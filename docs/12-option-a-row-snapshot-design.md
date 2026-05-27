# 12 — Option A: SELECT 시점 row snapshot 설계

[`docs/04-test-generator.md`](04-test-generator.md) 의 fixture 합성 정책 (Phase 0 한정 — INSERT만)을 확장. SUT가 분석 phase에 부팅 시점 시드 데이터 ( `data.sql`, Flyway, 운영 dump 등)를 읽는 경우, capture 시점에 발행되는 SELECT는 그 row의 *존재*만 알리고 *값*을 모르므로 fixture로 변환되지 않아 합성 테스트가 빈 DB에서 실패하는 한계가 있었다.

본 문서는 이 한계를 해결하기 위한 **Option A — SELECT 시점 row snapshot** 의 설계이다. 다른 옵션(B: 전체 DB diff snapshot / C: data.sql passthrough / D: cross-path INSERT 재활용) 은 평가 결과 기각. 특히 D는 테스트 케이스 간 의존성을 생성하여 병렬 실행 시 race condition을 유발하므로 graph-rag의 "결정적 합성 + 병렬 안전" 원칙에 위배.

## 1. 문제 정의

### 현재 동작
[`graph-rag-builder/.../CapturedSqlListener.java`](../graph-rag-builder/src/main/java/io/graphrag/builder/capture/CapturedSqlListener.java) 는 SQL 텍스트 + 파라미터 바인딩만 기록한다:
```java
CapturedSql sql = CapturedSqlBuilder.build(ctx.pathId(), qi.getQuery(), params, defaultSource);
```
ResultSet 은 들여다보지 않는다. [`FixtureComposer.fromCapturedSqls()`](../test-generator/src/main/java/io/graphrag/generator/compose/FixtureComposer.java) 는:
```java
if (sql.type() != CapturedSqlType.INSERT) continue;
```
SELECT는 폐기 → 합성 테스트에 before 블록 없음 → seeded data 없는 깨끗한 DB에서 404 / NPE 등.

### 시나리오
```
[capture phase]                            [generated test 실행 phase]
SUT 부팅 → data.sql 시드(owner id=1)      깨끗한 DB (data.sql 없음)
↓                                          ↓
GET /api/owners/1                          GET /api/owners/1
↓                                          ↓
Hibernate: SELECT * FROM owners WHERE id=? 404 — owner id=1 없음
↓                                          
CapturedSqlListener.afterQuery() 호출
↓
CapturedSql{type=SELECT, bindings=[1], raw="SELECT..."}
↓
FixtureComposer가 SELECT 폐기 → fixture 없음
```

### 해결 목표
capture phase에 SUT가 SELECT한 row의 *실제 값*을 같이 기록 → composer 가 그 값으로 `INSERT` fixture 합성 → 생성 테스트가 빈 DB에서도 통과.

## 2. 설계 원칙

| 원칙 | 적용 |
|---|---|
| **결정적 합성** | 같은 capture 입력 → 같은 fixture 출력. row snapshot도 시점만 결정적이면 OK |
| **병렬 안전** | snapshot 된 row를 각 테스트의 자체 fixture 로 inline. 테스트 간 의존성 없음 |
| **운영 부담 0** | snapshot 은 *분석 phase* 에만 수행. 운영 트래픽엔 영향 없음 (CaptureContext null 이면 noop) |
| **SUT 무수정** | listener 내부 변경. SUT 코드/설정 변경 없음 |
| **fail-safe** | snapshot 실패가 원본 query 동작을 막지 않음 |

## 3. 아키텍처 변경

### 3.1 모델 — `CapturedSql.readResultRows`

```java
public record CapturedSql(
    String id,
    String pathId,
    CapturedSqlType type,
    String rawSql,
    List<Binding> bindings,
    CapturedSqlSource source,
    SourceLocation sourceLocation,
    List<String> affectedTables,
    List<String> affectedColumns,
    List<Map<String, Object>> readResultRows   // ← 신규 (nullable, default empty)
)
```

- SELECT가 반환한 row 들의 컬럼명 → 값 매핑
- INSERT/UPDATE/DELETE에서는 항상 empty (이미 fixture 합성에 raw SQL 이 충분)
- Jackson 직렬화 호환: 기존 archive 의 `captured_sql.json` 에 새 필드 누락 시 default empty list 로 처리

### 3.2 캡처 — `CapturedSqlListener` 확장

```java
@Override
public void afterQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
    CaptureContext ctx = CaptureContext.current();
    if (ctx == null) return;
    for (QueryInfo qi : queryInfoList) {
        List<Object> params = flattenFirstBatch(qi);

        // 신규: SELECT일 때 row snapshot 시도
        List<Map<String, Object>> rows = List.of();
        CapturedSqlType type = CapturedSqlBuilder.detectType(qi.getQuery());
        if (type == CapturedSqlType.SELECT) {
            rows = trySnapshotRows(execInfo, qi.getQuery(), params);
        }

        CapturedSql sql = CapturedSqlBuilder.build(
            ctx.pathId(), qi.getQuery(), params, defaultSource, rows);
        ctx.addCapturedSql(sql);
    }
}

private List<Map<String, Object>> trySnapshotRows(
        ExecutionInfo info, String sql, List<Object> params) {
    try {
        Connection conn = info.getStatement().getConnection();
        String snapshotSql = rebuildAsSelectStar(sql);   // SELECT * 변환 (best-effort)
        try (PreparedStatement ps = conn.prepareStatement(snapshotSql)) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                return readAllRows(rs);
            }
        }
    } catch (Exception ignored) {
        return List.of();   // fail-safe: snapshot 실패 → 빈 리스트, 원본 동작은 그대로
    }
}
```

### 3.3 `SELECT *` 변환 (왜 필요한가)

원본 SQL이 `SELECT first_name FROM owners WHERE id = ?` 인 경우 :
- 그대로 재실행 → snapshot 에 `first_name` 만. INSERT 합성 시 다른 NOT NULL 컬럼 누락 → fixture 실패
- `SELECT * FROM owners WHERE id = ?` 로 재실행 → 모든 컬럼 확보 → INSERT 합성 안전

**정규식 기반 best-effort 변환** (Phase 1):
```java
static final Pattern SELECT_FROM_WHERE = Pattern.compile(
    "(?i)^\\s*SELECT\\s+.*?\\s+FROM\\s+(\\S+)\\s+WHERE\\s+(.*?)(?:ORDER BY|LIMIT|;|$)",
    Pattern.DOTALL);

private static String rebuildAsSelectStar(String sql) {
    Matcher m = SELECT_FROM_WHERE.matcher(sql);
    if (m.find()) {
        String table = m.group(1).replaceAll("[\"`]", "");
        String where = m.group(2).trim();
        return "SELECT * FROM " + table + " WHERE " + where;
    }
    return sql;   // 매칭 실패 → 원본 그대로
}
```

| 패턴 | 처리 |
|---|---|
| `SELECT ... FROM t WHERE ...` 단순 | ✅ `SELECT * FROM t WHERE ...` |
| JOIN 포함 | ⚠️ 정규식이 첫 FROM 만 잡음 — primary table 만 snapshot |
| 서브쿼리 / CTE | ⚠️ 첫 FROM 매칭만 — Phase 2+ AST 파서로 강화 |
| `SELECT COUNT(*)` | ⚠️ row 없음 → 빈 snapshot (fixture 없음, 무해) |
| `SELECT DISTINCT` | ✅ DISTINCT 무시하고 `SELECT *` 로 변환 |

### 3.4 동일 Connection 재사용의 의미

`Statement.getConnection()` 으로 *같은* Connection을 재사용:
- **장점**: 같은 트랜잭션에서 본 데이터를 그대로 봄. Hibernate가 같은 세션에서 INSERT 후 SELECT한 경우에도 visible
- **단점**: SUT의 cursor / autoCommit 상태에 영향 가능 → finally 블록에서 PreparedStatement 닫기 필수
- **격리 레벨**: SUT가 READ_COMMITTED 면 다른 트랜잭션의 commit 된 데이터까지. 시드 데이터는 부팅 시점 commit이므로 항상 visible

대안: 별도 Connection (DataSource.getConnection()) — 트랜잭션 분리되어 안전하나 SUT 트랜잭션 내부의 미커밋 변경은 못 봄. **Phase 1은 동일 Connection 선택** (시드 데이터 시나리오는 양쪽 다 OK이고, 더 일반적인 케이스 커버).

### 3.5 합성 — `FixtureComposer` 확장

```java
public static List<FixtureStatement> fromCapturedSqls(List<CapturedSql> captured) {
    List<FixtureStatement> result = new ArrayList<>();
    for (CapturedSql sql : captured) {
        if (sql.type() == CapturedSqlType.INSERT) {
            // 기존 로직: raw SQL 그대로
            result.add(new FixtureStatement(sql.rawSql(), valuesOf(sql), tableOf(sql)));
        } else if (sql.type() == CapturedSqlType.SELECT && !sql.readResultRows().isEmpty()) {
            // 신규: snapshot row → INSERT 합성
            String table = sql.affectedTables().get(0);
            for (Map<String, Object> row : sql.readResultRows()) {
                List<String> cols = new ArrayList<>(row.keySet());
                List<Object> vals = cols.stream().map(row::get).toList();
                String insertSql = "INSERT INTO " + table + " ("
                        + String.join(", ", cols) + ") VALUES ("
                        + String.join(", ", Collections.nCopies(cols.size(), "?")) + ")";
                result.add(new FixtureStatement(insertSql, vals, table));
            }
        }
    }
    return result;
}
```

### 3.6 Cleanup (after) — PK 추론

기존 `cleanupFor()` 는 `bindings.get(0)` 을 PK로 가정. SELECT-snapshot 으로 합성된 INSERT 는 binding 이 없으니 다른 전략 필요:

**Phase 1 정책**:
- snapshot 의 row 에 `id` 컬럼이 있으면 그 값으로 `DELETE FROM <table> WHERE id = ?`
- 없으면 컬럼들 전체 매칭 (`DELETE FROM <table> WHERE col1 = ? AND col2 = ? AND ...`) — 더 안전하지만 NULL 처리 까다로움
- Phase 2+: Hibernate metadata / JPA `@Id` 인식으로 정밀화

## 4. 데이터 흐름

```
[capture phase]                                             [archive]
SUT GET /api/owners/1
  ↓ Hibernate
  SELECT first_name, last_name, address FROM owners WHERE id=?  bindings=[1]
  ↓ ProxyDataSource listener afterQuery()
  CapturedSqlListener.trySnapshotRows():
    - SELECT * FROM owners WHERE id=?  ← rebuilt
    - ResultSet → [{id:1, first_name:"George", last_name:"Franklin", ...}]
  ↓ CapturedSqlBuilder.build()
  CapturedSql{
    type=SELECT,
    rawSql="SELECT first_name, last_name, ...",
    bindings=[Binding{0, 1, COMPUTED}],
    affectedTables=["owners"],
    readResultRows=[{id:1, first_name:"George", ...}]   ← 신규
  }
  ↓ archive.save()
  captured_sql.json: [{ ..., "readResultRows":[{...}] }]

[generator phase]
FixtureComposer.fromCapturedSqls(captured):
  → SELECT인데 readResultRows 가 있음
  → INSERT INTO owners (id, first_name, last_name, ...) VALUES (?, ?, ?, ...)
  ↓ TestSynthesizer
  생성된 테스트 before 블록:
    INSERT INTO owners (id, first_name, last_name, ...) VALUES (1, 'George', 'Franklin', ...)
  finally 블록:
    DELETE FROM owners WHERE id = 1

[generated test 실행 phase]
빈 DB → before INSERT → GET /api/owners/1 → 200 OK → DELETE cleanup
```

## 5. 변경 범위

| 모듈 | 파일 | 변경 |
|---|---|---|
| `shared-model` | `CapturedSql.java` | 신규 필드 `readResultRows` + 생성자 검증 |
| `shared-model` | `CapturedSqlTest.java` | round-trip 테스트 |
| `graph-rag-builder` | `CapturedSqlListener.java` | `trySnapshotRows()` + `rebuildAsSelectStar()` |
| `graph-rag-builder` | `CapturedSqlBuilder.java` | `build()` 오버로드 (rows 인자 받음) + `detectType()` public |
| `graph-rag-builder` | `CapturedSqlListenerTest.java` | SELECT seeded data 테스트 |
| `test-generator` | `FixtureComposer.java` | SELECT + readResultRows → INSERT 합성 분기 |
| `test-generator` | `FixtureComposerTest.java` | snapshot row → INSERT 합성 테스트 |
| `samples/demo-sut` | `PhaseSeedDataE2eTest.java` (신규) | data.sql 시드 + capture + 합성 + 실행 |
| `docs` | `12 ~ 17-option-a-*.md` | phase별 진행 문서 |

총 코드 변경 예상: ~250 line 신규 + ~30 line 수정

## 6. 위험과 완화

| 위험 | 완화 |
|---|---|
| SELECT 재실행이 SUT의 트랜잭션 상태에 영향 | try-with-resources로 PreparedStatement/ResultSet 즉시 닫음. autoCommit 안 건드림 |
| 재실행 SQL이 lock 잡음 | row-level lock 없는 plain SELECT — issue 가능성 낮음. SELECT FOR UPDATE만 별도 처리 (Phase 2+) |
| 큰 ResultSet (예: `SELECT * FROM users`) | snapshot 무제한 → 큰 fixture. 정책: row 100개 cap 추가 (Phase 1 옵션) |
| JOIN / 서브쿼리 | best-effort 정규식. primary table 만 snapshot. 알려진 한계로 문서화 |
| 비-PK 기준 SELECT (e.g., `WHERE last_name = ?`) | 같은 row 가 여러 SELECT 에 걸쳐 중복 snapshot → 중복 INSERT fixture → fixture 합성 시 dedup 필요 |
| 컬럼명 case sensitivity | `ResultSetMetaData.getColumnLabel()` 사용 (alias 우선) |

## 7. 검증 계획

### Phase 2 (model) 단위 테스트
- `CapturedSql` 생성자 — readResultRows null/empty/non-empty
- Jackson round-trip — 새 필드 직렬화/역직렬화
- 기존 archive (필드 없음) 역호환 로드

### Phase 3 (capture) 단위 테스트
- H2 in-mem 에 seed row 삽입
- ProxyDataSource + CapturedSqlListener attached
- `SELECT first_name FROM users WHERE id = ?` 발행
- captured.readResultRows 에 `[{id:1, first_name:"...", ...}]` 검증
- snapshot 실패 시뮬레이션 (잘못된 SQL pattern) → 빈 리스트 + 원본 query 결과 무영향

### Phase 4 (composer) 단위 테스트
- SELECT + readResultRows → `INSERT INTO ... VALUES ...` 생성 검증
- 동일 PK row 중복 SELECT → dedup
- cleanup DELETE 생성 검증

### Phase 5 (demo-sut E2E)
- data.sql 시드 (user id=1, "John")
- capture: `mvc.perform(get("/api/users/1"))`
- archive 저장
- 별도 깨끗한 H2 인스턴스에 합성 테스트 실행 → before INSERT 동작 → 200 통과 → DELETE cleanup

### Phase 6 (petclinic 재검증)
- 수동 `captured_sql.json` 제거
- demo-sut에 사용한 capture harness를 petclinic 에 invasive 통합 (BeanPostProcessor pattern)
- 자동 captured SELECT → INSERT fixture 합성 검증

## 8. 비-목표 (Phase 2+ 로 deferred)

- JOIN / subquery 정밀 파싱 (AST 기반)
- SELECT FOR UPDATE / pessimistic lock 처리
- `@Id` annotation 인식 통한 PK 정확 추출
- snapshot row 의 FK 의존성 그래프 정렬 (자식 row 먼저 INSERT 되는 등)
- 큰 ResultSet streaming (현재는 전부 메모리)
- Hibernate L1/L2 cache 인지 (cache hit 시 SELECT 안 나감 → snapshot 누락)

## 9. 진행 순서 + 산출 문서

| Phase | 작업 | 문서 |
|---|---|---|
| 1 (현재) | 분석 + 설계 | **본 문서** (`12-option-a-row-snapshot-design.md`) |
| 2 | 모델 확장 | `13-option-a-model.md` |
| 3 | capture 구현 | `14-option-a-capture.md` |
| 4 | composer 구현 | `15-option-a-composer.md` |
| 5 | demo-sut E2E | `16-option-a-e2e.md` |
| 6 | petclinic 재검증 | `17-option-a-petclinic.md` |

각 phase 끝에 해당 문서 + 코드 + 테스트 GREEN 상태로 마무리.

## 참조

- [`docs/04-test-generator.md`](04-test-generator.md) — 기존 fixture 합성 정책
- [`docs/11-datasource-proxy-wrap.md`](11-datasource-proxy-wrap.md) — DataSource wrap 패턴
- [`graph-rag-builder/.../CapturedSqlListener.java`](../graph-rag-builder/src/main/java/io/graphrag/builder/capture/CapturedSqlListener.java)
- [`graph-rag-builder/.../CapturedSqlBuilder.java`](../graph-rag-builder/src/main/java/io/graphrag/builder/capture/CapturedSqlBuilder.java)
- [`test-generator/.../FixtureComposer.java`](../test-generator/src/main/java/io/graphrag/generator/compose/FixtureComposer.java)
- [`shared-model/.../CapturedSql.java`](../shared-model/src/main/java/io/graphrag/model/CapturedSql.java)

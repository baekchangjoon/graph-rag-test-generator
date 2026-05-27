# 14 — Option A Phase 3: Capture 측 — SELECT row snapshot

[`docs/12-option-a-row-snapshot-design.md`](12-option-a-row-snapshot-design.md) §3.2 ~ §3.4 의 capture 측 변경 구현. SUT 가 부팅 시 시드된 데이터를 SELECT 할 때, 같은 Connection 으로 재실행하여 row 들의 실제 값을 함께 기록한다.

## 변경 파일

| 파일 | 역할 |
|---|---|
| [`CapturedSqlBuilder.java`](../graph-rag-builder/src/main/java/io/graphrag/builder/capture/CapturedSqlBuilder.java) | `build(...)` 오버로드 추가 (rows 인자) + `detectType()` public 승격 |
| [`CapturedSqlListener.java`](../graph-rag-builder/src/main/java/io/graphrag/builder/capture/CapturedSqlListener.java) | `trySnapshotRows()` + `rebuildAsSelectStar()` + `readAllRows()` |
| [`CapturedSqlListenerTest.java`](../graph-rag-builder/src/test/java/io/graphrag/builder/capture/CapturedSqlListenerTest.java) | 신규 — 8개 테스트 |

## CapturedSqlBuilder 변경

```java
// 신규 오버로드: rows 인자 받아 CapturedSql.readResultRows 로 전달
public static CapturedSql build(String pathId, String rawSql,
                                List<?> parameterValues, CapturedSqlSource source,
                                List<Map<String, Object>> readResultRows) { ... }

// 기존 4-arg 시그니처는 5-arg 에 List.of() 위임 (legacy 호출자 무수정)
public static CapturedSql build(String pathId, String rawSql,
                                List<?> parameterValues, CapturedSqlSource source) {
    return build(pathId, rawSql, parameterValues, source, List.of());
}

// detectType()  private → public — Listener 가 SELECT 분기 검사에 사용
public static CapturedSqlType detectType(String sql) { ... }
```

## CapturedSqlListener 핵심 로직

```java
@Override
public void afterQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
    CaptureContext ctx = CaptureContext.current();
    if (ctx == null) return;
    for (QueryInfo qi : queryInfoList) {
        List<Object> params = flattenFirstBatch(qi);
        List<Map<String, Object>> rows = List.of();
        if (CapturedSqlBuilder.detectType(qi.getQuery()) == CapturedSqlType.SELECT) {
            rows = trySnapshotRows(execInfo, qi.getQuery(), params);   // ← 신규
        }
        CapturedSql sql = CapturedSqlBuilder.build(
                ctx.pathId(), qi.getQuery(), params, defaultSource, rows);
        ctx.addCapturedSql(sql);
    }
}

List<Map<String, Object>> trySnapshotRows(ExecutionInfo info, String sql, List<Object> params) {
    try {
        Connection conn = info.getStatement().getConnection();
        if (conn == null || conn.isClosed()) return List.of();
        String snapshotSql = rebuildAsSelectStar(sql);
        try (PreparedStatement ps = conn.prepareStatement(snapshotSql)) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                return readAllRows(rs);
            }
        }
    } catch (Exception ignored) {
        return List.of();   // fail-safe
    }
}
```

### `SELECT *` 변환 정규식

```java
private static final Pattern SELECT_FROM_WHERE = Pattern.compile(
    "(?is)^\\s*SELECT\\s+.*?\\s+FROM\\s+(\\S+)\\s+(?:AS\\s+\\S+\\s+)?WHERE\\s+(.*?)" +
            "(?:\\s+ORDER\\s+BY\\b|\\s+LIMIT\\b|\\s+GROUP\\s+BY\\b|\\s*;?\\s*$)");

static String rebuildAsSelectStar(String sql) {
    Matcher m = SELECT_FROM_WHERE.matcher(sql);
    if (m.find()) {
        String table = m.group(1).replaceAll("[\"`]", "");
        String where = m.group(2).trim();
        if (!table.contains(",") && !table.contains(" ")) {
            return "SELECT * FROM " + table + " WHERE " + where;
        }
    }
    return sql;   // 매칭 실패 / 복합 FROM → 원본 그대로
}
```

| 입력 | 출력 |
|---|---|
| `SELECT first_name FROM owners WHERE id = ?` | `SELECT * FROM owners WHERE id = ?` |
| `SELECT id FROM owners WHERE id > ? ORDER BY id LIMIT 10` | `SELECT * FROM owners WHERE id > ?` |
| `SELECT o.first_name FROM owners o JOIN pets p ON p.owner_id = o.id WHERE o.id = ?` | 원본 그대로 (alias + JOIN) |
| `SELECT id FROM (SELECT id FROM owners) WHERE id = ?` | 원본 그대로 (subquery FROM) |

### Safety guard

- `Statement.getConnection()` 으로 같은 Connection 재사용 — 같은 transaction visibility
- try-with-resources 로 PreparedStatement/ResultSet 즉시 닫음 (autoCommit 미변경)
- `if (conn == null || conn.isClosed()) return List.of();` 으로 closed-connection race 방지
- 모든 예외 catch → 원본 query 결과에 영향 없음
- `MAX_SNAPSHOT_ROWS = 100` 으로 큰 ResultSet 폭주 방지

## 신규 테스트 (8개) — 전부 GREEN

| # | 테스트 | 검증 |
|---|---|---|
| 1 | `capturesSelectWithRowSnapshotWhenCaptureContextActive` | seeded owner 1 SELECT → snapshot 에 `{ID:1, FIRST_NAME:"George", LAST_NAME:"Franklin"}` (SELECT * 변환 동작) |
| 2 | `multipleRowsCapturedForRangeSelect` | `WHERE id <= 10` 으로 2 row snapshot |
| 3 | `selectWithoutCaptureContextIsNoop` | `CaptureContext.current() == null` 일 때 listener 무시 |
| 4 | `insertDoesNotTriggerSnapshot` | INSERT 는 snapshot 호출조차 안 함 (readResultRows empty) |
| 5 | `snapshotFailureDoesNotBreakOriginalQuery` | subquery FROM → 정규식 매칭 안 함 → fallback, 원본 query 정상 동작 |
| 6 | `rebuildAsSelectStarTransformsSimpleSelect` | 컬럼 부분집합 SELECT → `SELECT *` |
| 7 | `rebuildAsSelectStarPreservesOriginalForJoin` | JOIN 은 원본 보존 |
| 8 | `rebuildAsSelectStarStripsOrderByAndLimit` | `ORDER BY` / `LIMIT` 분리 |

## 검증

```
$ ./gradlew :graph-rag-builder:test --tests "io.graphrag.builder.capture.CapturedSqlListenerTest"
CapturedSqlListenerTest > rebuildAsSelectStarStripsOrderByAndLimit() PASSED
... (8 tests)
BUILD SUCCESSFUL

$ ./gradlew build
BUILD SUCCESSFUL — 89 actionable tasks
```

## 알려진 한계 (Phase 2+ deferred)

| 한계 | 영향 | 후속 작업 |
|---|---|---|
| JOIN / 서브쿼리 정규식 미커버 | 원본 SQL 그대로 재실행 → 컬럼 부분집합 가능 → INSERT 합성 시 NOT NULL 누락 위험 | AST 기반 SQL 파서 (jsqlparser) 도입 |
| Hibernate L1/L2 cache hit | listener 까지 SQL 안 옴 → snapshot 누락 | `EntityManager.clear()` 옵션 제공 (별도) |
| `SELECT FOR UPDATE` | 재실행 시 두 번째 lock 시도 → deadlock 가능성 | UPDATE-intent SELECT 감지 후 skip |
| 100+ row | `MAX_SNAPSHOT_ROWS` 로 잘림 | 페이지네이션 / sampling 정책 |
| 비-PK 기준 SELECT (`WHERE last_name = ?`) | 같은 row 가 여러 SELECT 에 걸쳐 중복 snapshot | composer 측 dedup (Phase 4) |

## 다음 단계

Phase 4: `FixtureComposer.fromCapturedSqls()` 에 SELECT + `readResultRows` → INSERT 합성 로직 추가. `cleanupFor()` 도 신규 fixture 포함하여 DELETE 자동 생성. PK 추론 정책 (snapshot row 에 `id` 컬럼 있으면 사용, 없으면 컬럼 전체 매칭).

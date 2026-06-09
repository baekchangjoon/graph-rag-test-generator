# 13 — Option A Phase 2: shared-model 확장

[`docs/12-option-a-row-snapshot-design.md`](12-option-a-row-snapshot-design.md) §3.1 의 모델 확장을 구현한다. 본 phase는 **데이터 모델 + JSON 직렬화 + 역호환** 만 다루며, 실제 capture/composer 통합은 Phase 3/4에서.

## 변경 요약

`io.graphrag.model.CapturedSql` 에 `readResultRows: List<Map<String, Object>>` 필드 추가.

### Before
```java
public record CapturedSql(
    String id, String pathId, CapturedSqlType type, String rawSql,
    List<Binding> bindings, CapturedSqlSource source,
    SourceLocation sourceLocation,
    List<String> affectedTables, List<String> affectedColumns) { ... }
```

### After
```java
public record CapturedSql(
    String id, String pathId, CapturedSqlType type, String rawSql,
    List<Binding> bindings, CapturedSqlSource source,
    SourceLocation sourceLocation,
    List<String> affectedTables, List<String> affectedColumns,
    List<Map<String, Object>> readResultRows   // ← 신규
) {
    public CapturedSql {
        // 기존 requireNonNull / List.copyOf 동일 + readResultRows 동일 처리
        readResultRows = List.copyOf(Objects.requireNonNullElse(readResultRows, List.of()));
    }

    /** Legacy convenience constructor — 기존 9-arg 호출자 무수정 컴파일 */
    public CapturedSql(...9 args without readResultRows) {
        this(..., List.of());
    }
}
```

## 설계 결정

### Q1. 왜 record 신규 컴포넌트로? (별도 wrapper 클래스가 아닌 이유)
- `CapturedSql` 은 archive `captured_sql.json` 의 element 타입. 별도 wrapper 로 두면 archive 스키마가 분기되어 reader/writer 모두 수정 필요
- 같은 SQL의 메타와 row snapshot은 1:1 관계 → 같은 record 안에 두는 게 자연스러움

### Q2. 왜 9-arg legacy constructor를 남겨두나?
- 호출자 13개 (production + test). 한꺼번에 수정하면 PR 노이즈만 큼
- 9-arg는 10-arg에 `List.of()` 위임하는 thin shim — 별도 동작 분기 없음
- Phase 3 (capture 측) 만 10-arg를 채워 호출. 나머지 (테스트 스텁, FileGraphStore 등)는 자연스럽게 빈 리스트 유지

### Q3. JSON 필드명?
`JsonMappers.standard()` 가 `PropertyNamingStrategies.SNAKE_CASE` → JSON 필드명은 `read_result_rows`. archive 외부 도구가 읽을 때 직관적.

### Q4. 역호환 — 기존 archive에 새 필드가 없으면?
`JsonMappers.standard()` 에 `FAIL_ON_UNKNOWN_PROPERTIES = false`. 추가 검증: 빠진 필드는 null로 전달됨 → compact constructor 가 `List.of()` 로 대체. **기존 archive 로드 가능 (`deserializesLegacyJsonWithoutReadResultRowsField` 테스트 통과)**.

### Q5. `Map<String, Object>` 타입 선택
- key: 컬럼명 (case-sensitive — JDBC `ResultSetMetaData.getColumnLabel()`)
- value: JDBC 가 반환한 Java 객체 (Integer, String, Timestamp 등) — Jackson이 그대로 직렬화
- 순서 유지 위해 capture 측은 `LinkedHashMap` 사용 권장. record 자체는 `List.copyOf` 로 immutable 보장

## 신규 테스트 (4개)

`CapturedSqlTest`:

1. **`constructsInsertWithBindings`** *(기존)* — 회귀 검증
2. **`jsonRoundTripPreservesAllFields`** *(기존)* — 회귀
3. **`legacyConstructorDefaultsReadResultRowsToEmpty`** *(신규)* — 9-arg constructor 호출 시 `readResultRows()` 빈 리스트
4. **`selectWithReadResultRowsRoundTrip`** *(신규)* — SELECT + row 1개로 직렬화/역직렬화 round-trip. JSON에 `"read_result_rows":` + `"first_name":"George"` 포함 검증
5. **`deserializesLegacyJsonWithoutReadResultRowsField`** *(신규)* — 9개 필드만 있는 legacy JSON 입력에서 `readResultRows()` 빈 리스트

## 검증 결과

```
$ ./gradlew :shared-model:test
BUILD SUCCESSFUL
$ ./gradlew build
BUILD SUCCESSFUL — 89 actionable tasks
```

전체 회귀 GREEN. 기존 13개 call site 무수정 컴파일 통과.

## 다음 단계

Phase 3: `CapturedSqlListener.afterQuery()` 에 `trySnapshotRows()` 추가. SELECT 시 `ExecutionInfo.getStatement().getConnection()` 으로 같은 Connection 재사용, `SELECT *` 변환, ResultSet → `List<Map<String,Object>>` 변환. fail-safe (snapshot 실패가 원본 query를 막지 않음).

## 변경 파일

- `shared-model/src/main/java/io/graphrag/model/CapturedSql.java` — 신규 필드 + 10-arg/9-arg 두 생성자
- `shared-model/src/test/java/io/graphrag/model/CapturedSqlTest.java` — 3개 테스트 추가

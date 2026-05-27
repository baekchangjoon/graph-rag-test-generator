# 15 — Option A Phase 4: FixtureComposer — SELECT snapshot → INSERT 합성

[`docs/12-option-a-row-snapshot-design.md`](12-option-a-row-snapshot-design.md) §3.5 ~ §3.6 의 합성 측 변경. capture phase 가 채워 둔 `readResultRows` 를 그대로 INSERT 문으로 변환하여 생성 테스트의 before 블록에 삽입한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| [`FixtureComposer.java`](../test-generator/src/main/java/io/graphrag/generator/compose/FixtureComposer.java) | SELECT + readResultRows 처리, dedup, cleanup 합성 |
| [`FixtureComposerTest.java`](../test-generator/src/test/java/io/graphrag/generator/compose/FixtureComposerTest.java) | 7개 신규 / 1개 갱신 테스트 |

## 처리 매트릭스

| 입력 `CapturedSql.type` | `readResultRows` | 결과 |
|---|---|---|
| INSERT | (무시) | raw SQL 그대로 fixture (Phase 0 기존 동작) |
| **SELECT** | **non-empty** | **각 row → `INSERT INTO <t> (col1, col2, ...) VALUES (?, ?, ...)`** |
| SELECT | empty | 무시 (snapshot 실패 / 0 row) |
| UPDATE / DELETE / DDL | — | 무시 |

## 합성 로직

```java
public static List<FixtureStatement> fromCapturedSqls(List<CapturedSql> captured) {
    List<FixtureStatement> result = new ArrayList<>();
    Set<String> dedupKeys = new LinkedHashSet<>();
    for (CapturedSql sql : captured) {
        if (sql.type() == CapturedSqlType.INSERT) {
            result.add(insertFromRawSql(sql));
        } else if (sql.type() == CapturedSqlType.SELECT && !sql.readResultRows().isEmpty()) {
            String table = sql.affectedTables().get(0);
            for (Map<String, Object> row : sql.readResultRows()) {
                String key = dedupKey(table, row);
                if (!dedupKeys.add(key)) continue;        // 중복 row → 한 번만
                result.add(insertFromSnapshotRow(table, row));
            }
        }
    }
    return result;
}
```

### INSERT 합성 (snapshot row → SQL)
```java
private static FixtureStatement insertFromSnapshotRow(String table, Map<String, Object> row) {
    List<String> cols = new ArrayList<>(row.keySet());      // LinkedHashMap 순서 유지
    List<Object> vals = cols.stream().map(row::get).toList();
    String placeholders = String.join(", ", Collections.nCopies(cols.size(), "?"));
    String sql = "INSERT INTO " + table + " ("
                 + String.join(", ", cols) + ") VALUES (" + placeholders + ")";
    return new FixtureStatement(sql, vals, table);
}
```

### Dedup 정책
- 같은 `(table, PK value)` row 가 여러 SELECT 에서 snapshot 된 경우 → 한 번만 INSERT 합성 (PK conflict 방지)
- PK 컬럼 추론: row 의 키 중 `"id"` (case-insensitive) 우선
- PK 없으면 전체 row map 을 key 로 사용 (보수적, 거의 모든 row 가 dedup 단위)

### Cleanup (`cleanupFor`) — DELETE 자동 합성

```java
private static FixtureStatement deleteFromSnapshotRow(String table, Map<String, Object> row) {
    String pkCol = findIdColumn(row);
    if (pkCol != null) {
        // PK 기준 단순 DELETE
        return new FixtureStatement(
                "DELETE FROM " + table + " WHERE " + pkCol + " = ?",
                List.of(row.get(pkCol)), table);
    }
    // PK 없으면 전체 컬럼 AND. NULL 은 IS NULL 처리
    StringBuilder where = new StringBuilder();
    List<Object> params = new ArrayList<>();
    int i = 0;
    for (var e : row.entrySet()) {
        if (i++ > 0) where.append(" AND ");
        if (e.getValue() == null) where.append(e.getKey()).append(" IS NULL");
        else { where.append(e.getKey()).append(" = ?"); params.add(e.getValue()); }
    }
    return new FixtureStatement("DELETE FROM " + table + " WHERE " + where, params, table);
}
```

FK 역순 유지: 기존 `Collections.reverse(deletes)` 그대로 (자식 row 가 나중에 INSERT 됐다는 가정).

## 신규/갱신 테스트 (FixtureComposerTest, 총 8개 전부 GREEN)

| # | 테스트 | 검증 |
|---|---|---|
| 1 | `buildsInsertFixtureForCapturedInsert` *(기존)* | INSERT raw SQL 그대로 → 회귀 |
| 2 | `ignoresSelectStatementsWithoutReadResultRows` *(갱신)* | SELECT + 빈 rows → 무시 |
| 3 | `cleanupStatementsAreFkReverseOrder` *(기존)* | INSERT 기반 cleanup 역순 → 회귀 |
| 4 | **`selectWithReadResultRowsBecomesInsertFixture`** | SELECT row → `INSERT INTO owners (id, first_name, last_name) VALUES (?, ?, ?)` + params 검증 |
| 5 | **`multipleSnapshotRowsBecomeMultipleInserts`** | 2 row snapshot → 2 INSERT |
| 6 | **`duplicateSnapshotRowsDeduped`** | 같은 (id=1) row 두 번 → INSERT 1번 (dedup) |
| 7 | **`cleanupForSelectSnapshotUsesIdColumn`** | snapshot row 에 `id` 컬럼 있음 → `DELETE FROM owners WHERE id = ?` |
| 8 | **`cleanupForSnapshotWithoutIdUsesAllColumnsAnd`** | `id` 없으면 모든 컬럼 AND fallback |

## 합성 결과 예시

### 입력 (Phase 3 capture 산출물)
```json
{
  "id": "sql-x", "path_id": "p1", "type": "SELECT",
  "raw_sql": "SELECT first_name FROM owners WHERE id = ?",
  "bindings": [{"position": 0, "value": 1, "origin": "COMPUTED"}],
  "affected_tables": ["owners"],
  "read_result_rows": [
    {"id": 1, "first_name": "George", "last_name": "Franklin",
     "address": "110 W. Liberty St.", "city": "Madison", "telephone": "6085551023"}
  ]
}
```

### 출력 (FixtureComposer)
```java
// before
INSERT INTO owners (id, first_name, last_name, address, city, telephone) VALUES (?, ?, ?, ?, ?, ?)
// params: [1, "George", "Franklin", "110 W. Liberty St.", "Madison", "6085551023"]

// after (cleanup)
DELETE FROM owners WHERE id = ?
// params: [1]
```

이 fixture 가 `TestSynthesizer.appendPathTestMethod()` 의 try/finally 블록에 삽입되어 다음과 같은 테스트 코드 생성:

```java
@Test
void path_p1() throws Exception {
    try (Connection conn = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASS)) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO owners (id, first_name, last_name, address, city, telephone) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, 1);
            ps.setObject(2, "George");
            ps.setObject(3, "Franklin");
            ps.setObject(4, "110 W. Liberty St.");
            ps.setObject(5, "Madison");
            ps.setObject(6, "6085551023");
            ps.executeUpdate();
        }
    }
    try {
        given().when().get("/api/owners/1").then().statusCode(200);
    } finally {
        try (Connection conn = ...) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM owners WHERE id = ?")) {
                ps.setObject(1, 1);
                ps.executeUpdate();
            }
        }
    }
}
```

## 검증

```
$ ./gradlew :test-generator:test --tests "FixtureComposerTest"
8 tests PASSED
$ ./gradlew build
BUILD SUCCESSFUL — 89 actionable tasks
```

## 다음 단계

Phase 5: demo-sut 에 시드 데이터 시나리오 E2E 추가. data.sql 로 owner row 1개 seed → capture phase 에서 `GET /api/owners/1` → 생성된 archive 가 SELECT + readResultRows 포함하는지 + 합성 테스트의 before 블록에 INSERT 자동 생성되는지 검증.

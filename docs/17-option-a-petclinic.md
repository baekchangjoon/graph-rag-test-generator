# 17 — Option A Phase 6: petclinic 재검증 (사용자 입력 단순화)

[`docs/12-option-a-row-snapshot-design.md`](12-option-a-row-snapshot-design.md) 의 최종 사용자 가치 검증 — **사용자가 직접 INSERT SQL 을 작성하지 않고도, SELECT + readResultRows 만으로 동일한 합성 테스트가 통과** 함을 외부 실제 프로젝트(petclinic) 에 적용해 확인한다.

## 시나리오

이전(`docs/16` 까지) 의 petclinic 수동 archive 패턴 vs Option A 적용 후 archive 패턴.

### Before — manual INSERT (`docs/17` 이전 우리 작업)
사용자가 `captured_sql.json` 에 직접 INSERT 문 + 모든 컬럼 + bindings 작성:
```json
[{"id":"f-p10","path_id":"p10","type":"INSERT",
  "raw_sql":"INSERT INTO owners(id, first_name, last_name, address, city, telephone) VALUES (?, ?, ?, ?, ?, ?)",
  "bindings":[
    {"position":0,"value":500,"origin":"LITERAL","origin_ref":null},
    {"position":1,"value":"Fx","origin":"LITERAL","origin_ref":null},
    ... (6개 bindings)
  ],
  "source":"JPA_REPOSITORY_DERIVED",
  "source_location":{...},
  "affected_tables":["owners"],
  "affected_columns":["id","first_name",...,"telephone"]}]
```
→ 사용자가 INSERT 의 컬럼 순서, placeholder, position-binding 매칭 등 세부사항을 모두 관리.

### After — Option A 자동 capture (이번 phase)
graph-rag-builder 가 capture phase 에 발행한 SELECT + 그 결과 snapshot 만 archive 에 저장:
```json
[{"id":"sql-sel-1","path_id":"p10_opta","type":"SELECT",
  "raw_sql":"SELECT first_name FROM owners WHERE id = ?",
  "bindings":[{"position":0,"value":999,"origin":"COMPUTED","origin_ref":null}],
  "source":"JPA_REPOSITORY_DERIVED",
  "source_location":{...},
  "affected_tables":["owners"],"affected_columns":[],
  "read_result_rows":[
    {"id":999,"first_name":"OptaSnap","last_name":"Owner999",
     "address":"999 Snapshot Way","city":"AutoCity","telephone":"9990000999"}
  ]}]
```
→ **사용자 작성 부담**: SELECT 한 줄 (또는 자동 capture) + row map (key=column, value=값). INSERT 작성 / column 순서 / position binding 관리 없음.

## test-generator 출력 (Option A 입력)

자동으로 다음 코드가 생성됨:
```java
@Test
void path_p10_opta() throws Exception {
    String testId = ...;
    try (Connection conn = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASS)) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO owners (id, first_name, last_name, address, city, telephone) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, 999);
            ps.setObject(2, "OptaSnap");
            ps.setObject(3, "Owner999");
            ps.setObject(4, "999 Snapshot Way");
            ps.setObject(5, "AutoCity");
            ps.setObject(6, "9990000999");
            ps.executeUpdate();
        }
    }
    try {
        given().contentType(ContentType.JSON).body("{}")
            .when().get("/api/owners/999")
            .then().statusCode(200);
    } finally {
        try (Connection conn = ...) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM owners WHERE id = ?")) {
                ps.setObject(1, 999);
                ps.executeUpdate();
            }
        }
    }
}
```

= manual INSERT 케이스와 **기능적으로 동일**. composer 가 readResultRows 로부터 INSERT SQL/bindings/cleanup DELETE 자동 합성.

## 실행 결과

```
$ mvn test
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
        -- in com.example.gen.pcopta.p10.T_999GetTest
[INFO] BUILD SUCCESS
```

깨끗한 Postgres (id=999 미존재) + petclinic SUT 환경에서 **단일 실행 PASS**. 동작:
1. test fixture: `INSERT INTO owners(...id=999...)`
2. RestAssured: GET /api/owners/999 → petclinic 이 row 발견 → 200 OK
3. finally: `DELETE FROM owners WHERE id=999`

## 사용자 입력 비교 (line count)

| 항목 | Before (manual INSERT) | After (Option A) |
|---|---|---|
| `captured_sql.json` | 13 줄 (bindings 6 + position 6 + meta) | 9 줄 (row map 6 column + bindings 1) |
| 사용자 인지 부담 | 컬럼 순서 / position-value 매칭 / placeholder | row 의 (column, value) 매핑만 |
| 실제 자동화 가능성 | 사용자가 INSERT 직접 작성 필요 (수동) | graph-rag-builder 가 capture phase 에 *완전 자동 추출* (Phase 7 통합 시) |

## Phase 7 (TODO) — petclinic 에 진짜 capture harness 통합

이번 phase 는 "Option A 의 출력 형태 archive" 를 사람이 손으로 만들어서 합성/실행한 데모. 진짜 자동 capture (petclinic 의 SUT JVM 안에서 `BeanPostProcessor` + `ProxyDataSourceBuilder.listener(new CapturedSqlListener())` 통합) 는 별개 작업:

1. graph-rag artifacts publishToMavenLocal (Gradle `maven-publish` 플러그인 추가)
2. petclinic 의 `pom.xml` 에 graph-rag-builder + shared-model + datasource-proxy 를 test scope 로 추가
3. petclinic 의 `src/test/java` 에 [`docs/11-datasource-proxy-wrap.md`](11-datasource-proxy-wrap.md) 의 `@TestConfiguration + BeanPostProcessor` 패턴 적용
4. "scout test" 작성:
   ```java
   @Test void scoutGetOwner() {
       CaptureContext.set(new CaptureContext("get-owner-1"));
       mvc.perform(get("/api/owners/1")).andExpect(status().isOk());
       List<CapturedSql> captured = CaptureContext.current().capturedSql();
       new GraphArchive(Paths.get("/tmp/petclinic-archive")).addCapturedSql(captured).save();
   }
   ```
5. 출력 archive 를 test-generator CLI 에 입력 → 별개 깨끗한 Postgres 에서 합성 테스트 실행

이 통합 작업은 petclinic 소스 수정이 필요한데 (현재 우리가 했던 SecurityConfig 수정과 같은 규모 ~1 파일), Phase 7 또는 별도 PR 로 분리.

## 검증 결과 요약

| 항목 | 상태 |
|---|---|
| Option A archive 형식 (`read_result_rows`) 로 test-generator 동작 | ✅ |
| 생성된 테스트가 petclinic 실 SUT 에서 통과 | ✅ |
| `INSERT INTO owners (id, first_name, ..., telephone) VALUES (?, ?, ..., ?)` 자동 합성 | ✅ |
| `DELETE FROM owners WHERE id = ?` cleanup 자동 합성 | ✅ |
| 사용자 입력에서 INSERT SQL / position binding 제거 | ✅ |
| 회귀: 기존 Phase 0~5 E2E / 단위 테스트 GREEN | ✅ |
| 진짜 in-process auto-capture (Phase 7) | 📄 별도 작업으로 deferred |

## 누적 변경 사항 (Phase 1 ~ 6)

| 파일 | 변경 종류 |
|---|---|
| `docs/12 ~ 17-option-a-*.md` | 신규 (6개 문서) |
| `shared-model/.../CapturedSql.java` | 신규 필드 + 9-arg legacy constructor |
| `shared-model/.../CapturedSqlTest.java` | 3개 테스트 추가 |
| `graph-rag-builder/.../CapturedSqlBuilder.java` | `build()` 5-arg 오버로드 + `detectType()` public |
| `graph-rag-builder/.../CapturedSqlListener.java` | `trySnapshotRows()` + `rebuildAsSelectStar()` |
| `graph-rag-builder/.../CapturedSqlListenerTest.java` (신규) | 8개 테스트 |
| `test-generator/.../FixtureComposer.java` | SELECT + readResultRows 분기, dedup, cleanup |
| `test-generator/.../FixtureComposerTest.java` | 7개 신규 / 1개 갱신 |
| `samples/demo-sut/.../PhaseSeedDataE2eTest.java` (신규) | seeded data E2E |

총 코드 (구현) ≈ 200 line 추가 / 30 line 수정. 테스트 ≈ 350 line 추가. 문서 ≈ 1500 line.

전체 빌드 89 task GREEN, 회귀 0.

## 산출물 (Phase 6 데모)

```
/tmp/gen-e2e/petclinic-arch-opta/p10-opta/
  endpoints.json
  paths.json
  captured_sql.json        ← SELECT + read_result_rows 형식
  captured_http.json
/tmp/gen-e2e/petclinic-arch-opta/out/com/example/gen/pcopta/p10/
  T_999GetTest.java        ← 자동 합성된 테스트
/tmp/gen-e2e/petclinic-opta-runner/
  pom.xml
  src/test/java/...        ← 실행 가능 runner
```

## 다음 단계 (옵션)

- Phase 7: petclinic 에 in-process auto-capture 통합 (publishToMavenLocal + BeanPostProcessor scout test)
- 한계 정리: PK 정확 추출 (`@Id` annotation 인식), JOIN/subquery 정밀 파싱, batch INSERT row snapshot
- end-to-end automation: `graph-rag-builder run --sut <jar> --endpoint <id>` 같은 CLI 로 위 흐름 일괄 실행

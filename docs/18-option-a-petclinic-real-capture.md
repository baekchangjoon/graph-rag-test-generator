# 18 — Option A Phase 7: petclinic 실 in-process auto-capture (수동 archive 제로)

[`docs/17-option-a-petclinic.md`](17-option-a-petclinic.md) 가 *"capture 형식의 archive"* 를 hand-craft한 데모였다면, 본 phase 는 **graph-rag-builder 가 petclinic JVM 안에서 실제로 SQL + readResultRows 를 자동 캡처** 한 archive 로 합성 → 깨끗한 Postgres 에서 실 SUT 대상 PASS 까지 가는 완전 자동 사이클을 검증한다.

## 변경 (graph-rag 측)

| 파일 | 변경 |
|---|---|
| [`build.gradle.kts`](../build.gradle.kts) | 모든 java 서브프로젝트에 `maven-publish` 적용 + `publications.maven { from(components.java) }` |
| [`graph-rag-builder/build.gradle.kts`](../graph-rag-builder/build.gradle.kts) | spring-boot 의 bootJar classifier=`boot`, plain `jar` enabled + classifier 비움 (외부 프로젝트가 `testImplementation("io.graphrag:graph-rag-builder:...")` 로 받을 수 있게) |

## 변경 (petclinic 측 — 외부 프로젝트, 본 repo 와 별개)

| 파일 | 변경 |
|---|---|
| `pom.xml` | `io.graphrag:shared-model`, `io.graphrag:graph-rag-builder` (test scope), `net.ttddyy:datasource-proxy` 추가 |
| `src/test/java/.../graphrag/PetclinicScoutTest.java` (신규) | scout 2 개 — EAGER + 단일 테이블 native query |

## 실행 명령

### 0. 원-셋업
```bash
# graph-rag artifacts 를 Maven Local 에 발행
./gradlew :shared-model:publishToMavenLocal \
          :graph-rag-builder:publishToMavenLocal \
          --no-configuration-cache
```

### 1. scout (petclinic 내부)
```bash
cd ~/github_spring-petclinic/spring-petclinic
mvn -Dtest=PetclinicScoutTest test
```

stdout:
```
[scout] dataSource class = net.ttddyy.dsproxy.support.ProxyDataSource
[scout] captured: 2
  SELECT select o1_0.id,...,p1_0.owner_id,...,t1_0.id,... FROM owners ... JOIN pets ... rows=1
  SELECT select v1_0.pet_id,...,v1_0.visit_date,... FROM visits ... rows=0
[scout-simple] captured: 1
[scout-simple] archive saved
Tests run: 2, Failures: 0, Errors: 0
```

scout 가 작성한 archive (단일 테이블 버전):
```json
[{
  "id": "sql-...",
  "path_id": "get-owner-1-simple",
  "type": "SELECT",
  "raw_sql": "SELECT id, first_name, last_name, address, city, telephone FROM owners WHERE id = ?",
  "bindings": [{"position":0,"value":1,"origin":"COMPUTED"}],
  "source": "JPA_ENTITYMANAGER",
  "affected_tables": ["owners"],
  "read_result_rows": [{
    "ID": 1, "FIRST_NAME": "George", "LAST_NAME": "Franklin",
    "ADDRESS": "110 W. Liberty St.", "CITY": "Madison", "TELEPHONE": "6085551023"
  }]
}]
```
→ **graph-rag-builder 가 petclinic 의 H2 시드(owner id=1) 의 실제 값을 SUT JVM 안에서 자동 캡처.**

### 2. test-generator (사용자 작성 SQL 0)
```bash
test-generator \
  --archive /tmp/gen-e2e/petclinic-scout-simple-archive \
  --endpoint "GET:/api/owners/1" \
  --package "com.example.gen.pcsimple" \
  --out /tmp/gen-e2e/petclinic-auto-simple-out
```

자동 생성된 테스트:
```java
@Test
void path_get_owner_1_simple() throws Exception {
    try (Connection conn = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASS)) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO owners (ID, FIRST_NAME, LAST_NAME, ADDRESS, CITY, TELEPHONE) VALUES (?, ?, ?, ?, ?, ?)")) {
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
        given().contentType(ContentType.JSON).body("{}")
            .when().get("/api/owners/1")
            .then().statusCode(200);
    } finally {
        try (Connection conn = ...) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM owners WHERE ID = ?")) {
                ps.setObject(1, 1);
                ps.executeUpdate();
            }
        }
    }
}
```

### 3. 깨끗한 Postgres + 빈 petclinic SUT 에서 실행
```bash
# DB clean reset (schema 만, data.sql 무시)
docker exec gen-e2e-pg psql -U appuser -d petclinic -c "DROP TABLE ..."
docker exec -i gen-e2e-pg psql -U appuser -d petclinic < schema.sql

# SUT 부팅 (--spring.sql.init.mode=never 로 data.sql 자동 로드 차단)
java -jar spring-petclinic-4.0.0-SNAPSHOT.jar \
    --spring.sql.init.mode=never \
    --spring.jpa.hibernate.ddl-auto=none ...

# 사전 확인: owner id=1 부재
curl http://localhost:8084/api/owners/1   # HTTP 404
docker exec gen-e2e-pg psql -U appuser -d petclinic -c "SELECT count(*) FROM owners"
# count: 0

# generated test 실행
cd /tmp/gen-e2e/petclinic-auto-runner
APP_BASE_URI=http://localhost:8084 JDBC_URL=jdbc:postgresql://localhost:55432/petclinic \
JDBC_USER=appuser JDBC_PASS=apppass mvn test
```

결과:
```
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
        -- in com.example.gen.pcsimple.T_1GetTest
[INFO] BUILD SUCCESS
```

## 100% 자동화 데이터 흐름

```
[scout test JVM]                              [test runner JVM]
ProxyDataSource + CaptureContext                                              
↓                                                                              
em.createNativeQuery("SELECT...owners WHERE id=?")                            
↓ CapturedSqlListener.afterQuery                                              
재실행 (rebuildAsSelectStar) → ResultSet                                       
→ readResultRows = [{ID:1, FIRST_NAME:"George", ...}]                          
↓                                                                              
archive 저장 (JSON 4 파일, 사용자 작성 0 byte)                                 
                ↓                                                              
                test-generator --archive --endpoint                            
                ↓                                                              
                T_1GetTest.java (INSERT + GET + DELETE 자동)                   
                                                              ↓                
                                                              [clean Postgres] 
                                                              owners 0 row     
                                                              mvn test         
                                                              fixture INSERT → 
                                                              GET 200 OK →     
                                                              DELETE → PASS    
```

## 발견된 한계 (운영 통합 전 해결 필요)

### 1. ThreadLocal CaptureContext ↔ Servlet handler thread
- RestTemplate / MockMvc 는 Tomcat handler thread 에서 실행 → `CaptureContext.current()` 가 null → capture 안 됨
- 본 scout 은 OwnerRepository / EntityManager 직접 호출로 우회 (동일 thread)
- **후속**: Servlet filter 가 request 시작 시 CaptureContext 설정 / 종료 시 clear. 또는 TransmittableThreadLocal

### 2. EAGER JOIN multi-table row
- `OwnerRepository.findById(1)` → Hibernate 가 `LEFT JOIN pets / types` 까지 한 wide SELECT
- snapshot row 가 `[ID, ADDRESS, ..., OWNER_ID, BIRTH_DATE, NAME]` (owners + pets + types 컬럼 혼합)
- `affected_tables[0] = "owners"` 사용 → `INSERT INTO owners (ID, ADDRESS, ..., OWNER_ID, BIRTH_DATE, NAME)` → SQL 오류 (owners 에 없는 컬럼)
- **회피**: scout-simple 처럼 native query 로 단일 테이블만 SELECT
- **후속**: snapshot row 의 각 컬럼을 `affected_tables` 의 각 테이블로 분리 → 다중 INSERT 합성. 또는 schema metadata 로 컬럼 → 테이블 매핑

### 3. digit-starting classname
- `/api/owners/1` 의 last segment `1` → 생성 클래스명 `1GetTest` (Java 식별자 시작 숫자 불가)
- 사후 `sed` 로 `T_1GetTest` 변환 필요
- **후속**: TestSynthesizer 의 classname sanitize 로직에 prefix 추가

## 검증 통계 (Option A 전체)

### 단위 테스트
| 모듈 | 신규 추가 | 결과 |
|---|---|---|
| shared-model | 3 (CapturedSql round-trip / legacy ctor / legacy JSON) | ✅ |
| graph-rag-builder | 8 (CapturedSqlListener H2 통합) | ✅ |
| test-generator | 7 신규 + 1 갱신 (FixtureComposer) | ✅ |

### E2E 테스트
| 위치 | 시나리오 | 결과 |
|---|---|---|
| `samples/demo-sut/PhaseSeedDataE2eTest` | seed user → POST /api/orders → SELECT users + INSERT orders → 합성 INSERT 둘 다 포함 | ✅ |
| petclinic `PetclinicScoutTest#scout...AndWriteArchive` | EAGER JOIN SELECT 실 캡처 + archive 저장 | ✅ (capture 성공, 합성 시 multi-table 한계 노출) |
| petclinic `PetclinicScoutTest#scoutSingleTable...` | native query 단일 SELECT → 합성 → 빈 Postgres + 빈 SUT 에서 GET /api/owners/1 PASS | ✅ |

### 신규 코드 line coverage (JaCoCo XML 기반)

| 클래스 | LINE | BRANCH | METHOD |
|---|---|---|---|
| `shared-model/CapturedSql` | **100.0%** (14/14) | n/a | 100.0% |
| `graph-rag-builder/CapturedSqlBuilder` | **95.8%** (23/24) | 85.7% | 100.0% |
| `graph-rag-builder/CapturedSqlListener` | **98.1%** (53/54) | 76.7% | 100.0% |
| `test-generator/FixtureComposer` | **98.6%** (68/69) | 79.6% | 100.0% |

평균 line coverage **98%** — option A 신규 코드 전부 거의 fully covered.

### 회귀
```
$ ./gradlew build
BUILD SUCCESSFUL — 89 actionable tasks
```
기존 Phase 0~5 + 단위/E2E 0 broken.

## Phase 1~7 누적 변경

| 파일 | 변경 종류 | 라인 |
|---|---|---|
| `shared-model/.../CapturedSql.java` | record 확장 + legacy ctor | +35 |
| `shared-model/.../CapturedSqlTest.java` | 3 테스트 추가 | +60 |
| `graph-rag-builder/.../CapturedSqlBuilder.java` | 5-arg overload + detectType public | +20 |
| `graph-rag-builder/.../CapturedSqlListener.java` | trySnapshotRows + rebuildAsSelectStar + readAllRows | +70 |
| `graph-rag-builder/.../CapturedSqlListenerTest.java` (신규) | 8 H2 통합 테스트 | +130 |
| `test-generator/.../FixtureComposer.java` | SELECT → INSERT 합성 + dedup + snapshot cleanup | +80 |
| `test-generator/.../FixtureComposerTest.java` | 7 신규 + 1 갱신 | +90 |
| `samples/demo-sut/.../PhaseSeedDataE2eTest.java` (신규) | demo-sut 전 사이클 E2E | +110 |
| `build.gradle.kts` | maven-publish | +10 |
| `graph-rag-builder/build.gradle.kts` | bootJar classifier 조정 | +6 |
| `docs/12 ~ 18-option-a-*.md` (신규 7개) | Phase 별 가이드 | +2000 |
| (외부) petclinic `pom.xml` + scout test | maven local deps + scout | +180 |

총 graph-rag 코드 ~411 line, 테스트 ~390 line, 문서 ~2000 line. 외부 petclinic 변경은 본 repo 와 무관 (참조만).

## 운영 통합 권장 순서 (Phase 8+ 후보)

1. **Servlet filter 기반 CaptureContext propagation** — REST E2E scout 이 직접 controller 호출 없이 HTTP 호출로 가능해짐
2. **Schema metadata 통합** — JDBC `DatabaseMetaData` 또는 JPA `Metamodel` 로 컬럼 → 테이블 매핑 → JOIN snapshot 의 다중 INSERT 합성
3. **classname sanitize 강화** — TestSynthesizer 에 prefix 정책 (digit-start → `T_`, special char → underscore)
4. **publishToMavenLocal 자동화** — graph-rag 의 `./gradlew bootstrap` task 등으로 단일 명령 packaging
5. **end-to-end CLI** — `graph-rag scout <sut.jar> --endpoint POST:/api/x --capture-output /tmp/archive` 같은 통합 도구

## 참조

- [`docs/12-option-a-row-snapshot-design.md`](12-option-a-row-snapshot-design.md) — 설계
- [`docs/13-option-a-model.md`](13-option-a-model.md) — 모델
- [`docs/14-option-a-capture.md`](14-option-a-capture.md) — capture
- [`docs/15-option-a-composer.md`](15-option-a-composer.md) — composer
- [`docs/16-option-a-e2e.md`](16-option-a-e2e.md) — demo-sut E2E
- [`docs/17-option-a-petclinic.md`](17-option-a-petclinic.md) — petclinic hand-crafted archive 검증
- 본 문서 — petclinic 진짜 in-process auto-capture E2E

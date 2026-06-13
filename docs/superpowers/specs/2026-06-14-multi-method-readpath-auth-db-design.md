# 다중 HTTP method · GET read-path 시드/합성 · JWT 인증 · DB 비종속 설계

- 작성일: 2026-06-14
- 대상: `graph-rag-builder`, `test-generator`, `testlib`, `shared-model`, `samples/order-service`, `e2e`
- 선행 맥락: Phase 0–3(POST 전용, JPA/MyBatis, WireMock/OTEL, STOMP) 완료. 본 설계는 그 위에 얹는 4개 기능.
- 동기: 현행 도구는 `@PostMapping`+`@RequestBody` 엔드포인트만, 인증 없는 SUT만, Postgres 고정으로만 동작한다. 실제 SUT(예: `baekchangjoon/spring-petclinic`)는 조회(GET) 엔드포인트가 다수이고 JWT 인증이 필수이며 DB가 SUT마다 다르다.

---

## 1. 목표와 범위

| 항목 | 내용 |
|---|---|
| 목표 | (1) GET/PUT/DELETE/PATCH 인덱싱, (2) GET 조회 경로의 시드 데이터 생성+결정적 합성, (3) JWT 인증을 탐색·생성 양쪽에 주입, (4) DB를 SUT의 docker-compose에서 결정(Postgres 고정 제거) |
| 검증 단계 | **1단계**: in-repo `samples/order-service`에 네 기능을 모두 걸어 e2e GREEN. **2단계**: 동일 능력을 `spring-petclinic`(외부 SUT)에 적용 |
| 작업 범위 | 이 폴더(`graph-rag-fable`) 내. 2단계의 petclinic clone/build/compose 구성은 본 spec의 후속(별도 plan) |
| 진행 규약 | 각 태스크 TDD(테스트 먼저 → 최소 구현 → 리팩터), 완료 시 commit + `progress/` 기록, 주요 결정은 `docs/decisions/` |

---

## 2. 관통 원칙 — "입력"의 일반화

현행 파이프라인은 POST body 전용이다: `EndpointInvoker.invoke(JsonNode body)`가 항상 body를 POST하고(`graph-rag-builder/.../run/EndpointExplorationRunner.java:170-196`), `SampleInputSynthesizer`가 `BodyShape`에서 body를 합성한다.

**핵심 결정:** 탐색 엔진이 다루는 "입력"을 평탄한 `JsonNode {paramName: value}` 로 통일한다. invoker가 `Endpoint.params`의 `ParamKind`(BODY/PATH/QUERY)를 보고 path 치환·query string·body로 분배한다.

- 효과: `InputMutator`·`HeuristicExplorer`·`CoverageGuidedFuzzer`·dedup 로직을 **변경 없이** GET/PUT/DELETE에 재사용.
- 하위호환: POST는 `{bodyField:value}` 그대로라 기존 동작 불변.
- `EndpointInvoker` SPI 시그니처(`invoke(JsonNode)`)는 유지하고 구현(`httpInvoker`)만 method/param-aware로 바꾼다.

---

## 3. 기능 1 — 다중 HTTP method 인덱싱

**대상:** `graph-rag-builder/.../index/EndpointIndexer.java`

| 변경 | 내용 |
|---|---|
| 매핑 어노테이션 | `@GetMapping/@PutMapping/@DeleteMapping/@PatchMapping` 상수 추가 + `@RequestMapping(method=RequestMethod.X)`. `EndpointIndexer.java:52`의 단일 `postMapping` 체크를 "메서드→HTTP method" 매핑 순회로 일반화 |
| 파라미터 종류 | `extractParams`(`:72`)에 `@PathVariable`→`ParamKind.PATH`, `@RequestParam`→`ParamKind.QUERY` 추가(현재 `@RequestBody`만). `ParamKind`는 이미 BODY/PATH/QUERY/HEADER 정의됨 |
| path 변수 보존 | `{ownerId}` 토큰을 path 문자열에 보존(현재도 리터럴로 들어옴) |
| `authRequired` | `:64`의 하드코딩 `false` 제거. **휴리스틱 판정**: 인증 구성이 주어지면 `loginPath`(및 명시한 public 경로)를 제외한 전 경로 = `true`. 정확한 Spring Security 정적 파싱은 보류(아래 보류 표) |

**단위 테스트:** `@GetMapping`/`@PutMapping`/`@DeleteMapping` + `@PathVariable`/`@RequestParam`를 가진 fixture 컨트롤러 → 기대 `Endpoint`(method, path, params kind, authRequired) 검증.

---

## 4. 기능 2 — GET read-path 시드 + 합성 (시드 = builder의 역할)

조회 엔드포인트는 응답할 데이터가 DB에 존재해야 테스트가 성립한다. **builder가 그 시드를 만들어 graph 사실로 기록**하고, generator가 이를 재현한다.

### 4.1 신규: `ReadInputSynthesizer` (`SampleInputSynthesizer`와 대칭)

`graph-rag-builder/.../run/ReadInputSynthesizer.java`. write 경로의 `SampleInputSynthesizer`와 짝.

흐름:
1. **타깃 테이블 결정**: path/스키마 매칭. `/api/owners/{ownerId}` → `owners`, path var `ownerId`→PK. `/api/orders?userId=` → `orders`, query `userId`→대응 컬럼. 경로 세그먼트↔테이블명 단/복수 매칭. 추론 실패 시 `--read-target <endpointId>=<table>` 수동 override.
2. **시드 row 합성**: 타깃 테이블의 PK = 선택한 path/query 값, WHERE 매칭 컬럼 = 해당 param 값, 나머지 NOT NULL = 기본값(`SampleInputSynthesizer.defaultFor` 재사용). FK 부모는 기존 FK 시딩 재사용.
3. **입력 합성**: path/query param 값의 평탄 `JsonNode {paramName:value}` 반환 → orchestrator로 전달.

### 4.2 탐색 흐름 분기 (`EndpointExplorationRunner.run`)

- write 엔드포인트(POST/PUT/PATCH, body 있음): 기존 `SampleInputSynthesizer`.
- read 엔드포인트(GET, body 없음): `ReadInputSynthesizer`.
- 시드 INSERT: 기존 `Seeds.insert`(멱등) 사용하되 **dialect 분기**(§6.3) 적용. 삽입한 시드를 `RequiredSeed` 사실로 기록.
- 캡처: read 경로는 SELECT SQL이 캡처되지만 **fixture는 SELECT가 아니라 `RequiredSeed`에서** 생성한다. 응답은 시드값을 알고 있으므로 결정적 → 기존 `fixture.assertions()` 기계로 필드 단언.

### 4.3 invoker 메서드 분배 (`httpInvoker`, `EndpointExplorationRunner.java:170`)

- `endpoint.httpMethod()`에 따라 `.GET()/.PUT()/.DELETE()/.PATCH()/.POST()`.
- URL = base + `{pathVar}` 치환 + query string(input의 PATH/QUERY param). body는 본문 있는 method만 직렬화.

### 4.4 데이터 모델 변경 (`shared-model`)

```java
// 신규
record RequiredSeed(String id, String pathId, String table,
                    List<String> columns, List<String> values)

// GraphAsset:  + List<RequiredSeed> seeds
// ExploredPath: + List<String> requiredSeedIds
//   (생성자 시그니처 변경 → JsonRoundTripTest 갱신 + builder/generator 컴파일 수정. Phase 1 선례 동일)
```

### 4.5 generator (`test-generator`)

- `FixtureComposer`: read path면 `inserts`를 `RequiredSeed`에서 생성(현재는 캡처 INSERT SQL에서). 단언은 `sampleResponse`에서(기존 경로 재사용).
- `Generator.generateSingle`(`:158-207`): 요청 path를 `endpoint.path()` + `sampleInput`의 path/query 값으로 **리터럴 URL 선계산**(예 `"/api/owners/1"`). RestAssured pathParam 불필요.
- `test-class.mustache`: read method는 `.body(...)` 생략, `.{{httpMethodLower}}("{{requestPath}}")` 사용. (현재는 항상 body+POST 가정)

**단위 테스트:** `ReadInputSynthesizer`(스키마+GET param→시드 row+입력), Generator 골든(read→seed-insert+필드 단언, GET 리터럴 URL).

---

## 5. 기능 3 — JWT 인증 (petclinic `ApiBlackBoxTestSupport` 패턴 = 구현)

petclinic의 블랙박스 인증을 그대로 옮긴다: `/api/auth/login`으로 JWT 발급 → **1회만 발급 후 캐싱**(volatile + double-checked locking) → `Authorization: Bearer <token>` 헤더 부착.

### 5.1 구성 (4값 + 헤더 기본값)

`--auth-login-path`(예 `/api/auth/login`), `--auth-user`(`admin`), `--auth-pass`(`password`), `--auth-token-field`(응답 JSON 토큰 경로, 기본 `token`). 헤더·scheme 기본 `Authorization`/`Bearer`. builder는 CLI/request JSON, 생성 테스트는 `Env`로 주입.

### 5.2 builder 탐색 측

- 신규 `AuthTokenProvider` + `AuthConfig`(record): 탐색 시작 전 `loginPath`에 `{user,pass}` POST 1회 → 응답 JSON `tokenField` 추출 → 빌드 런 전체에 캐시(petclinic `authToken()` 직렬화와 동형).
- `httpInvoker`: `endpoint.authRequired`면 `header("Authorization","Bearer "+token)` 주입. **이 한 줄이 401 장벽을 풀어 SQL/분기/응답 캡처를 복구한다.**
- `BuilderCli`/`BuildConfig`/`AnalysisEnvironment`에 `AuthConfig` 배선.

### 5.3 generator + testlib 측

- 신규 `RealAuthAdapter`(name `"real"`) + `META-INF/services/io.graphrag.testlib.spi.AuthAdapter` 등록. `AuthClient.login()`이 loginPath POST→token 추출, **volatile + double-checked locking 캐싱**. Env: `AUTH_LOGIN_PATH/AUTH_USER/AUTH_PASS/AUTH_TOKEN_FIELD`. (현행 `NoopAuthAdapter`는 `"noop-token"` 반환만 — 유지하되 기본은 noop)
- `RestAssuredHelper`: `authenticated()` 추가 = `given()` + `Authorization: <scheme> <token>` (토큰은 `TestScope.auth().login(...)`에서, 캐시 공유). petclinic `authenticated()` 대응.
- `test-class.mustache`: `{{#authRequired}}scope.rest().authenticated(){{/authRequired}}{{^authRequired}}scope.rest().given(){{/authRequired}}`.
- `Generator.generateSingle`: `endpoint.authRequired` → scope 키 추가(현재 버려지는 `authMode`/`authRequired`를 여기서 처음 실사용).
- login 엔드포인트 자체(`authRequired=false`)는 평소 POST 캡처 → 정상/오류(400) 케이스 그대로 생성.

**단위 테스트:** `AuthTokenProvider`/`RealAuthAdapter`(로컬 `HttpServer` 스텁이 token 반환), Generator 골든(authRequired→`authenticated()`).

---

## 6. 기능 4 — DB는 SUT의 docker-compose에서 결정 (Postgres 고정 제거)

**전제:** 모든 SUT는 docker-compose를 가지며 거기에 어떤 DB를 쓰는지 적혀 있다. **접근 (A)**: compose를 읽어 DB 종류를 알아내고, builder가 같은 종류의 격리 DB를 Testcontainers로 띄워 SUT jar에 JDBC URL을 주입한다. 현행 "SUT jar 자식 프로세스 + 격리 DB" 모델(JaCoCo·OTEL agent 부착, seed/reset 제어)을 유지하며 DB만 비종속화한다.

### 6.1 신규: `ComposeInspector` + `DbConfig`

`graph-rag-builder/.../env/ComposeInspector.java`. `detectDb(composePath)` → compose YAML 파싱 → DB 서비스(image가 `postgres|mysql|mariadb` 매칭) 탐지 → env(`POSTGRES_DB/USER/PASSWORD` 또는 `MYSQL_*`)에서 `DbConfig{type, image, dbName, user, pass}` 추출.

### 6.2 변경: `AnalysisEnvironment`

- `PostgreSQLContainer` 필드(`AnalysisEnvironment.java:25,34`) → 제네릭 `JdbcDatabaseContainer<?>`. 신규 `JdbcContainers.create(DbConfig)`가 type에 맞는 컨테이너 선택(postgres→`PostgreSQLContainer`, mysql→`MySQLContainer`, mariadb→`MariaDBContainer`), compose와 동일 image 사용.
- 생성자 `AnalysisEnvironment(String postgresImage)` → `AnalysisEnvironment(DbConfig)`. `jdbcUrl()/openConnection()`(`:65-71`)은 컨테이너에서. SUT jar 주입(`:61`)은 그대로 이 URL/user/pass 사용 → SUT가 compose가 선언한 것과 같은 DB로 부팅.
- `BuilderCli`: `--postgres-image` → `--sut-compose <path>`(DB 탐지, 필수) + `--db-image` override(선택). `BuildConfig.postgresImage` → `DbConfig`.

### 6.3 Dialect 영향

- `Seeds.insert`의 `ON CONFLICT DO NOTHING`은 **Postgres 전용**(`Seeds.java`). 신규 `SqlDialect` 헬퍼로 멱등 INSERT를 type별 분기: postgres `... ON CONFLICT DO NOTHING` / mysql·mariadb `INSERT IGNORE ...`.
- `SchemaExtractor`(JDBC `DatabaseMetaData`)는 대체로 portable. type별 식별자 대소문자 차이만 점검.
- `SqlLogParser`는 로그 레벨 파싱이라 dialect 영향 작음(점검만).

### 6.4 test-generator / e2e 측

- 생성 테스트는 이미 `Env`의 `JDBC_URL`로 접속(`TestScope.create`, `JdbcHelper`) → DB 비종속.
- 드라이버: `JdbcAdapter`/testlib 런타임에 매칭 JDBC 드라이버 필요. `JDBC_URL` scheme로 드라이버 선택. mysql/mariadb 드라이버를 testlib 런타임 의존에 추가.
- e2e: **SUT의 compose가 단일 출처.** builder도 generated test도 그 compose의 DB를 가리킨다. 별도 `postgres:15` 강제 제거.

**단위 테스트:** `ComposeInspector.detectDb`(postgres/mysql compose fixture → DbConfig), `SqlDialect` 멱등 INSERT(type별 문자열).

---

## 7. 통합 데이터 흐름

```
[compose] ComposeInspector.detectDb ──► DbConfig{type,image,db,user,pass}
                                            │
[env]   AnalysisEnvironment(DbConfig) ──► JdbcDatabaseContainer + SUT jar(JDBC 주입)
                                            │
[index] EndpointIndexer ──► Endpoint{method(GET/POST/...), path, params(BODY|PATH|QUERY), authRequired}
                                            │
[auth]  AuthTokenProvider.login() once ─────┼──► token(cache)
                                            ▼
[explore] (write) SampleInputSynthesizer ─┐
          (read)  ReadInputSynthesizer ──► seeds + 평탄 input ─► Seeds.insert(SqlDialect) + RequiredSeed
                                            │
          httpInvoker(method-aware, +Bearer) ─► orchestrator (mutator/fuzzer 불변)
                                            ▼
          captureSql / response ─► ExploredPath{sampleInput, status, response, requiredSeedIds, ...}
                                            ▼
[graph]  GraphAsset{endpoints, paths, sql, seeds, ...}
                                            ▼
[generate] FixtureComposer(read: inserts←RequiredSeed) + Generator(authRequired→authenticated())
           ─► RestAssured test (GET 리터럴 URL, Bearer 헤더, 시드 INSERT, 결정적 단언)
```

---

## 8. 샘플 SUT 선검증 (`samples/order-service`, 1단계)

네 기능을 모두 거는 최소 추가:
- `POST /api/auth/login`(JWT, 하드코딩 user) + JwtUtil + Security 설정(GET 보호).
- read 엔드포인트: `GET /api/orders/{id}`(path var) + `GET /api/orders?userId=`(query) — 보호됨.
- e2e: `docker-compose.yml`/`run-e2e.sh`에 `AUTH_ADAPTER=real` + `AUTH_*` env, builder는 `--sut-compose` + `--auth-*`. 기대 경로 수는 read/auth 케이스만큼 증가(현재 16 → +N).

`samples/order-service`의 compose DB는 현행대로 Postgres여도 무방하나, `ComposeInspector` 경로를 실제로 타도록 `--sut-compose`로 주입해 검증한다.

---

## 9. 아키텍처 대비 의식적 보류 (decision 문서로 각각 기록)

| 항목 | 정석 | 본 설계 | 시점 |
|---|---|---|---|
| `authRequired` 판정 | Spring Security 설정 정적 파싱 | 휴리스틱(비-login=보호) + public 경로 옵션 | 후속 |
| read 타깃 테이블 추론 | 라우팅↔엔티티 정밀 매핑 | path/스키마 휴리스틱 + `--read-target` override | 후속 |
| petclinic 적용(2단계) | clone/build/compose 통합 | 본 spec 후속 plan | 2단계 |
| DB type 범위 | 임의 JDBC | postgres/mysql/mariadb (Testcontainers JdbcDatabaseContainer) | 필요 시 확장 |

---

## 10. 완료 기준

- [ ] `EndpointIndexer`가 GET/PUT/DELETE/PATCH + PathVariable/RequestParam을 인덱싱(단위 GREEN)
- [ ] `ReadInputSynthesizer`가 스키마+param에서 시드 row+입력 합성, `RequiredSeed`가 graph에 기록(단위 GREEN)
- [ ] `httpInvoker`가 method/param-aware로 GET/PUT/DELETE 호출(통합)
- [ ] JWT: builder 탐색이 Bearer 주입으로 보호 엔드포인트의 SQL/분기 캡처 성공, 생성 테스트가 `authenticated()`로 통과
- [ ] `ComposeInspector`+`AnalysisEnvironment`가 compose의 DB로 동작(Postgres 하드코딩 제거), `SqlDialect` 멱등 INSERT
- [ ] 1단계: `samples/order-service`에 네 기능 모두 걸어 e2e 풀사이클 GREEN
- [ ] `docs/decisions/` + `progress/` 기록, README 갱신

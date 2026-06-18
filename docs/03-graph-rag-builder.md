# 03 — 도구 1: Graph RAG Builder

소스 코드에서 사실을 추출해 그래프 RAG 자산(`graph.json`)을 만들고, 증분 갱신한다. CLI 전용 — 조회 서버는 없다.

LLM은 도구 안에 없다. 외부 오케스트레이터가 LLM이거나 사람이거나 무관하게, 도구 1은 결정적이고 재현 가능하다.

## 5개 레이어

```
┌───────────────────────────────────────────────────────────┐
│ Layer 5: 영속 (graph-store)                                 │
│  - Graph store: JSON 파일(graph.json) + 파티션 샤드          │
│  - Vector store (pgvector 등) — 보류                        │
├───────────────────────────────────────────────────────────┤
│ Layer 4: Sink Capture                                      │
│  - Hibernate SQL logger                                    │
│  - MyBatis Interceptor                                     │
│  - WireMock recorder (분석용 임베디드)                       │
│  - Netty LoggingHandler / 자체 javaagent                    │
├───────────────────────────────────────────────────────────┤
│ Layer 3: Path Exploration (분기 탐색)                       │
│  - HeuristicExplorer + CoverageGuidedFuzzer (self-fuzzer)   │
│    → ExplorationOrchestrator가 예산 분할·순차 구동           │
│  - 입력 발견: InputOracle (StaticLiteralOracle + ConcolicOracle) │
│  - JaCoCo arm-level 커버리지 피드백                          │
│  - Execution harness: Spring TestContext + Testcontainers   │
├───────────────────────────────────────────────────────────┤
│ Layer 2: Framework Introspection                            │
│  - Spring TestContext 부팅 → 실제 빈 그래프                   │
│  - Hibernate SchemaExport → 물리 스키마                      │
│  - MyBatis Mapper 인벤토리                                    │
│  - Flyway/Liquibase 마이그레이션 파싱                          │
│  - Spring Security 설정 분석                                  │
├───────────────────────────────────────────────────────────┤
│ Layer 1: Structural Indexing                                │
│  - Spoon AST (EndpointIndexer, BodyShapeExtractor,          │
│    ConstraintExtractor, ValidationConstraintExtractor 등)    │
└───────────────────────────────────────────────────────────┘
```

자세한 입력 생성 흐름은 [docs/23](23-input-generation-flow.md),
오라클·탐색 백엔드는 [docs/24](24-exploration-backends-and-input-oracle.md) 참조.

## 핵심 도구

| 레이어 | 도구 | 역할 |
|---|---|---|
| L1 | Spoon | AST 기반 구조 인덱싱: 엔드포인트(`EndpointIndexer` — `@RestController`(JSON/`@RequestBody`) + `@Controller`(폼/커맨드 객체, `ParamKind.FORM`) 모두), 바디 구조(`BodyShapeExtractor`), 제약(`ConstraintExtractor`: `extractComparisons` 비교식 / `extractConjunctions` 메서드 내 `&&` 다필드 가드 / `extractEnumColumns` 가드 유래 enum 컬럼값 / `extractStringEqualities` 문자열 동치 / `extractStateGuards` 저장-행 상태 가드(TEMPORAL · ENUM `!=`(negated)/`==`(positive) — 상태머신 다중 전이 다-arm 변종 시드)), enum 상수(`EnumConstantExtractor`: FQN→상수), Bean Validation(`ValidationConstraintExtractor`) |
| L2 | Spring Boot TestContext | 실제 빈 와이어링 introspection |
| L2 | Hibernate SchemaExport | JPA Entity → DDL |
| L2 | Flyway/Liquibase parser | 마이그레이션 → DDL truth |
| L2 | MyBatis Configuration | mapper 인벤토리 |
| L3 | 자체 fuzzer (`HeuristicExplorer` + `CoverageGuidedFuzzer`) | coverage-guided path 탐색, `ExplorationOrchestrator`가 예산 분할·순차 구동 |
| L3 | InputOracle (`StaticLiteralOracle` + `ConcolicOracle`) | 분기를 여는 입력 후보 발견. 전자는 Spoon 리터럴, 후자는 ASM 바이트코드 심볼릭 스캔 + Z3(`tools.aqua:z3-turnkey`) |
| L3 | JaCoCo | arm-level branch coverage 측정 (누적 exec data + per-request probe 지문) |
| L3 | Testcontainers | 실제 DBMS (운영과 동일 버전) |
| L4 | OTEL DB span 캡처 (기본) | `SqlCaptureBackend`/`OtelSpanCapture` — SUT의 OTEL agent가 내보내는 DB span(SQL+bind)을 요청별 `traceparent`로 trace-id 귀속 캡처. JDBC 레벨이라 Hibernate·MyBatis·raw JDBC 모두 포함. 동시 요청도 격리 ([docs/06](06-test-environment.md) "trace 모드") |
| L4 | Hibernate SQL logger / MyBatis Interceptor (폴백) | `--trace-mode none` 또는 OTEL이 빈 trace일 때 로그 파싱 캡처(`LogParserCapture`). MyBatis 동적 SQL 실제 형태 포함. `--trace-mode sleuth` 는 같은 로그 파싱에 B3 trace-id 상관을 더해 비동기·서비스간 SQL까지 회수 |
| L4 | WireMock | 분석용 임베디드 HTTP mock, recorder 활성 |
| L4 | Netty LoggingHandler | 바이트 hex dump |
| L4 | 자체 javaagent | InputStream/OutputStream 후킹 (raw socket) |
| L5 | Graph store | `GraphStore` 인터페이스 + JSON 파일(`JsonFileGraphStore` → `graph.json`) + 파티션 샤드(`PartitionedGraphStore`, Phase 6.1). 조회 서버 없음(빌더는 CLI 전용). Neo4j는 보류 (`decisions/graph-store-phase6.md`) |
| L5 | Vector store | 보류 — 임베딩 질의 등장 전까지 불필요 (`decisions/graph-store-phase1.md`) |

## 캡처되는 핵심 사실

스키마는 `SCHEMAS.md`의 `0. 공통 데이터 모델` 참조. 요지:

- **Endpoint**: HTTP 메소드/경로, handler 메소드, 인증 요구
- **ExploredPath**: 탐색된 코드 경로. sample input, exit status, 분기 시퀀스, (옵션) path constraint
- **CapturedSQL**: 실제 발행된 SQL. 바인딩의 origin (API param/literal/computed) 보존
- **CapturedHttpCall**: 외부 HTTP. URL + body + 응답 + SUT가 실제로 읽은 응답 필드
- **CapturedSocketIO**: 외부 socket. 보낸/받은 바이트, (옵션) 디코드 결과
- **Table/Column**: 운영 DBMS 기준 물리 스키마 + FK + UNIQUE
- **Branch**: 분기점 + 조건 + 어느 path에서 실행됐는지
- **PropagationInfo**: 트레이싱 헤더 전파 능력
- **ValidationConstraint**: `@RequestBody` DTO의 Bean Validation 제약 (`ValidationConstraintExtractor` — NotNull/Size/Min/Max/Pattern 등)
- **컬렉션 바디 (collection BodyShape)**: `@RequestBody`/Kafka `@KafkaListener`/WS 페이로드가 컬렉션(`List`/`Set`/`Collection`/`Iterable<E>`·배열 `E[]`)이면 `BodyShapeExtractor.extractFromType`가 원소 타입 `E`를 환원해 `BodyShape.collection()==true`로 인덱싱한다. 원소가 DTO면 `fields`에 원소 DTO 필드를, scalar(String/숫자/불리언/`java.time.*`/**enum**)면 빈 `fields`를 둔다. `bodyTypeKey`가 원소 타입까지 인코딩하므로 서로 다른 `List<DTO>`가 `java.util.List` 키로 충돌하지 않는다. 합성(`SampleInputSynthesizer`)은 **유효 원소 1개짜리 JSON 배열**을 만들고 **happy-path만** 1회 탐색한다(컬렉션 바디는 변이/음수-검증/by-id 경로를 타지 않음).
- **Comparison / StringEquality**: 전 계층(컨트롤러/서비스/도메인) AST에서 추출한 숫자 비교식·문자열 동치 제약 (`ConstraintExtractor.extractComparisons` / `extractStringEqualities`)
- **입력 후보**: 오라클이 도출한 비-리터럴 입력 후보값 (`InputOracle` — 분기 경계 ±1 등)

## 갱신 전략

| 변경 유형 | 재인덱싱 범위 |
|---|---|
| 메소드 본문 변경 | 해당 파일만 |
| 메소드 시그니처 변경 | 해당 파일 + 직접 호출자 |
| `@Query` / MyBatis XML 변경 | 해당 mapper + 호출자 + 영향 Table |
| Spring config 변경 | 해당 프로젝트 wiring 재검증 |
| `pom.xml` / `build.gradle` 의존성 변경 | 해당 프로젝트 풀빌드 |
| 신규 endpoint | 해당 파일 + endpoint 인덱스 |

**500만 라인 레거시**: PR 단위 증분 + 야간 풀 재빌드로 표류 보정.
증분 빌드 PoC는 구현됨 (Phase 6.2): `--incremental-base <prev-graph-dir>
--changed-files <list-file>` — 더티 파티션(패키지)만 재탐색, 클린 파티션의
탐색 사실은 이전 그래프에서 이월. 정적 인덱싱은 항상 풀로 수행.

- **엔드포인트 선택 (`--endpoint <spec[,spec]>`)**: 선택한 단위만 탐색한다.
  스펙은 단위 id(`post-api-orders`) 또는 `METHOD /path`(`POST /api/orders`)이며
  HTTP 엔드포인트·WS 엔드포인트·Kafka consumer 에 적용된다(WS/Kafka 는 id 만).
  `--incremental-base` 동반 시 나머지 단위의 탐색 사실(path/sql/httpCall/wsExchange/
  kafkaExchange/seed)은 base 에서 그대로 이월하고, base 없으면 선택 단위만 담은
  **부분 그래프**가 된다. 어느 경우든 정적 엔드포인트 목록(`endpoints()` 등)은
  필터링하지 않고 풀로 유지한다. `--changed-files` 와 함께 주면 `--endpoint` 가 우선.

## 분석 환경

분석 시점에 도구가 직접 띄우는 환경:

- DB: Testcontainers (운영 동일 DBMS/버전). 인메모리 DB는 보조.
- HTTP downstream: 임베디드 WireMock + recorder
- Socket downstream: 임베디드 자체 Netty mock + byte recorder
- Spring TestContext로 SUT 부팅 (실제 빈 와이어링)
- JaCoCo 에이전트로 arm-level coverage 측정 (엔드포인트별 누적 exec data를 probe OR 병합 + 요청마다 probe 지문으로 distinct path 식별 — `BranchCoverageAnalyzer`, `CoverageFingerprint`, `EndpointExplorationRunner.cumulativeCoverage`)

이 분석 환경은 **테스트가 실행될 환경과는 별개**. 혼동하지 않도록 분리.

### Attach 모드 (사용자 compose로 분석)

위 기본 환경 대신, 사용자가 가진 `docker-compose.yml` 로 SUT를 띄워 분석할 수도 있다. 빌더가
override compose를 생성해 app 서비스에 SQL 로깅·jacoco/otel 에이전트·포트 publish를 주입하고
스택의 up/down을 소유한다. 플래그·생성 override·v1 한계는 [docs/26](26-attach-mode.md) 참조.

## 외부에서 가져오지 않는 데이터

- 운영/스테이징 실 트래픽 로그
- 동적으로 수집된 메트릭
- 외부 트레이스/APM 데이터

모든 사실은 **도구가 직접 빌드/실행**해서 얻는다.

## 한계 (정직하게)

- **리플렉션, Class.forName**: 정적으로 못 잡음. 일부 누락 가능.
- **`@Conditional`, profile 기반 빈**: TestContext 부팅에서 일부만 해소
- **MyBatis `<foreach>` 실 카디널리티**: 1/N 가정으로 합성
- **컬렉션 바디 — happy-only(원소 1개)**: 컬렉션 `@RequestBody`/Kafka/WS 페이로드는 유효 원소 1개 배열로 happy-path만 탐색한다. 다음은 이번 범위에서 의도적으로 제외(deferred — 확장 진입점은 [list-dto-body-shape 설계](superpowers/specs/2026-06-18-list-dto-body-shape-design.md)의 후속 작업 표 참조): 원소별 음수-검증 arm(`@Valid List<@Valid DTO>` 위반), 빈 배열 `[]` arm, 다중 원소 배열, 컬렉션 바디 필드 변이/coverage-guided fuzzing, 컬렉션 바디 + PATH param 조합, 중첩 컬렉션 `List<List<..>>`/`Map` 바디. 인자 없는 raw `List`는 원소 타입 불명이라 인덱싱하지 않는다(현행 유지).
- **외부 시스템 응답 enum/range**: 임베디드 mock의 minimal valid 응답에서 출발
- **비결정적 분기 (시간/Random)**: 분석 시점 `Clock.fixed`, seeded Random 사용
- **민감 정보**: 캡처 시 패턴 기반 마스킹 필수
- **`@Controller` 폼 — 클래스-레벨 path 변수**: `@RequestMapping("/owners/{ownerId}")`의 `{ownerId}`가 핸들러 파라미터가 아니라 `@ModelAttribute` 헬퍼 메서드(`findOwner(@PathVariable ownerId)`)에서만 해석되는 경우(petclinic 패턴), 인덱서는 **같은 컨트롤러의 모든 메서드에서 `@PathVariable` 타입 신호를 역추출**해 그 path 변수를 PATH 파라미터로 등록한다(`collectPathVarTypes`+`extractPlaceholders`). 등록되면 `ReadInputSynthesizer`가 해당 리소스(+FK 부모)를 시드해 `@ModelAttribute` 헬퍼가 성공하고 폼 핸들러에 진입한다. path 변수 이름은 `@PathVariable` value/name으로 정규화(`pathVarName`)해 path 템플릿 `{x}`와 일치시킨다(치환 정확). **단일** 추가-PATH(예: petclinic `/owners/{ownerId}/pets/new`, order-service `UserOrderWebController`)는 양 arm까지 완전 탐색된다. **다중** 추가-PATH(`/owners/{ownerId}/pets/{petId}/edit`)는 `ReadInputSynthesizer.mapParamToColumn`이 PATH를 일괄 target PK에 매핑하는 한계로 정밀 시드가 부분적이며(별도 후속 예정), 타입 신호 없는 placeholder는 센티널("0")로 graceful fallback(`buildPathAndQuery`). `@Controller` 폼은 현재 **커버리지 전용**(테스트 생성 미지원 — `Generator`가 `ParamKind.FORM` 엔드포인트를 스킵)이다.

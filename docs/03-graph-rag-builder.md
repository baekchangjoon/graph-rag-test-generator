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
| L1 | Spoon | AST 기반 구조 인덱싱: 엔드포인트(`EndpointIndexer`), 바디 구조(`BodyShapeExtractor`), 제약(`ConstraintExtractor`: `extractComparisons` 비교식 / `extractConjunctions` 메서드 내 `&&` 다필드 가드 / `extractEnumColumns` 가드 유래 enum 컬럼값 / `extractStringEqualities` 문자열 동치), enum 상수(`EnumConstantExtractor`: FQN→상수), Bean Validation(`ValidationConstraintExtractor`) |
| L2 | Spring Boot TestContext | 실제 빈 와이어링 introspection |
| L2 | Hibernate SchemaExport | JPA Entity → DDL |
| L2 | Flyway/Liquibase parser | 마이그레이션 → DDL truth |
| L2 | MyBatis Configuration | mapper 인벤토리 |
| L3 | 자체 fuzzer (`HeuristicExplorer` + `CoverageGuidedFuzzer`) | coverage-guided path 탐색, `ExplorationOrchestrator`가 예산 분할·순차 구동 |
| L3 | InputOracle (`StaticLiteralOracle` + `ConcolicOracle`) | 분기를 여는 입력 후보 발견. 전자는 Spoon 리터럴, 후자는 ASM 바이트코드 심볼릭 스캔 + Z3(`tools.aqua:z3-turnkey`) |
| L3 | JaCoCo | arm-level branch coverage 측정 (누적 exec data + per-request probe 지문) |
| L3 | Testcontainers | 실제 DBMS (운영과 동일 버전) |
| L4 | Hibernate SQL logger | JPA 발행 SQL 캡처 |
| L4 | MyBatis Interceptor | MyBatis 발행 SQL 캡처 (동적 SQL 포함 실제 형태) |
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

## 분석 환경

분석 시점에 도구가 직접 띄우는 환경:

- DB: Testcontainers (운영 동일 DBMS/버전). 인메모리 DB는 보조.
- HTTP downstream: 임베디드 WireMock + recorder
- Socket downstream: 임베디드 자체 Netty mock + byte recorder
- Spring TestContext로 SUT 부팅 (실제 빈 와이어링)
- JaCoCo 에이전트로 arm-level coverage 측정 (엔드포인트별 누적 exec data를 probe OR 병합 + 요청마다 probe 지문으로 distinct path 식별 — `BranchCoverageAnalyzer`, `CoverageFingerprint`, `EndpointExplorationRunner.cumulativeCoverage`)

이 분석 환경은 **테스트가 실행될 환경과는 별개**. 혼동하지 않도록 분리.

## 외부에서 가져오지 않는 데이터

- 운영/스테이징 실 트래픽 로그
- 동적으로 수집된 메트릭
- 외부 트레이스/APM 데이터

모든 사실은 **도구가 직접 빌드/실행**해서 얻는다.

## 한계 (정직하게)

- **리플렉션, Class.forName**: 정적으로 못 잡음. 일부 누락 가능.
- **`@Conditional`, profile 기반 빈**: TestContext 부팅에서 일부만 해소
- **MyBatis `<foreach>` 실 카디널리티**: 1/N 가정으로 합성
- **외부 시스템 응답 enum/range**: 임베디드 mock의 minimal valid 응답에서 출발
- **비결정적 분기 (시간/Random)**: 분석 시점 `Clock.fixed`, seeded Random 사용
- **민감 정보**: 캡처 시 패턴 기반 마스킹 필수

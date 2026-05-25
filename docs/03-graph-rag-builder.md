# 03 — 도구 1: Graph RAG Builder

소스 코드에서 사실을 추출해 그래프 RAG 자산을 만들고, 증분 갱신하며, 조회 API를 제공한다.

LLM은 도구 안에 없다. 외부 오케스트레이터가 LLM이거나 사람이거나 무관하게, 도구 1은 결정적이고 재현 가능하다.

## 5개 레이어

```
┌───────────────────────────────────────────────────────────┐
│ Layer 5: 영속 + 조회 API                                   │
│  - Graph store (Neo4j 등)                                  │
│  - Vector store (pgvector 등)                              │
│  - REST/gRPC 조회 인터페이스 (SCHEMAS.md 참조)              │
├───────────────────────────────────────────────────────────┤
│ Layer 4: Sink Capture                                      │
│  - Hibernate SQL logger                                    │
│  - MyBatis Interceptor                                     │
│  - WireMock recorder (분석용 임베디드)                       │
│  - Netty LoggingHandler / 자체 javaagent                    │
├───────────────────────────────────────────────────────────┤
│ Layer 3: Path Exploration (분기 탐색)                       │
│  - JDart (콘콜릭, 1차)                                       │
│  - Coverage-guided fuzzer + JaCoCo (2차)                    │
│  - EvoSuite bridge (3차)                                    │
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
│  - scip-java (Maven + Gradle 빌드 지원)                      │
│  - Spoon AST enricher (어노테이션, String 리터럴, dataflow)   │
└───────────────────────────────────────────────────────────┘
```

## 핵심 도구

| 레이어 | 도구 | 역할 |
|---|---|---|
| L1 | scip-java | 타입 해석된 심볼/호출/import 그래프 |
| L1 | Spoon | AST 레벨 enrichment (어노테이션, dataflow 보조) |
| L2 | Spring Boot TestContext | 실제 빈 와이어링 introspection |
| L2 | Hibernate SchemaExport | JPA Entity → DDL |
| L2 | Flyway/Liquibase parser | 마이그레이션 → DDL truth |
| L2 | MyBatis Configuration | mapper 인벤토리 |
| L3 | JDart | 콘콜릭 path 탐색 (1차) |
| L3 | 자체 fuzzer | coverage-guided, JaCoCo 피드백 (2차) |
| L3 | EvoSuite | search-based input 탐색 (3차) |
| L3 | JaCoCo | branch coverage 측정 |
| L3 | Testcontainers | 실제 DBMS (운영과 동일 버전) |
| L4 | Hibernate SQL logger | JPA 발행 SQL 캡처 |
| L4 | MyBatis Interceptor | MyBatis 발행 SQL 캡처 (동적 SQL 포함 실제 형태) |
| L4 | WireMock | 분석용 임베디드 HTTP mock, recorder 활성 |
| L4 | Netty LoggingHandler | 바이트 hex dump |
| L4 | 자체 javaagent | InputStream/OutputStream 후킹 (raw socket) |
| L5 | Graph store | Neo4j 등 (의사결정 필요) |
| L5 | Vector store | pgvector 등 (의사결정 필요) |

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

## 분석 환경

분석 시점에 도구가 직접 띄우는 환경:

- DB: Testcontainers (운영 동일 DBMS/버전). 인메모리 DB는 보조.
- HTTP downstream: 임베디드 WireMock + recorder
- Socket downstream: 임베디드 자체 Netty mock + byte recorder
- Spring TestContext로 SUT 부팅 (실제 빈 와이어링)
- JaCoCo 에이전트로 coverage 측정

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

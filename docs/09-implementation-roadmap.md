# 09 — 구현 로드맵

TDD 기반. 매 phase 끝에 E2E 통합 동작 확인.

## 현황 (2026-09-01)

| 단계 | 상태 |
|---|---|
| 0, 1 | 완료 (`archive/progress/` 참조) |
| 2 (WireMock/외부 HTTP 캡처) | 완료 — `HttpCaptureServer` (`archive/progress/2-*.md`) |
| 3 (WebSocket/STOMP) | 완료 — `WsEndpointIndexer`/`WsCaptureRunner` (`archive/progress/3-*.md`) |
| 4 (Netty), 5 (Raw Socket) | **홀딩** (2026-06-11 사용자 결정, Phase 6 선행) |
| 6.1 그래프 스토어 | 완료 — 파티션 샤드 파일 스토어, Neo4j 보류 (`docs/decisions/graph-store-phase6.md`) |
| 6.2 증분 빌드 | 완료 — `--incremental-base`/`--changed-files` (`archive/progress/6-2.md`) |
| 6.3, 6.4 | 미착수 |
| 7 (auth · DB-agnostic · multi-method/GET read-path · constraint-directed input + 콘콜릭 oracle) | 완료 (`archive/progress/7-*.md`) |
| 입력 발견 Stage 0–4 | 완료 — happy 합성 → 다필드 가드 변이 → by-id 진입·시드 리셋(2026-06-15) → 저장된 행 상태 가드 시드(`ConstraintExtractor.extractStateGuards`) ([23](23-input-generation-flow.md)) |
| OTEL SQL 캡처 (2026-06-18) | 완료 — `SqlCaptureBackend` 추상화 + `OtelSpanCapture` 기본 백엔드 ([06](06-test-environment.md) "trace 모드") |
| Kafka outbound produce 캡처 (2026-06-18) | 완료 — 요청별 trace-id 귀속 + 생성 테스트 어설션 합성 |
| 레거시 Sleuth trace 모드 (2026-06-19) | 완료 — `samples/legacy-tram` 라이브 검증 (`e2e/run-legacy-tram-sleuth-e2e.sh`) |
| 삼중 합성 Phase A (2026-07-27) | 완료 — `provenance`/`synthesize-triple`/`trial` CLI + 에이전트 스킬, CI 회귀화 ([03](03-graph-rag-builder.md) "삼중 합성") |
| 어설션 provenance 승격 (2026-09-01) | 완료 — 에러 엔벨로프 계약·메시지 리터럴 기반 `equalTo` 승격 ([04](04-test-generator.md) "Assertion 합성 규칙") |

내력 상세와 날짜별 기록은 [CHANGELOG.md](../CHANGELOG.md).

### 캡처 백엔드 확장 (Phase 7 이후, 2026-06)

| 작업 | 상태 |
|---|---|
| SQL 캡처 백엔드 추상화 + OTEL DB span 기본 | 완료 (2026-06-18) — `SqlCaptureBackend`/`OtelSpanCapture`, 로그 byte-offset 폴백. `--trace-mode otel`(기본) (`docs/06`, PR #60) |
| 레거시 Sleuth(B3) trace 모드 + legacy-tram 라이브 E2E | 완료 (2026-06-19) — `--trace-mode sleuth --capture-services`, `samples/legacy-tram`에서 R1/CAP/NOISE PASS (PR #60·#63) |
| Kafka outbound produce 캡처 + 어설션 합성 | 완료 (2026-06-18) — `KafkaCaptureReceiver`→`CapturedEventEmit`, `--kafka-bootstrap`(attach)/`--with-kafka`(분석) (PR #61) |

### 입력 발견 단계 (Stage 0–3b, Phase 7 이후 심화)

Phase 7의 입력 생성을 "더 흔한 분기 종류"로 넓히는 단계별 작업. 상세는 docs/23·24, spec은
`docs/superpowers/specs/2026-06-1*-stage*`.

| Stage | 내용 | 상태 |
|---|---|---|
| 0 | 유효 happy 합성(enum 첫상수/날짜 ISO/이메일) | 완료 (petclinic 33→47/253) |
| 1/2 | 메서드 내 `&&` conjunction 추출 + joint/enum 변이 | 완료 (47→69, VIP arm 도달) |
| 3 | by-id 진입(PUT/DELETE path-id 시드, boolean, enum 컬럼 시드) | 완료 (69→113) |
| 3b | mutating by-id 요청별 시드 리셋 + 결정성 구체 어설션 | 완료 (생성 by-id 빈 DB 재현, 16/16) |
| — | CI 회귀 보호: order-service Booking 리소스로 Stage 0–3b 라이브 검증 | 완료 (e2e 22→53) |
| 4 | 저장된 행 상태 가드 인식 + 시드 합성(`ConstraintExtractor.extractStateGuards`) | 완료 |
| 4 확장(예정) | 상태 의존 가드 **양 arm** concolic 시드 변종(stale 과거날짜, capacity 다중행) | 미착수 |

## 권장 순서 원칙

1. **의존성 역순**: 다른 모듈이 의존하는 것 먼저
2. **결정 불필요 우선**: 의사결정이 끝났거나 영향이 작은 것 먼저
3. **결정 필요한 것은 모아서 한 번에 요청**
4. **각 phase 끝에 E2E 검증** 후에야 다음 phase로

## Phase 0 — 단일 endpoint smoke

목표: 100K 모던 프로젝트의 JPA-only POST 엔드포인트 하나에 대해
build → graph → generate → run → pass 전 사이클 통과.

| 단계 | 산출 | TDD 목표 |
|---|---|---|
| 0.1 프로젝트 골격 | 빌드 시스템, 모듈, CI 골격 | 빌드 통과 |
| 0.2 shared/model | 도메인 DTO + JSON 직렬화 | 라운드트립 테스트 |
| 0.3 testlib api/noop | 인터페이스 + noop 어댑터 | API contract 테스트 |
| 0.4 test-state-dashboard 골격 | 이벤트 수신 + 메모리 상태 | 이벤트 처리 단위 테스트 |
| 0.5 socket-mock-server 골격 | 컨테이너에서 실행 가능 | admin API 단위 테스트 |
| 0.6 graph-rag-builder Phase 0 | scip-java + JPA 단일 endpoint 캡처 | endpoint 1개 정확 캡처 |
| 0.7 test-generator Phase 0 | 단일 RestAssured POST 템플릿 | 결정적 출력 테스트 |
| 0.8 Phase 0 E2E | docker-compose 환경에서 생성 테스트 실행 | 생성 테스트 통과 |

Phase 0의 PoC 통과율 메트릭: 1/1 endpoint의 생성 테스트가 docker-compose 환경에서 통과.

## Phase 1 — 분기 탐색 + MyBatis

목표: 같은 100K 프로젝트에서 분기 다수 + MyBatis 케이스.

| 단계 | 산출 |
|---|---|
| 1.1 PathExplorer SPI + 자체 fuzzer | coverage-guided + JaCoCo 통합 |
| 1.2 정적 제약 추출 (콘콜릭 대체) → 이후 ConcolicOracle(ASM+Z3) oracle | handler 분기 조건 정적 수집, 이후 콘콜릭 oracle로 능력 도입 (`docs/24`, `docs/decisions/explorer-engines.md`) |
| 1.3 MyBatis XML mapper 인덱서 | XML mapper SQL 캡처 |
| 1.4 MyBatis Interceptor 캡처 | 동적 SQL의 실제 형태 캡처 |
| 1.5 다중 path → 다중 테스트 합성 | 도구 2가 path별 테스트 생성 |

Phase 1 메트릭: 같은 endpoint의 N개 path가 N개 테스트로 합성되고 통과.

## Phase 2 — WireMock 통합

목표: SUT가 외부 HTTP 호출하는 endpoint 처리.

| 단계 | 산출 |
|---|---|
| 2.1 RestTemplate/WebClient/Feign 호출 추적 | dataflow로 URL/body |
| 2.2 임베디드 WireMock recorder | 분석 시 응답 shape 캡처 |
| 2.3 OTEL javaagent 통합 (분석 + 테스트 양쪽) | baggage propagation 활성 |
| 2.4 도구 2의 http-mock-composer | WireMock 스텁 + baggage 매칭 합성 |
| 2.5 응답 필드 사용 추적 | 소비자 코드가 읽은 필드만 mock |

Phase 2 메트릭: 외부 HTTP 호출 있는 endpoint의 테스트가 WireMock 사용해 통과 + 병렬 안전.

## Phase 3 — WebSocket / STOMP

목표: Spring STOMP 사용 endpoint 처리.

| 단계 | 산출 |
|---|---|
| 3.1 `@MessageMapping` 등 어노테이션 분석 | WsEndpoint 노드 그래프 |
| 3.2 STOMP 통신 캡처 | 분석 환경에서 메시지 캡처 |
| 3.3 테스트의 STOMP 클라이언트 helper | testlib에 추가 |

## Phase 4 — Netty 소켓

목표: Netty 기반 outbound 소켓 통신.

| 단계 | 산출 |
|---|---|
| 4.1 Netty pipeline 분석기 | ChannelInitializer 분석 |
| 4.2 ByteLayout 추출 | encoder/decoder의 byte 시퀀스 |
| 4.3 socket-mock-server 본격 활용 | 분석 시 임베디드 mock 결합 |
| 4.4 도구 2의 socket-mock-composer | byte 시퀀스 합성 |
| 4.5 ProtocolDecoder SPI | 사양 등록 시 디코드 가능 |

## Phase 5 — Raw Socket

목표: `java.net.Socket` 직접 사용 케이스.

| 단계 | 산출 |
|---|---|
| 5.1 javaagent로 stream 후킹 | Input/OutputStream 사용 캡처 |
| 5.2 best-effort 합성 | 사양 부재 시 raw byte 그대로 |
| 5.3 ProtocolDecoder 자리 비워둠 | 사양 확보 시 backfill |

## Phase 6 — 500만 라인 레거시 이식

목표: A 프로젝트(Java 8 + SB2)로 확장.

| 단계 | 산출 |
|---|---|
| 6.1 분산 그래프 스토어 검토 | 노드 수천만 처리 |
| 6.2 증분 빌드 인프라 | 모듈/패키지 단위 파티셔닝 |
| 6.3 야간 풀 + PR 증분 | 표류 보정 운영 |
| 6.4 raw socket 보강 어노테이션 도입 | 프로토콜 사양 부재 영역 보강 |

## Phase 7 — MSA 일반화 (auth · DB-agnostic · multi-method · 제약-지향 입력)

목표: 이기종 MSA 서비스로 확장. 인증·다양한 DBMS·다양한 HTTP 메서드·제약 기반 입력 합성.

| 단계 | 산출 |
|---|---|
| 7.1 인증 | `AuthTokenProvider`/`AuthConfig`: endpoint별 `authRequired` 시 토큰 발급·헤더 주입 |
| 7.2 DB-agnostic | `DbConfig`/`JdbcContainers`(Postgres·MySQL·MariaDB)/`ComposeInspector`(compose에서 DBMS·자격 추출, `${VAR:-default}` 전개) |
| 7.3 multi-HTTP-method + GET read-path | PUT/DELETE/PATCH 등 PATH param 치환, `ReadInputSynthesizer`로 GET seed 합성 |
| 7.4 제약-지향 입력 + 콘콜릭 oracle | `InputOracle`(static-literal + `ConcolicOracle` ASM/Z3), `ConstraintExtractor`가 산출한 후보를 필드별 투영 |

Phase 7 메트릭: 이기종 MSA(예: diary=Java23, mindgraph=Java11, auth-user=MySQL)의 endpoint가 인증·다중 메서드·제약 입력으로 통과.

## 각 단계의 TDD 흐름

각 단계는 다음 순서:

1. **테스트 먼저**: 단위 테스트 작성 (실패 상태)
2. **최소 구현**: 테스트 통과시키는 최소 코드
3. **리팩터**: 중복 제거 + 의도 명확화
4. **단위 테스트 통과 확인**
5. **단계 완료 시**:
   - `progress/{phase}-{step}.md` 작성: 진행 내용 + 검수 + 설계 부합 확인
   - 다음 단계로 이동

## 매 Phase 끝의 E2E

각 Phase 끝에 docker-compose 환경에서 전 사이클 통합 실행. 통과율을 메트릭으로 기록.

## 위험 관리

| 위험 | 대응 |
|---|---|
| 콘콜릭 솔버의 5M 라인 검증 부재 | Phase 1에서는 fuzzer 위주, ConcolicOracle(ASM/Z3)은 100K 검증 후 확장 (JDart는 보류, `docs/decisions/explorer-engines.md`) |
| OTEL agent 라이브러리 호환성 | agent 버전 고정 + CI 검증 |
| SUT 외부 프로세스 부팅 시간 | jar 재사용 / 공유 탐색 환경 전략 |
| Testcontainers 비용 | suite 단위 컨테이너 재사용 |
| 민감 정보 캡처 누출 | 패턴 마스킹 + 캡처 직후 sanitize |

## 구체화된 앞으로 할 일

- **OpenAPI 기반 외부 stub seeding**과 **override 키(JAVA_TOOL_OPTIONS 등) 치환 경고** 2건은
  착수 전 로드맵으로 방향·통합 지점·리스크를 정리해 두었다 —
  [archive/27-roadmap-otel-capture-stub-seeding](archive/27-roadmap-otel-capture-stub-seeding.md)
  (같은 문서의 ① OTEL SQL 캡처는 2026-06-18 완료). 각 항목은 착수 시 spec→plan으로 확장한다.

폼/ParamMap 엔드포인트 테스트 생성(현재 미지원)은
[superpowers/followup/2026-06-25-form-parammap-test-generation](superpowers/followup/2026-06-25-form-parammap-test-generation.md)
참조 — A(form-urlencoded, 우선)·B(멀티파트)·C(동적 `@RequestParam Map`) 3갈래로 현황·필요 작업·리스크를
정리했다. A는 생성기/템플릿 변경에 국한(빌더 무변경), B·C는 빌더 확장 필요.

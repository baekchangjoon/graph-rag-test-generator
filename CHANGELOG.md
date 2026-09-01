# Changelog — 개발 내력

프로젝트의 단계(phase)별 진행 기록. 현재 상태와 다음 단계의 단일 출처는
[docs/09-implementation-roadmap.md](docs/09-implementation-roadmap.md)다.

## 2026-09-01 — 어설션 provenance 승격 (PR #123·#124)

- 실패 경로의 Spring 에러 엔벨로프(`status`/`error`/`path`)를 프레임워크 계약 증명으로
  `equalTo` 승격, 핸들러 소스의 예외 메시지 리터럴을 graph.json에 실어(`Endpoint.errorMessageLiterals`)
  message 노출 SUT에서 `message` 어설션 합성. 뮤테이션 재검증으로 겹가드 마스킹 해소 확인.
- CI: sonar 스캔에 JaCoCo 집계 커버리지 배선(이전에는 커버리지 데이터 없이 스캔).
- 상세: `docs/superpowers/requirements/2026-09-01-assertion-provenance-requirements.md`

## 2026-07-26~27 — 삼중 합성(Phase A)

다중 가드(입력 검증→DB 상태 비교→외부 응답 검증) 순차 조건이 막던 깊은 happy path를 열기 위해
`provenance`/`synthesize-triple`/`trial` CLI 3종 + 에이전트 스킬 3종(`.claude/skills/`) +
결정적 검증 게이트(마커 계약·seed.sql 화이트리스트·스키마·PII)를 도입. order-service fixture
엔드포인트 4종(fulfillment/transfers/invoices/quotas)으로 CI 회귀화.

## 2026-06-19 — 레거시 Sleuth trace 모드 + legacy-tram 라이브 E2E (PR #60·#63)

Java 8 + Sleuth(B3) + Eventuate Tram MSA에서 `--trace-mode sleuth --capture-services a,b,c`로
order-web→reservation→ledger 동기/비동기 홉의 SQL을 요청 단위로 회수. `samples/legacy-tram`
(Boot 2.7·MySQL binlog/CDC)에서 B3 전파·캡처·노이즈 배제 3종 수용 기준 라이브 통과.
런북 `e2e/run-legacy-tram-sleuth-e2e.sh`.

## 2026-06-18 — OTEL SQL 캡처 · Kafka outbound produce 캡처 (PR #61)

- SQL 캡처를 교체 가능한 `SqlCaptureBackend`로 추상화하고 OTEL agent의 DB span을 요청별
  `traceparent`로 귀속하는 `OtelSpanCapture`를 기본으로 도입(`--trace-mode none`은 로그 파싱 폴백).
  petclinic·tainted-spring MSA(Postgres·MySQL, JDK 8/11/17/23) 교차 검증.
- SUT가 발행하는 Kafka 메시지를 요청별 trace-id로 귀속 캡처(`KafkaCaptureReceiver` →
  `CapturedEventEmit`)하고 생성 테스트가 `KafkaHelper.consumeNextRecord` + JSONAssert로 어설션 합성.
  attach 모드 `--kafka-bootstrap`, 분석 모드 `--with-kafka`.

## 2026-06-15 — 입력 발견 Stage 0–3b

유효 happy 합성(enum/날짜/이메일) → 다필드 `&&` conjunction joint/enum 변이 →
by-id(PUT/DELETE/{id}) 진입(path-id 시드·boolean·enum 컬럼) → mutating by-id 시드 리셋·구체
어설션. 외부 spring-petclinic 적용 실측(coveredAppBranches 33→113/253). order-service에
Booking 추가로 CI 회귀화.

## 2026-06-14 — Phase 7: 다중 HTTP method + JWT 인증 + DB 비종속 + GET read-path

GET/PUT/DELETE/PATCH 인덱싱, `--sut-compose` 기반 DB 타입 자동 탐지(Postgres·MySQL·MariaDB),
`--auth-*` JWT 인증 주입(탐색·생성 테스트 양쪽), GET 조회 경로 시드 + 결정적 합성.

## 2026-06-11 — Phase 6.1·6.2: 파티션 그래프 스토어 + 증분 빌드

`PartitionedGraphStore`(Neo4j 보류 — `docs/decisions/graph-store-phase6.md`),
`--incremental-base`/`--changed-files`로 더티 파티션만 재탐색. Phase 4·5(Netty/Raw Socket)는
사용자 결정으로 보류하고 Phase 6 선행.

## 2026-06-10 — Phase 0~3

- **Phase 0**: 단일 JPA endpoint의 build → graph → generate → run → pass 사이클 통과.
- **Phase 1**: 분기 탐색(휴리스틱 + fuzzer + JaCoCo) + MyBatis. still_missing 리포트 +
  `--manual-paths` 수동 보강.
- **Phase 2**: WireMock 통합 — 외부 HTTP 캡처(임베디드 WireMock + `--external-stubs`/`--sut-env`),
  OTEL javaagent baggage 전파, 생성 테스트의 스텁 합성(baggage 격리 + consumedFields 투영).
- **Phase 3**: WebSocket/STOMP — WsEndpoint 인덱싱, 최소 STOMP 클라이언트로 메시지 교환 캡처,
  testlib StompHelper, echo 마커 기반 병렬 격리 합성.

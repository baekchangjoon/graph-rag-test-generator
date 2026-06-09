# 09 — 구현 로드맵

TDD 기반. 매 phase 끝에 E2E 통합 동작 확인.

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
| 1.2 JDart bridge | 콘콜릭 1차 탐색 |
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

## 미결 의사결정의 영향

OPEN-DECISIONS.md 항목 중 일부는 phase 진입을 막을 수 있음:

- Phase 0 진입 전 필수: 빌드 시스템, 언어, Java 버전, Phase 0 PoC endpoint 선택
- Phase 1 진입 전: graph store / vector store 선택 (Phase 0은 file-system 임시 영속으로 시작 가능)
- Phase 4 진입 전: socket 프로토콜 사양 확보 여부

## 위험 관리

| 위험 | 대응 |
|---|---|
| JDart의 5M 라인 검증 부재 | Phase 1에서는 fuzzer 위주, JDart는 100K 검증 후 확장 |
| OTEL agent 라이브러리 호환성 | agent 버전 고정 + CI 검증 |
| Spring TestContext 부팅 시간 | 컨텍스트 캐싱 / 공유 전략 |
| Testcontainers 비용 | suite 단위 컨테이너 재사용 |
| 민감 정보 캡처 누출 | 패턴 마스킹 + 캡처 직후 sanitize |

# graph-rag-test-generator

Java/Spring 레거시 코드베이스를 대상으로 **블랙박스 REST API 테스트의 테스트 데이터와 테스트 코드를 결정적으로 생성**하기 위한 시스템.

> Repo: https://github.com/baekchangjoon/graph-rag-test-generator

## 두 도구 + 보조 인프라

```
[사람 또는 LLM (외부 오케스트레이터)]
   ├──→ [도구 1: Graph RAG Builder]   ← 코드 → 그래프 RAG (LLM 없음)
   │       조회 API ←──── 사실 회수
   └──→ [도구 2: Test Generator]      ← 결정적 합성 (LLM 없음)
                                       템플릿 + 규칙
       산출물: RestAssured 테스트 + 픽스처 SQL + WireMock 스텁 + Socket mock 바이트
```

LLM은 **도구의 외부**에 위치. 도구 내부에는 없습니다.

## 현 진행 상태

| Phase | 상태 | E2E |
|---|---|---|
| 0 — 단일 path JPA endpoint | ✅ | `Phase0E2eTest` |
| 1 — multi-path + javac 검증 + MyBatis 캡처 | ✅ | `Phase1MultiPathE2eTest` |
| 2 — 외부 HTTP 캡처 + WireMock stub 합성 (통합 포함) | ✅ | `Phase2HttpE2eTest`, `Phase2HttpSynthesisE2eTest` |
| 3 — WebSocket/STOMP 모델 | 🔶 모델만 |  |
| 4 — Netty Socket 모델 + composer + ProtocolDecoder SPI | 🔶 SPI/composer |  |
| 5 — Raw socket javaagent | 🔶 SPI |  |
| 6 — 5M legacy 이식 | 📄 아키텍처 문서 | |

`./gradlew build`: BUILD SUCCESSFUL — 단위/통합/E2E 100+ 케이스 GREEN.

## 핵심 결정 요약

- 두 대상 프로젝트: Java 8 + Spring Boot 2 (500만 라인 레거시), Java 21 + Spring Boot 3 (10만 라인 현대화)
- ORM: MyBatis + JPA 양쪽 지원
- 외부 통신: HTTP REST + WebSocket + Raw Socket (Netty)
- 분석 방식: 도구 자체가 빌드/실행 가능 (Testcontainers, Spring Boot TestContext). 외부 실 트래픽 데이터는 사용하지 않음.
- 분기 탐색: JDart (콘콜릭) → coverage-guided fuzzer → EvoSuite 순차 보강 (Phase 1은 Manual만 구현)
- 테스트 형식: RestAssured 외부 호출. docker-compose로 SUT + DB + WireMock + socket-mock 운영
- 격리: testId 기반 + OTEL javaagent의 baggage propagation
- 모니터링: test-state-dashboard로 자원 사용/누수 추적

## 빠른 시작

요구: JDK 17 (Amazon Corretto 권장), git.

```bash
git clone https://github.com/baekchangjoon/graph-rag-test-generator.git
cd graph-rag-test-generator
./gradlew build            # 전체 모듈 빌드 + 단위/통합/E2E 실행

# Phase별 E2E 단독 실행
./gradlew :samples:demo-sut:test --tests "*.Phase0E2eTest"
./gradlew :samples:demo-sut:test --tests "*.Phase1MultiPathE2eTest"
./gradlew :samples:demo-sut:test --tests "*.Phase2HttpE2eTest"
./gradlew :samples:demo-sut:test --tests "*.Phase2HttpSynthesisE2eTest"

# 합성기 단위 (생성 코드 javac 컴파일 검증 포함)
./gradlew :test-generator:test
```

운영 환경 (생성된 테스트 실행)은 `docker-compose.yml` 참조.

## 모듈 구성

```
.
├── shared-model/            # 도메인 record (Endpoint, ExploredPath, CapturedSql/Http/Socket/...)
├── testlib-api/             # 테스트 helper SPI + TestScope + Config
├── testlib-adapter-noop/    # Phase 0 default 어댑터 + ServiceLoader 통합
├── test-state-dashboard/    # Spring Boot 앱: 테스트 자원 추적 + 누수 감지
├── socket-mock-server/      # Netty TCP mock + admin REST (Phase 0/4)
├── graph-rag-builder/       # 도구 1: 캡처 (JPA/MyBatis/HTTP) + 영속 + 조회 API
├── test-generator/          # 도구 2: 결정적 합성 + javac 검증
└── samples/demo-sut/        # PoC 대상 Spring Boot 3 + JPA SUT
```

## 디렉터리

```
.
├── README.md                # 본 파일
├── SCHEMAS.md               # API 스키마 정의
├── OPEN-DECISIONS.md        # 의사결정 기록 (default 수용)
├── docker-compose.yml       # 테스트 실행 환경 가이드
├── docs/                    # 주제별 설계 문서
│   ├── 01-overview.md
│   ├── 02-architecture.md
│   ├── 03-graph-rag-builder.md
│   ├── 04-test-generator.md
│   ├── 05-branch-exploration.md
│   ├── 06-test-environment.md
│   ├── 07-mock-infrastructure.md
│   ├── 08-dashboard.md
│   ├── 09-implementation-roadmap.md
│   └── 10-legacy-scaling.md
└── progress/                # 단계별 진행/검수 기록 (15개)
```

## 구현 원칙

- TDD: 모든 모듈은 테스트 우선 작성
- 결정적 동작: 동일 입력 → 동일 출력 (javac로 검증)
- 어댑터 분리: mock/auth/storage 등 외부 의존은 모두 SPI 뒤로
- 단계별 E2E: 매 phase 끝에 통합 동작 확인

자세한 내용은 `docs/`와 `SCHEMAS.md`를 참조하세요.

## 라이선스

Apache 2.0. `LICENSE` 참조.

# graph-rag

Java/Spring 레거시 코드베이스를 대상으로 **블랙박스 REST API 테스트의 테스트 데이터와 테스트 코드**를 결정적으로 생성하기 위한 시스템.

## 두 개의 도구 + 보조 인프라

```
[사람 또는 LLM (외부 오케스트레이터)]
   ├──→ [도구 1: Graph RAG Builder]   ← 코드 → 그래프 RAG (LLM 없음)
   │       조회 API ←──── 사실 회수
   └──→ [도구 2: Test Generator]      ← 결정적 합성 (LLM 없음)
                                        템플릿 + 규칙
       산출물: RestAssured 테스트 + 픽스처 SQL + WireMock 스텁 + Socket mock 바이트
```

LLM은 **도구의 외부**에 위치. 도구 내부에는 없습니다.

## 핵심 결정 요약

- 두 대상 프로젝트: Java 8 + Spring Boot 2 (500만 라인 레거시), Java 21 + Spring Boot 3 (10만 라인 현대화)
- ORM: MyBatis + JPA 양쪽 지원
- 외부 통신: HTTP REST + WebSocket + Raw Socket (Netty)
- 분석 방식: 도구 자체가 빌드/실행 가능 (Testcontainers, Spring Boot TestContext). 외부 실 트래픽 데이터는 사용하지 않음.
- 분기 탐색: JDart (콘콜릭) → coverage-guided fuzzer → EvoSuite 순차 보강
- 테스트 형식: RestAssured 외부 호출. docker-compose로 SUT + DB + WireMock + socket-mock 운영
- 격리: testId 기반 + OTEL javaagent의 baggage propagation
- 모니터링: test-state-dashboard로 자원 사용/누수 추적

## 디렉터리

```
.
├── README.md                        # 본 파일
├── SCHEMAS.md                       # API 스키마 정의
├── OPEN-DECISIONS.md                # 현재 미결 의사결정 목록
├── docs/                            # 주제별 설계 문서
│   ├── 01-overview.md
│   ├── 02-architecture.md
│   ├── 03-graph-rag-builder.md
│   ├── 04-test-generator.md
│   ├── 05-branch-exploration.md
│   ├── 06-test-environment.md
│   ├── 07-mock-infrastructure.md
│   ├── 08-dashboard.md
│   └── 09-implementation-roadmap.md
└── progress/                        # 단계별 진행/검수 기록
```

## 구현 원칙

- TDD: 모든 모듈은 테스트 우선 작성
- 결정적 동작: 동일 입력 → 동일 출력
- 어댑터 분리: mock/auth/storage 등 외부 의존은 모두 SPI 뒤로
- 단계별 E2E: Phase 0부터 매 phase 끝에 통합 동작 확인

자세한 내용은 `docs/`와 `SCHEMAS.md`를 참조하세요.

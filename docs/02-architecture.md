# 02 — 아키텍처

## 두 도구 + 보조 인프라

```
============== BUILD PHASE (오프라인, 커밋 단위) ==============
[Source]
  → Structural Indexer (scip-java + Spoon)
  → Framework Introspector (Spring context, Hibernate, MyBatis)
  → Schema Extractor (Hibernate SchemaExport + Flyway/Liquibase)
  → Path Explorer (JDart → fuzzer → EvoSuite)
  → Sink Capturer (Spring TestContext + Testcontainers + WireMock + custom socket mock)
  → Graph Persistor + Vector Indexer
              ↓
        [GRAPH RAG ASSET]
        - 구조 노드
        - path-conditioned facts
        - captured SQL/HTTP/socket
        - 스키마 (DDL truth)
        - 임베딩
============================================================

================ QUERY PHASE (온라인) =======================
사용자 또는 LLM (외부 오케스트레이터)
   1. Graph RAG 조회 → 사실 회수
   2. [도구 2 GenerationRequest 구성]
   3. POST /generate
              ↓
[도구 2] (LLM 없음, 결정적)
   - 입력 파싱 + 기존 산출물 분석
   - 적용 규칙 결정
   - 템플릿 + 프로그램으로 코드 합성
   - (옵션) self-check: compile + run + JaCoCo
              ↓
   GenerationResult + recommendations
              ↓
[오케스트레이터가 평가 → 부족하면 다시 호출]
============================================================
```

## 컴포넌트 책임 경계

### 도구 1: Graph RAG Builder

- LLM 없음
- 코드 분석 + 도구가 직접 실행해서 캡처
- Graph + Vector store에 사실 적재
- 증분 갱신
- 조회 API 제공

자세한 내용: `docs/03-graph-rag-builder.md`

### 도구 2: Test Generator

- LLM 없음
- Graph RAG 사실 + 사양 → 결정적 합성
- 큰 골격: 템플릿. 가변 길이 슬롯: 프로그램으로 합성 (방식 C)
- 기존 산출물 + 새 목표를 입력으로 받아 delta 출력

자세한 내용: `docs/04-test-generator.md`

### testlib

- 생성된 테스트가 의존하는 helper 라이브러리
- SPI + 어댑터로 mock/auth 백엔드 교체 가능
- TestScope 단위로 unique testId + cleanup 보장
- 대시보드 이벤트 발행 (fire-and-forget)

자세한 내용: `docs/07-mock-infrastructure.md`

### test-state-dashboard

- 별도 standalone 서비스 (docker-compose에 추가)
- testlib의 이벤트 수신, 메모리 상태 유지
- TTL 기반 누수 감지 + 알람 채널
- REST API + 간단한 web UI

자세한 내용: `docs/08-dashboard.md`

### socket-mock-server

- Netty 기반 standalone (오픈소스 표준 부재로 자체 제작)
- TCP/UDP 바인딩 + byte 패턴 매칭 + 응답 byte 시퀀스
- 다단계 stateful 세션 지원
- admin REST API로 시나리오 등록

자세한 내용: `docs/07-mock-infrastructure.md`

### 외부 오케스트레이터 (LLM 또는 사람)

- 도구의 부분이 아님
- 도구 1 조회 → 도구 2 호출 → 결과 평가 → 재호출 결정
- 참고 구현은 `orchestration-examples/`에 둠 (도구가 아닌 예제)

## 모듈 구성

```
repo/
├── graph-rag-builder/           # 도구 1
│   ├── ingestion/
│   ├── indexing/
│   ├── introspection/
│   ├── exploration/
│   ├── execution/
│   ├── capture/
│   ├── schema-extractor/
│   ├── embedding/
│   ├── persistence/
│   ├── update/
│   └── query-api/
├── test-generator/              # 도구 2
│   ├── input-parser/
│   ├── graph-rag-client/
│   ├── rules/
│   ├── composers/               # 가변 길이 슬롯 프로그램
│   ├── templates/               # 큰 골격 (Mustache)
│   ├── snippets/                # 작은 정형 조각 (Mustache)
│   ├── self-check/
│   ├── coverage-reporter/
│   └── api/
├── testlib/                     # 런타임 helper
│   ├── api/
│   ├── adapter/
│   │   ├── http-mock/           # WireMock default
│   │   ├── socket-mock/         # custom Netty default
│   │   ├── jdbc/
│   │   ├── auth/
│   │   └── dashboard-reporter/
│   └── runtime-config/
├── socket-mock-server/          # standalone
├── test-state-dashboard/        # standalone
├── shared/
│   ├── model/
│   ├── spi/
│   └── client/
└── orchestration-examples/      # 참고 구현
    ├── human-cli/
    └── claude-agent/
```

## 두 phase의 데이터 흐름

### Build phase

```
Source code
   ↓
Structural index (scip-java, Spoon)
   ↓
Framework introspection (Spring Boot test context, Hibernate, MyBatis 인벤토리)
   ↓
Path explorer (JDart → fuzzer → EvoSuite, JaCoCo로 coverage 누적)
   ↓
Execution harness (Testcontainers DB, WireMock, custom socket mock)
   ↓
Sink capturer (Hibernate SQL log, MyBatis Interceptor, WireMock recorder, byte logger)
   ↓
Graph + Vector store
```

### Query phase

```
오케스트레이터: GenerationRequest 작성
   ↓
도구 2: 입력 파싱
   ↓
도구 2: Graph RAG 조회 (도구 1의 query API)
   - matching paths
   - captured SQL/HTTP/socket
   - schema, FK
   - propagation info
   ↓
도구 2: 규칙 결정 + 합성
   - testId 발급
   - origin 기반 치환
   - 템플릿 + 프로그램으로 코드 합성
   ↓
(옵션) self-check: 도커 환경에서 컴파일/실행/coverage 확인
   ↓
GenerationResult + 산출 파일들
```

## 단일/병렬 실행 모델

- 도구 1: 멀티 인스턴스 가능. 같은 그래프를 공유 (read-only path).
- 도구 2: 완전 stateless. 동시 다중 호출 안전.
- 갱신 트리거 (`/index/incremental`)는 도구 1 내부에서 직렬화.

## 환경 분리

- **build environment** (도구 1 분석용): Testcontainers + 임베디드 WireMock + custom socket mock. 자유롭게 컨테이너 재기동.
- **test runtime environment** (생성된 테스트 실행): docker-compose 운영 동일 DBMS + WireMock 서비스 + socket-mock-server 서비스 + dashboard.

두 환경은 별개입니다. 도구 1의 분석 환경과 도구 2가 생성한 테스트가 실행되는 환경을 혼동하지 않도록.

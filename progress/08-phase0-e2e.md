# Progress: Phase 0 E2E 통합

**Date**: 2026-05-25
**Task**: #10 Phase 0 E2E 통합
**Result**: 전체 사이클 (capture → archive → synthesize) 성공

## 산출물

### demo-sut (Phase 0 PoC 대상)

```
samples/demo-sut/src/main/java/io/graphrag/demo/
├── DemoSutApplication.java
├── domain/
│   ├── UserEntity.java           # @Entity users
│   ├── OrderEntity.java          # @Entity orders, FK to users
│   ├── UserRepository.java       # JpaRepository
│   └── OrderRepository.java      # JpaRepository
└── api/
    ├── CreateOrderRequest.java
    ├── OrderResponse.java
    └── OrdersController.java     # POST /api/orders

src/main/resources/
├── application.yml               # Postgres datasource via env
├── application-test.yml          # test profile
└── schema.sql                    # users + orders 테이블 + FK
```

### docker-compose.yml (테스트 실행 환경)

Postgres 15-alpine + WireMock + socket-mock-server + test-state-dashboard. demo-sut은 별도 실행 가정 (Phase 1+에서 컨테이너 이미지 추가).

### Phase0E2eTest

전체 파이프라인 통합 테스트. 1 test, PASSED.

테스트 단계:
1. CaptureContext 활성 + ProxyDataSource(CapturedSqlListener) 주입 via BeanPostProcessor
2. demo-sut의 POST /api/orders 실제 호출 (MockMvc + H2 in-memory)
3. CapturedSql 누적 확인 (INSERT INTO orders 포함)
4. GraphArchive에 저장 + 다시 로드 → 일관성 확인
5. TestSynthesizer로 Java 테스트 코드 합성
6. 합성된 코드의 핵심 fragment 검증:
   - `class OrdersPostTest`
   - `package io.graphrag.demo.generated;`
   - `@Test`, `.post("/api/orders")`, `UUID.randomUUID()`
   - 캡처된 INSERT가 fixture로 포함

## 통과한 전 사이클

```
[demo-sut: 실 POST /api/orders 호출]
        ↓
[ProxyDataSource + CapturedSqlListener]
        ↓
[CaptureContext에 CapturedSql 누적]
        ↓
[GraphArchive.save() → endpoints.json + captured_sql.json]
        ↓
[GraphArchive.load() → 일관성 검증]
        ↓
[TestSynthesizer.synthesize() → Java 소스 코드 (String)]
        ↓
[합성 코드 fragment 검증]
```

## 발견 및 수정

1. **shared-model deps 누락**: graph-rag-builder는 `implementation`으로 shared-model 의존, transitive 노출 안 됨. demo-sut test에 `testImplementation(project(":shared-model"))` 명시 추가.
2. **Testcontainers + Docker socket**: macOS Docker Desktop의 socket 경로 (`com.docker.docker/Data/docker-cli.sock`)를 Testcontainers UnixSocketStrategy가 못 찾음. Phase 0 E2E에서는 H2 (Postgres 호환 모드)로 우회. 운영 환경 (docker-compose)은 실 Postgres 사용.
3. **DataSource 순환 의존성**: `@Bean @Primary DataSource(DataSource original)` 패턴이 자기 참조. BeanPostProcessor 패턴으로 우회: Spring이 만든 DataSource 빈을 사후 wrap.

## 설계와의 부합 확인

| 항목 | 결과 |
|---|---|
| 도구 1 (graph-rag-builder) 캡처 → 도구 2 (test-generator) 합성 사이클 | OK |
| RestAssured 스타일 합성 (외부 HTTP 클라이언트) | OK (생성 코드 검증) |
| 결정적 합성 (LLM 없음) | OK |
| 자기 스코프 cleanup, FK 역순 DELETE | OK (FixtureComposer) |
| testId 발급, UUID 기반 | OK |
| docker-compose env 가정 (env vars: JDBC_URL, APP_BASE_URI) | OK |

## 의도적으로 후속 phase로 미룬 항목

- **운영 동일 DBMS로 분석 환경**: 현재 H2. Phase 1+에서 Testcontainers Postgres로 (Docker socket 환경 설정 후)
- **생성 코드의 컴파일/실행 검증**: 현재는 fragment 매칭. Phase 1+에서 javac 호출하여 실제 컴파일 확인
- **docker-compose에서 demo-sut을 컨테이너로 실행**: 현재는 호스트 실행 가정. Phase 1+에서 demo-sut Dockerfile + sut 서비스 추가
- **OTEL javaagent 부착**: docker-compose에 명시했으나 demo-sut 컨테이너화 시 적용
- **socket-mock-server + dashboard + wiremock 통합 동작**: docker-compose 정의는 있으나 통합 테스트는 Phase 1+

## 다음 단계 (Phase 1)

- JPA 통합 테스트의 Postgres 전환
- 생성 코드 javac 컴파일 검증
- 분기 탐색 엔진 (자체 fuzzer) 통합
- MyBatis Interceptor 캡처
- demo-sut의 Dockerfile + docker-compose 통합 실행
- baggage propagation 적용 (분석 + 합성 양쪽)

# 2026-06-10 — Phase 0 재구축 설계 (graph-rag-fable)

## 배경

이전 구현(`/Users/changjoonbaek/graph-rag/graph-rag`)은 6단계 오케스트레이터, 외부
scout-launcher, private jdbc-intercept-agent, 외부 petclinic 클론 의존으로 복잡도가
폭발해 중단되었다. 이 리포는 `docs/01-overview.md`(요구사항)와
`docs/02-architecture.md`(아키텍처)를 우선 기준으로 처음부터 재구현한다.
나머지 문서(03~22)는 참고 자료다.

## 사용자 결정 (2026-06-10)

| 항목 | 결정 |
|---|---|
| 진행 범위 | Phase 0 완료 후 멈추지 않고 Phase 1, 2, … 순으로 자율 진행 |
| Phase 0 SUT | 리포 내 자체 샘플 SUT (`samples/order-service`) |
| 도구 기술 스택 | Java 17 + Gradle 멀티모듈 |
| 그래프 영속 | 파일 기반 JSON (저장소 인터페이스 추상화, Phase 1에서 재평가) |
| 작업 범위 | 이 폴더(`graph-rag-fable`) 내로 국한. 외부 경로 변경 금지 |
| 의사결정 기록 | 기능 단위로 `docs/decisions/`에 문서화 |

## Phase 0 목표 (roadmap 09 기준)

샘플 SUT의 JPA-only POST endpoint 1개에 대해
**build → graph → generate → run → pass** 전 사이클 통과.

메트릭: 1/1 endpoint의 생성 테스트가 docker-compose 환경에서 통과.

## 리포 구성

```
graph-rag-fable/
├── shared-model/            # 도메인 DTO + JSON 직렬화
├── testlib/                 # 생성 테스트의 helper 라이브러리 (api + adapter)
├── test-state-dashboard/    # 이벤트 수신 + in-memory 상태 + TTL 누수감지
├── socket-mock-server/      # Netty TCP mock + admin REST 골격
├── graph-rag-builder/       # 도구 1
├── test-generator/          # 도구 2
├── samples/order-service/   # 샘플 SUT: Spring Boot 3 + JPA + Postgres
├── e2e/                     # Phase 0 E2E
├── docs/decisions/          # 기능 단위 의사결정 문서
└── progress/                # 단계별 진행 기록 ({phase}-{step}.md)
```

## 컴포넌트 설계

### shared-model (0.2)

`SCHEMAS.md`가 유실되어 docs 03/04/08의 서술로부터 재정의한다 (의사결정 문서로 기록).

- 그래프 사실: `Endpoint`, `ExploredPath`, `CapturedSql`(+`SqlBinding` origin:
  API_PARAM/LITERAL/COMPUTED), `TableSchema`/`ColumnSchema`(+FK, UNIQUE)
- 도구 2 계약: `GenerationRequest`, `GenerationResult`
- 대시보드 계약: `TestEvent` (SCOPE_CREATED/SCOPE_CLEANED/DB_ROW_INSERTED/…)
- Jackson 직렬화, 라운드트립 테스트

### testlib (0.3)

- `api/`: `TestScope`, `JdbcHelper`, `RestAssuredHelper`, `HttpMockClient`,
  `SocketMockClient`, `AuthClient`
- `adapter/`: ServiceLoader 기반 SPI. Phase 0 구현체:
  - jdbc: plain JDBC (생성 테스트가 실제 사용)
  - dashboard-reporter: http (fire-and-forget) + noop (`DASHBOARD_URL` 미설정 시)
  - http-mock / socket-mock / auth: noop (Phase 2+에서 실제 구현)
- fail-fast: `APP_BASE_URI`, `JDBC_URL` 등 필수 env 누락 시 즉시 실패.
  dashboard만 예외 (없으면 noop).

### test-state-dashboard (0.4)

- Spring Boot 단일 앱. `POST /events` 수신 → in-memory `TestRun` 상태 갱신
- `GET /active`, `GET /test/{id}`, `GET /leaked`
- TTL 누수 감지 (기본 300s, 주기 스캔). Reaper는 비활성 (Phase 0 범위 외)
- Dockerfile 포함 (compose 통합)

### socket-mock-server (0.5)

- Netty TCP 바인딩 + byte 패턴(hex exact/prefix) 매칭 + 응답 byte 시퀀스
- admin REST: `POST /__admin/expectations`, `DELETE /__admin/expectations`
- Dockerfile 포함. Phase 0 생성 테스트는 사용하지 않음 (골격 + 단위 테스트까지)

### samples/order-service (Phase 0 SUT)

- Spring Boot 3.x (Java 17) + Spring Data JPA + Postgres
- 도메인: `users`(부모) ← `orders`(자식, FK). docs 04 예제와 정합
- endpoint: `POST /api/orders` — body `{userId, amount, type}` → 201 `{id, status:"PENDING"}`
  (JPA-only, 외부 호출 없음), 404 (user 없음), 400 (검증 실패)
- 인증 없음 (`auth_required=false`), `ddl-auto=create` 허용 (스키마 추출용)
- 부트 가능한 운영 jar 빌드

### graph-rag-builder (0.6)

CLI: `build --sut-dir <dir> --sut-jar <jar> --out <graph-dir>`

1. **구조 인덱싱**: Spoon으로 SUT 소스 스캔 → `@RestController` + `@PostMapping`
   → `Endpoint` 노드 (메소드/경로/파라미터 타입/auth)
2. **분석 환경**: Testcontainers Postgres 기동 → SUT 운영 jar를 자식 프로세스로
   실행. env 주입만으로 datasource 연결 + Hibernate SQL 로깅 활성
   (`logging.level.org.hibernate.SQL=DEBUG`,
   `logging.level.org.hibernate.orm.jdbc.bind=TRACE`). SUT 소스 무수정 원칙 준수
3. **스키마 추출**: SUT 기동 후 DB의 JDBC 메타데이터에서 Table/Column/FK/UNIQUE
4. **경로 실행 + 캡처**: endpoint 요청 DTO에서 결정적 sample input 합성
   (사전 user INSERT 포함) → HTTP 호출 → SUT stdout의 Hibernate SQL 로그 파싱 →
   `CapturedSql` (binding origin: 요청 값과 일치 → API_PARAM, 그 외 → LITERAL/COMPUTED)
5. **영속**: `GraphStore` 인터페이스 + `JsonFileGraphStore` 구현 (graph-dir에 JSON)

### test-generator (0.7)

CLI: `generate --request <request.json> --graph <graph-dir> --out <out-dir>`

- `GraphRagClient` 인터페이스 + 파일 기반 구현 (HTTP는 Phase 1)
- 규칙 (docs 04): API_PARAM → testId 기반 unique 치환, LITERAL 보존,
  FK 정렬 INSERT, FK 역순 DELETE cleanup, NOT NULL 채움
- Mustache 템플릿 (test-class / before-each / after-each / test-method) +
  `FixtureComposer`, `AssertionComposer`
- 출력: RestAssured + testlib 기반 `.java` 1개 + 리포트. 동일 입력 = 동일 출력
  (시간/Random 금지)

### e2e (0.8)

- docker-compose: postgres:15 + SUT 이미지 + wiremock + socket-mock-server +
  test-state-dashboard
- 흐름: builder 실행(그래프 생성) → generator 실행(테스트 생성) → compose 기동 →
  생성 테스트를 호스트 Gradle 모듈에서 실행 (`APP_BASE_URI=http://localhost:...`) →
  통과 확인
- OTEL javaagent 부착은 Phase 2(WireMock 격리 필요 시점)로 보류

## 아키텍처 대비 의식적 보류 (decision 문서로 각각 기록)

| 항목 | 원안 (docs 02/03) | Phase 0 결정 | 복귀 시점 |
|---|---|---|---|
| L1 인덱싱 | scip-java + Spoon | Spoon만 | Phase 1+ 재평가 |
| SUT 부팅 | Spring TestContext in-process | 외부 프로세스(운영 jar) + env 주입 | 필요 시 재평가 |
| SQL 캡처 | Hibernate logger/MyBatis Interceptor | Hibernate 로그 파싱 (MyBatis는 Phase 1) | Phase 1 |
| 도구 1 조회 API | REST/gRPC | 파일 기반 GraphRagClient | Phase 1 |
| graph/vector store | Neo4j/pgvector 등 | JSON 파일 (인터페이스 추상화) | Phase 1 진입 전 |
| 임베딩/Vector | embedding 모듈 | 보류 (Phase 0 조회는 구조 질의만) | Phase 1+ |
| path exploration | JDart/fuzzer/EvoSuite | 단일 happy-path 결정적 input | Phase 1 |
| test-runner 컨테이너 | compose 내 컨테이너 | 호스트 Gradle 실행 | Phase 1+ |
| orchestration-examples | human-cli, claude-agent | 보류 | Phase 1+ |

## 진행 방식

- roadmap 09의 TDD 흐름: 단계별 테스트 먼저 → 최소 구현 → 리팩터 →
  `progress/{phase}-{step}.md` 기록
- 각 Phase 끝에 E2E 통과 후 다음 Phase 진입
- Phase 0 통과 후 Phase 1(분기 탐색 + MyBatis), Phase 2(WireMock) 순으로 자율 진행

## Phase 0 성공 기준

- [ ] `gradle check` 전 모듈 GREEN
- [ ] builder가 `POST /api/orders`의 Endpoint/ExploredPath/CapturedSql/Schema를
      JSON 그래프로 산출
- [ ] generator가 결정적으로 RestAssured 테스트 생성 (같은 입력 두 번 → byte 동일)
- [ ] docker-compose 환경에서 생성 테스트 실행 → PASS
- [ ] 의사결정 문서 + progress 기록 완비

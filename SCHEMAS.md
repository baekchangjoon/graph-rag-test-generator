# API Schemas

본 문서는 시스템 구성요소 간 계약(API 스키마)을 정의합니다.
모든 구현은 이 계약을 만족해야 합니다.

- 도구 1 (Graph RAG Builder) Query API
- 도구 2 (Test Generator) Request/Result API
- testlib (런타임 helper 라이브러리) 인터페이스
- test-state-dashboard 이벤트 스키마

스키마 변경은 `Schema Version`을 올리고 어댑터 layer에서 흡수합니다.

---

## 0. 공통 데이터 모델

모든 API가 참조하는 도메인 객체입니다. `shared/model` 모듈에 위치합니다.

```yaml
Endpoint:
  id: string                           # "POST:/api/orders"
  method: enum [GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS]
  path: string                         # "/api/orders/{id}"
  project: string
  handler_class: string
  handler_method: string
  auth_required: bool
  required_roles: [string]

ExploredPath:
  id: string                           # ULID
  endpoint_id: string
  discovered_by: enum [JDART, FUZZER, EVOSUITE]
  sample_input:
    headers: { name: value }
    path_params: { name: value }
    query_params: { name: value }
    body: any
  path_constraint:                     # JDart에서만 채워짐, nullable
    expression: string
    variables: [string]
  branches_taken: [string]
  exit_status: int
  exit_response_shape: any
  coverage_signature: string           # JaCoCo 해시
  code_version: string                 # commit SHA

Binding:
  position: int
  value: any
  origin: enum [
    API_PARAM,                         # 치환 가능
    LITERAL,                           # 보존
    COMPUTED,                          # 케이스별
    CONFIG_PROPERTY,
    GENERATED_BY_FRAMEWORK
  ]
  origin_ref: string                   # 예: "apiParam.userId"

CapturedSQL:
  id: string
  path_id: string
  type: enum [SELECT, INSERT, UPDATE, DELETE, DDL]
  raw_sql: string
  bindings: [Binding]
  source: enum [
    JPA_REPOSITORY_DERIVED,
    JPA_QUERY_ANNOTATION,
    JPA_CRITERIA,
    JPA_ENTITYMANAGER,
    MYBATIS_XML_MAPPER,
    MYBATIS_ANNOTATION,
    JDBC_TEMPLATE,
    JDBC_RAW
  ]
  source_location:
    class: string
    method: string
    line: int
  affected_tables: [string]
  affected_columns: [string]

CapturedHttpCall:
  id: string
  path_id: string
  method: string
  url_template: string
  url_concrete: string
  url_bindings: [Binding]
  request_headers: { name: value }
  request_body: any
  request_body_bindings: [Binding]
  response_status: int
  response_body_observed: any
  response_fields_read_by_sut: [string]  # JSON path
  client_type: enum [REST_TEMPLATE, WEBCLIENT, FEIGN, OKHTTP, OTHER]
  target_external_id: string

CapturedSocketIO:
  id: string
  path_id: string
  direction: enum [OUTBOUND, INBOUND]
  endpoint_host: string
  endpoint_port: int
  byte_hex: string
  byte_origin: string
  protocol: enum [TCP, UDP]
  framework: enum [NETTY, RAW_SOCKET, OTHER]
  protocol_decoded: any | null
  session_field: string | null

Table:
  name: string
  columns: [Column]
  primary_key: [string]
  foreign_keys: [{from_columns, to_table, to_columns}]
  unique_constraints: [[string]]
  check_constraints: [string]
  scope_reachable_via: [string]

Column:
  name: string
  type: string                         # 운영 DBMS 기준
  nullable: bool
  default: any | null

Branch:
  id: string
  method_ref: string                   # "OrderService.placeOrder:142"
  condition_text: string
  condition_ast: any | null
  involved_vars: [string]
  exercised_in_paths: [string]

PropagationInfo:
  scheme: enum [OTEL_BAGGAGE, OTEL_TRACECONTEXT, B3, NONE]
  header_name: string                  # 기본 "baggage"
  key_in_baggage: string               # "test-id"
```

---

## 1. 도구 1 (Graph RAG Builder) Query API

REST 인터페이스. JSON 응답. 페이지네이션은 `?limit=N&cursor=...`.

### 메타
```
GET /version
  → { commit_sha, indexed_at, schema_version }

GET /projects
  → [{ id, build_system, indexed_at, code_version }]
```

### Endpoint 탐색
```
GET /endpoints?project={id}&method={m}&path_prefix={p}
  → [Endpoint]

GET /endpoints/{id}
  → Endpoint + summary {
       explored_path_count,
       total_branches,
       uncovered_branches_count
     }

GET /endpoints/{id}/auth
  → AuthRequirement {
       required: bool,
       roles: [string],
       auth_scheme: enum [FORM, OAUTH2_PWD, OAUTH2_CC, JWT, CUSTOM]
     }
```

### Path 탐색
```
GET /endpoints/{id}/paths
  → [ExploredPath (summary)]

GET /paths/{id}
  → ExploredPath (full) +
    { captured_sql_ids: [...],
      captured_http_ids: [...],
      captured_socket_ids: [...] }

POST /paths/match
  body: { endpoint_id, params: {...} }
  → [{ path_id, match_confidence, missing_constraints: [...] }]
```

### Captured 사실
```
GET /paths/{id}/captured-sql       → [CapturedSQL]
GET /paths/{id}/captured-http      → [CapturedHttpCall]
GET /paths/{id}/captured-socket    → [CapturedSocketIO]

GET /sql/{id}                      → CapturedSQL
GET /http-calls/{id}               → CapturedHttpCall
GET /socket-ios/{id}               → CapturedSocketIO
```

### 스키마 / 격리
```
GET /tables                        → [Table 요약]
GET /tables/{name}                 → Table (full)
GET /tables/{name}/fk-chain        → [Table]
GET /tables/{name}/scope-analysis
  → { scope_reachable: bool,
      reachable_via: [api_param],
      reason_if_not: string }
```

### Branches
```
GET /branches?endpoint_id={id}&exercised={true|false}
  → [Branch]

GET /branches/{id}                 → Branch
```

### Propagation
```
GET /projects/{id}/propagation     → PropagationInfo
```

### 검색 (벡터)
```
POST /search/similar-endpoints
  body: { query_endpoint_id, top_k: 5 }
  → [{ endpoint_id, similarity }]

POST /search/by-stereotype
  body: { stereotype, has_db, has_http, has_socket, top_k }
  → [{ endpoint_id, ... }]
```

### 갱신 트리거
```
POST /index/full                   # 풀빌드 (관리자)
POST /index/incremental
  body: { changed_files: [...], code_version }
GET /index/status                  → { running, progress, last_completed }
```

에러 응답은 RFC 7807 (`application/problem+json`).

---

## 2. 도구 2 (Test Generator) API

### 요청
```
POST /generate
  body: GenerationRequest
  → GenerationResult
```

```yaml
GenerationRequest:
  target:
    endpoint_id: string
    focus:                           # 우선순위 힌트, optional
      cover_paths: [path_id]
      cover_branches: [branch_id]
      scenarios:                     # 자유 텍스트
        - "amount=0 edge"
        - "expired token"
      parameter_overrides:           # 특정 입력 강제
        userId: ["test-user-1"]
        amount: [-1, 0, MAX_INT]
    must_exclude_paths: [path_id]

  existing_artifacts:
    test_files:
      - path: "tests/OrdersPostTest.java"
        covers_paths: [path_id]
        covers_branches: [branch_id]
    coverage_baseline:
      paths_covered: [path_id]
      branches_covered: [branch_id]

  constraints:
    test_style:
      framework: junit5
      assertion_lib: hamcrest        # RestAssured 친화
      rest_client: rest_assured
    runtime:
      java_version: 8 | 11 | 17 | 21 # SUT 기준
      spring_version: 2.x | 3.x      # SUT 기준
    environment:
      db_dialect: postgres-15        # 운영과 동일
      auth_mode: real | disabled
      socket_isolation_policy: best_effort | force_serial
      parallel_execution: enabled | disabled
    id_strategy:
      mode: uuid | namespaced
      length_hint: 8

  budget:
    max_new_tests: int
    max_modified_tests: int
    timeout_seconds: int

  output:
    package: "com.example.tests.orders"
    base_directory: "src/test/java"
```

### 응답
```yaml
GenerationResult:
  status: enum [SUCCESS, PARTIAL, FAILED]

  new_artifacts:
    - path: string
      kind: enum [TEST_CLASS, FIXTURE_DATA, SUPPORTING]
      content: string
      covers_paths: [path_id]
      covers_branches: [branch_id]

  modified_artifacts:
    - path: string
      diff: string                   # unified diff
      reason: string

  coverage_delta:
    paths:
      newly_covered: [path_id]
      still_missing: [path_id]
      reasoning: { path_id: explanation }
    branches:
      newly_covered: [branch_id]
      still_missing: [branch_id]

  parallel_safety_report:
    fully_parallel: [test_name]
    serial_required:
      - test: test_name
        reason: enum [SOCKET_NO_SESSION, ...]
        details: string
    scope_unreachable_tables:
      - test: test_name
        table: string
        recommendation: string

  quality_report:
    weak_assertions: [{test, reason}]
    missing_negative_cases: [{path_id, reason}]
    fixture_warnings: [...]

  rationale:
    chosen_paths:
      - { path_id, why_chosen, captured_sources: [...] }

  recommendations:
    - kind: enum [
        REQUEST_GRAPH_REINDEX,
        COVER_BRANCH_NEXT,
        ADD_AUTH_FIXTURE,
        ADD_PROTOCOL_DECODER,
        SUT_PROPAGATION_MISSING
      ]
      target: any
      message: string

  diagnostics:
    graph_queries_made: int
    template_selections: [...]
    generation_time_ms: int
```

---

## 3. testlib 인터페이스

생성된 테스트 코드가 의존하는 안정적 API. 어댑터로 백엔드 교체 가능.

```java
// === 핵심 진입점 ===
public final class TestScope implements AutoCloseable {
    public final String testId;
    public final HttpMockClient http;
    public final SocketMockClient socket;
    public final JdbcHelper jdbc;
    public final AuthClient auth;
    public final RestAssuredHelper rest;

    public static TestScope create();
    public static TestScope create(String namespacePrefix);

    @Override public void close();
    public void cleanup();
}

// === HTTP mock 어댑터 ===
public interface HttpMockClient {
    StubBuilder stub(String urlPattern);
    void removeAllForScope(String testId);

    interface StubBuilder {
        StubBuilder method(HttpMethod m);
        StubBuilder withQueryParam(String name, Matcher<String> m);
        StubBuilder withHeader(String name, Matcher<String> m);
        StubBuilder withBaggage(String key, String value);
        StubBuilder respondJson(String json);
        StubBuilder respondStatus(int code);
        StubMapping register();
    }
}

// === Socket mock 어댑터 ===
public interface SocketMockClient {
    Session bind(String host, int port);
    void removeSession(String testId);

    interface Session {
        Session withSessionField(String name, String value);
        Session onReceive(byte[] pattern);
        Session onReceiveHex(String hex);
        Session respond(byte[] bytes);
        Session respondHex(String hex);
        Session step(int order);
        SessionHandle register();
    }
}

// === JDBC ===
// 메소드명은 JdbcTemplate 컨벤션을 따름
public final class JdbcHelper {
    public int update(String sql, Object... params);
    public List<Map<String,Object>> query(String sql, Object... params);
    public void updateScoped(String sql, Object... params);  // 대시보드 report 포함
}

// === Auth ===
public interface AuthClient {
    Token login(String username, String password);
    Token clientCredentials(String clientId, String clientSecret);
    Token jwtFor(String subject, Map<String,Object> claims);

    interface Token {
        String bearerHeader();
        String raw();
    }
}

// === RestAssured 통합 ===
public final class RestAssuredHelper {
    public RequestSpecification given();
    public RequestSpecification withBaggage(String k, String v);
    public RequestSpecification withAuth(Token t);
}

// === Dashboard reporter ===
public interface DashboardReporter {
    void report(DashboardEvent e);

    static DashboardReporter fromEnv();
    static DashboardReporter noop();
}

// === 어댑터 SPI ===
public interface HttpMockAdapter { HttpMockClient create(Config c); }
public interface SocketMockAdapter { SocketMockClient create(Config c); }
public interface AuthAdapter { AuthClient create(Config c); }
public interface DashboardAdapter { DashboardReporter create(Config c); }
// java.util.ServiceLoader로 발견
```

### 환경변수 (설정)
```
APP_BASE_URI
JDBC_URL / JDBC_USER / JDBC_PASS
HTTP_MOCK_ADMIN
SOCKET_MOCK_ADMIN
AUTH_BASE_URI
DASHBOARD_URL                       # 없으면 noop
TEST_AUTH_MODE=real|disabled
OTEL_BAGGAGE_KEY=test-id            # 컨벤션
```

---

## 4. 대시보드 이벤트 스키마

```yaml
DashboardEvent:
  event_id: uuid
  type: enum [
    SCOPE_CREATED,
    SCOPE_CLEANED,
    DB_ROW_INSERTED,
    DB_ROW_DELETED,
    HTTP_STUB_REGISTERED,
    HTTP_STUB_REMOVED,
    SOCKET_SESSION_OPENED,
    SOCKET_SESSION_CLOSED,
    AUTH_TOKEN_ISSUED
  ]
  test_id: string
  timestamp: iso8601
  payload: object                    # type별 스키마
```

### payload 정의
```yaml
SCOPE_CREATED.payload:
  test_class: string
  test_method: string
  run_id: string

SCOPE_CLEANED.payload:
  resources_released:
    db_rows: int
    http_stubs: int
    socket_sessions: int

DB_ROW_INSERTED.payload:
  table: string
  key_column: string
  key_value: string

DB_ROW_DELETED.payload:
  table: string
  key_column: string
  key_value: string

HTTP_STUB_REGISTERED.payload:
  stub_id: string
  url_pattern: string
  scope_baggage_value: string

HTTP_STUB_REMOVED.payload:
  stub_id: string

SOCKET_SESSION_OPENED.payload:
  session_id: string
  mock_host: string
  mock_port: int

SOCKET_SESSION_CLOSED.payload:
  session_id: string

AUTH_TOKEN_ISSUED.payload:
  token_kind: string
  expires_at: iso8601 | null
```

### 대시보드 REST API
```
POST /events                       # testlib가 이벤트 발행
  body: DashboardEvent | [DashboardEvent]
  response: 202 Accepted

GET /active                        # 현재 ACTIVE 테스트
GET /test/{test_id}                # 상세
GET /tables/{name}/holders         # 테이블별 row 소유자
GET /leaked                        # 누수 의심
GET /history?since={iso}&until={iso}&test_id={tid}&type={t}
POST /reap/{test_id}               # 수동 reaper 실행
```

### 알람 채널
```
LEAKED 상태 진입 시:
  - 콘솔 로그 (default)
  - SLACK_WEBHOOK_URL 설정 시 Slack
  - WEBHOOK_URL 설정 시 POST
```

---

## 5. 버전 관리

각 API는 명시적 버전 헤더로 관리:

- 도구 1 query API: 응답 헤더 `X-Schema-Version: 1`
- 도구 2 generate API: 응답 `schema_version` 필드
- testlib: Java 모듈 semver (`testlib-api-1.x.y`)
- 대시보드 이벤트: payload 안에 `schema_version`

스키마 미스매치 감지 시 명시적 경고. 어댑터 layer로 흡수.

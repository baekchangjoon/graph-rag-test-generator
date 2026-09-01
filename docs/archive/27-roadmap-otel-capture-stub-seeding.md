# 27 — 로드맵: OTEL SQL 캡처 · 외부 stub seeding · override 키 치환 경고

> **아카이브(2026-09-01 이동).** ① OTEL SQL 캡처는 2026-06-18 구현 완료([CHANGELOG](../../CHANGELOG.md)).
> ②·③은 미착수 — 착수 시 이 문서를 spec의 출발점으로 쓴다. 현재 상태는 [docs/09](../09-implementation-roadmap.md).

착수 전 "앞으로 할 일" 3건. 각 항목은 실제 착수 시점에 각자 `spec → plan`으로 확장한다(이 문서는
방향·통합 지점·리스크를 못 박는 로드맵이지, 구현 스펙이 아니다). 우선순위 순서: ① OTEL, ② stub
seeding, ③ JAVA_TOOL_OPTIONS 치환 경고.

각 항목은 *문제 → 제안 방향 → 통합 지점 → 핵심 결정·리스크·오픈 질문* 으로 적는다.

---

## 1. OTEL Agent v2.16.0 기반 SQL/bind 캡처 + test-id 귀속 (교체 가능, 폴백 보유)

> **상태: 구현 완료 (2026-06-18).** spec [docs/superpowers/specs/2026-06-18-otel-sql-capture-design.md],
> plan [docs/superpowers/plans/2026-06-18-otel-sql-capture.md]. `SqlCaptureBackend`(`OtelSpanCapture`
> 1순위 + `LogParserCapture` 폴백), in-process OTLP/protobuf 리시버, 요청별 `traceparent` 주입(HTTP 헤더 /
> Kafka 레코드 헤더)으로 trace-id 귀속. 기본값을 `otel`로 전환했고 `--trace-mode none` 으로 폴백한다
> ([docs/06](06-test-environment.md) "trace 모드" 절, [docs/26](26-attach-mode.md) "attach OTEL 네트워킹" 절).
>
> **PoC·구현에서 확정/정정된 사실:**
> - **SQL 텍스트 속성** — agent 2.16.0은 stable DB semconv opt-in 없이는 SQL을 **`db.statement`(구 키)**로
>   내보낸다(`db.system=postgresql/h2`, `db.operation` 등도 구 semconv). 아래 "검증된 사실"이 가정한
>   `db.query.text`(신규 키)는 opt-in 시에만 나온다. 빌더는 **둘 다 읽는다**(신규→구). 바인딩만은 신규 키
>   `db.query.parameter.<index>`(0-based)로 정상 노출된다.
> - **상관 방식** — HTTP는 server-entry span, Kafka consumer는 process span이 **주입 span의 child**(같은
>   trace-id)가 된다(link 아님). 빌더는 entry span(parent==주입 spanId) 도착을 await 후 그 trace의 DB span을
>   환원한다. 동시 요청은 trace-id로 격리 귀속된다.
> - **OTLP transport** — agent OTLP exporter는 `http/json` 미지원 → 리시버는 `http/protobuf` 로 수신.
> - **attach 보안** — 컨테이너 도달을 위해 리시버를 `0.0.0.0` 에 bind하고, 넓어진 노출은 실행마다 1회용
>   토큰 헤더 인증으로 보상한다([docs/26](26-attach-mode.md) "attach OTEL 네트워킹" 절).

### 문제

현재 SQL/바인딩 캡처는 SUT가 stdout에 남기는 **Hibernate/MyBatis 로그 파싱**(`SqlLogParser` —
`org.hibernate.SQL=DEBUG` + `org.hibernate.orm.jdbc.bind=TRACE`)이고, 요청 귀속은 **로그 byte-offset
구간**(`SutProcess.logOffset()`/`readLogRange()`)으로 한다. 한계:

- **동시성 취약** — byte-offset 창은 단일 직렬 실행을 전제한다. 병렬 요청/비동기 작업이 끼면 구간이 섞인다.
- **로그 접근 의존** — attach 모드는 컨테이너 로그를 `docker compose logs` 스트림으로 끌어와야만 SQL을
  본다([docs/26](26-attach-mode.md)). 로그 포맷/버퍼링에 민감하다.
- **ORM 결합** — Hibernate/MyBatis 로그 형식별 파서가 필요하다(raw JDBC 등은 비대상).

### 제안 방향

SQL 캡처를 **교체 가능한 인터페이스**(`InputOracle` 선례 — [docs/24](24-exploration-backends-and-input-oracle.md))로
추상화한다.

```
interface SqlCaptureBackend {
    // 요청 1건(고유 test-id)이 유발한 SQL + 바인딩을 반환
    List<CapturedSql> capture(String testId, <요청 경계>);
}
```

- **`OtelSpanCapture` (1순위)** — 빌더가 **in-process OTLP 리시버**를 띄우고, SUT의 **OTEL Java agent
  v2.16.0** 이 내보내는 **DB span**(SQL + bind 값)을 수신한다. 요청별 **고유 test-id** 를 발급해 SQL을
  그 요청/생성-테스트에 정확히 귀속한다.
- **`LogParserCapture` (폴백)** — 기존 `SqlLogParser` 경로. v2.16.0 bind-값 노출이 환경/드라이버에서
  실패하거나 batch 등 미수집 케이스에서 폴백.
- 검증 후 기본값을 OTEL로 전환한다. **attach 모드의 SQL 채널도 이 backend 로 대체**(로그 스트림 불요).

#### 검증된 사실 (OTEL Java agent / semconv)

- 플래그: **`otel.instrumentation.jdbc.experimental.capture-query-parameters=true`** (experimental) →
  prepared statement 바인딩을 span 속성으로 노출.
- 출력 포맷: `db.query.text` 는 `?` 로 유지되고, 바인딩은 **별도 속성** —
  **`db.query.parameter.<key>`**, `<key>` 는 **0-based 위치 인덱스**, **값 타입 String**.
  예: `INSERT ... VALUES (?, ?)` + 값 `'a', 7` → `db.query.parameter.0="a"`, `db.query.parameter.1="7"`.

#### 검증된 제약 (설계에 직접 영향)

1. **sanitizer 관계** — `capture-query-parameters` 를 활성화하면 해당 JDBC instrumentation의
   **sanitizer가 자동 비활성화된다**(JDBC instrumentation README 기준 — 상호 배타). 즉 별도 sanitizer
   토글 로직은 불필요하다. **PoC는** sanitizer 조합 자체가 아니라 **driver별 실제 값 노출 여부 + batch
   처리(아래 2번)** 확인에 집중한다.
2. **batch 연산 미수집** — semconv상 **batch 연산에는 파라미터를 싣지 않는다.** Hibernate batch insert/update
   는 bind 값이 비므로 → **폴백 유지 근거** + 분석 중 JDBC batch 비활성 고려.
3. **컬럼 매핑은 SQL 텍스트 파싱 계속** — `db.query.text` 는 `?` 라 position→column/table/kind 매핑은
   기존 파싱 로직을 재사용한다. OTEL은 **bind 값 소스 + 귀속(test-id)만** 대체한다.

### 통합 지점

- **버전 선행** — `gradle/libs.versions.toml` 의 `otelAgent` 가 현재 **`2.14.0`** 이다. **`2.16.0` 으로
  bump** 가 1단계 선행 작업이고, `graph-rag-builder` 리소스 번들/`e2e` copy 산출물이 새 agent를
  쓰는지 PoC에서 확인한다.
- `graph-rag-builder/.../capture/SqlLogParser.java` → 폴백 구현으로 인터페이스 뒤로 이동.
- `graph-rag-builder/.../coverage/OtelAgent.java` → 현재 `OTEL_TRACES_EXPORTER=none`(baggage 전파만).
  OTEL backend 시 **`otlp` 로 전환** + `OTEL_EXPORTER_OTLP_ENDPOINT`(빌더 리시버)·protocol·`capture-query-parameters`
  활성. `none` 은 폴백(log-parser) 모드에서만 유지.
- `EndpointExplorationRunner.doSend` 의 baggage — 현재 `test-id=explore` 상수 → **요청/path별 고유값**.
- attach: `AttachedComposeEnvironment` 의 로그-스트림 SQL 경로를 OTEL backend 로 교체 가능.

### 핵심 결정·리스크·오픈 질문

- **test-id ↔ SQL span 상관 방식**: (a) **trace-id 상관**(권장) — 빌더가 요청별 고유 trace를 만들어
  **W3C `traceparent` 를 outbound로 주입**하고, in-process 리시버가 그 trace-id의 DB span만 묶는다.
  ("baggage 의존 없음"은 커스텀 baggage 키가 불필요하다는 뜻일 뿐, **W3C trace 전파는 필요** — 빌더가
  trace 생산자가 된다.) vs (b) baggage→span 속성 복사(OTEL 실험 기능/커스텀 SpanProcessor).
- **test-id 고유화의 파급** — `test-id` 를 요청별 고유값으로 바꾸면, `HttpCaptureServer.drainNewExchanges()`
  의 baggage 매칭(현재 `test-id=` **존재 여부만** 확인)과 생성 테스트 stub 귀속을 **값 매칭으로 승급**해야
  한다(항목 2와의 의존).
- **리시버 배선**: 빌더 in-process OTLP(gRPC/HTTP) 수신기 — 의존성·동적 포트·start/stop 순서. **attach 시**
  컨테이너 SUT가 호스트 리시버에 도달해야 하므로 `host.docker.internal`(Mac/Win) 또는 Linux `extra_hosts`
  전략 필요.
- **결정성(async)** — span은 OTLP로 **비동기** 도착한다. 고정 sleep은 flaky → **test-id별 기대 span
  완료를 폴링/await**(타임아웃) 하는 동기화 전략을 spec에서 정한다.
- **에이전트 오버헤드·런타임 토글** — Java 에이전트 attach는 JVM 시작 시점 결정이라 런타임 제거는
  불가하다. 단 우리 설계에선 **빌더가 SUT를 띄울 때만** `JAVA_TOOL_OPTIONS` 로 주입하므로 분석 런에만
  붙는다(평상시 부재). 떠 있는 JVM 내 **요청별 on/off** 는 **parent-based sampling + 빌더의 `traceparent`
  주입**으로 — 추적된 요청만 span 생성·export, 그 외 트래픽은 0(오버헤드 무시 가능). exporter/instrumentation
  설정 플립은 startup-read라 라이브 변경 불가(재시작 필요).
- **v2.16.0 PoC** 가 1단계 게이트(버전 bump + driver별 값 노출 + batch). 실패 시 폴백 유지, 기본값 전환 보류.

---

## 2. OpenAPI 기반 외부 stub seeding + 커버리지 유도형 응답 fuzzing

> **상태(2026-06-23): 무-LLM 1순위 = 단계1로 커버됨.** 운영자 수동 stub 작성((가))을 없애는
> **무-LLM·무-OpenAPI 형상-only 합성**은 [단계1](superpowers/specs/2026-06-23-stage1-external-stub-synthesis-design.md)에서
> 구현·검증 완료(REQ-001~014 100% green). SUT 응답 DTO 형상에서 minimal valid 200 응답을 결정적으로
> 합성해 외부-의존 경로(예: order-service `GET /inventory/stock` → 외부 직후 409 분기)를 통과시킨다.
> 따라서 본 §2의 나머지 — **OpenAPI 기반 합성**은 **단계3**, **커버리지 유도형 응답 값 fuzzing**(enum/상태코드
> 전환 등 외부-응답-의존 분기 전체 열기)은 **단계2**로 이연한다. 수동 `--external-stubs`는 escape hatch로 유지.
>
> **상태(2026-06-24): 단계2 = enum 응답 변형 완료(PR #92).** enum 타입 응답 필드의 정적-완전 상수 집합을
> 변형 stub으로 갈아끼워 모든 arm을 결정적으로 연다([설계](superpowers/specs/2026-06-24-stage2-enum-response-fuzzing-design.md),
> REQ-001~011 green). **단계2-A = status-style String 리터럴 변형**은 그 변형 루프 위에 후보 출처만 교체해
> (소비 코드 equals-family 분기 리터럴 추출) String 응답 분기의 arm을 연다
> ([설계](superpowers/specs/2026-06-24-stage2-string-literal-response-fuzzing-design.md), REQ-001~012 green).
> 남은 후속: **concolic 숫자 경계(단계2-B)**, **OpenAPI/LLM(단계3)**.

### 문제

SUT가 외부 HTTP 의존(결제·재고 등)을 호출할 때:

- **(가)** 그 응답을 stub으로 채워야 탐색이 외부-의존 경로를 지나간다. 현재는 `--external-stubs <dir>` 로
  **운영자가 WireMock mapping JSON을 수동 작성**한다(`HttpCaptureServer.loadStubs`). 손이 많이 간다.
- **"외부 응답을 받은 이후"의 SUT 분기가 미탐색** — 빌더는 SUT의 **요청 입력**만 fuzzing하고, 외부
  의존이 돌려주는 **응답**은 고정(수동 stub 1종)이라, 응답 값/상태에 따라 갈리는 분기를 못 연다.

> **(다) 캡처→생성테스트 stub 은 이미 있음** — 탐색 중 외부 교환을 캡처(`drainNewExchanges` →
> `CapturedHttpCall`)해 그래프에 넣고, 생성 테스트가 `scope.http().stub(...)`(testId metadata + baggage
> 매칭)으로 재현한다. 이 항목은 **(가) 자동화 + 응답 fuzzing** 에 집중한다.

### 제안 방향

수동 stub 대신 **외부 서비스의 `openapi.json`** 을 입력으로 받아 stub 응답을 자동 합성하고, **커버리지
유도형**으로 외부 응답을 변형해 외부-응답-의존 분기를 연다(SUT 입력 fuzzing과 동일 철학).

1. OpenAPI **응답 스키마**(operation별 2xx/4xx/5xx, 모델 schema)에서 **baseline 유효 응답** 합성.
2. **변형 생성**: 필드값 경계·enum 변종·nullable 누락·타입 변형·**상태코드 전환(2xx↔4xx/5xx)**.
3. **커버리지 유도 루프**: 각 변형을 WireMock stub으로 갈아끼우고 호출 → **새 SUT 분기를 연 변형만**
   시드 큐에 환류(입력 fuzzer와 같은 arm-aware 지문 + per-request JaCoCo delta). SUT 입력 × 외부 응답을
   **공동 budget** 안에서 탐색.
4. **보존된 변형 = 생성 테스트 stub** — 분기를 연 외부 응답이 (다) 경로를 통해 그 path의 stub으로 굳는다.

수동 `--external-stubs` 는 **escape hatch 로 유지**(OpenAPI 없는 서비스/예외 케이스).

> **범위: analysis 모드 우선.** `HttpCaptureServer`(임베디드 WireMock)·`{{wiremock}}` 치환은
> `AnalysisEnvironment` 에 있고, **attach v1은 외부 HTTP 캡처를 미지원**(`AttachedComposeEnvironment.httpCapture()`
> = null, [docs/26](26-attach-mode.md))한다. 따라서 이 항목은 analysis 모드 대상이다. attach까지 넓히려면
> compose 내부 WireMock 서비스 주입 + `{{wiremock}}` 치환 재설계가 **별도 범위**로 필요하다.

### 통합 지점

- **입력** — 외부 OpenAPI 스펙 공급 경로 신설(예: `--external-openapi <dir|spec[,svc=spec]>`). 다중
  외부 서비스 매핑(서비스↔base URL/`{{wiremock}}` 키) 포함.
- `HttpCaptureServer` — 현재 stub 로드/캡처. **OpenAPI→stub 합성기**와 변형 stub 교체(register/reset) 추가.
- `CoverageGuidedFuzzer` / `ExplorationOrchestrator` — 유도 루프에 응답 변형 편입(아래 오픈 질문의 추상화 필요).
- `--sut-env KEY={{wiremock}}` — 외부 URL을 분석 WireMock으로 redirect(기존).
- `CapturedHttpCall` → 생성 테스트 stub 경로(기존, 재사용).

### 핵심 결정·리스크·오픈 질문

- **fuzzer 추상화 (3-모델 합의 — 가장 큰 설계 결정)**: 현재 `CoverageGuidedFuzzer`/`PathExplorer.explore(EndpointTarget,…)`/
  `InputMutator.forTarget`/`EndpointInvoker` 는 **SUT 입력(JsonNode body) 변이 전용**이다. WireMock stub 교체는
  다른 축이라 같은 루프에 직접 못 넣는다. **새 좌표(`ExternalResponseVariant`)를 seed queue·tried-key·
  `PathCandidate`/`CapturedHttpCall` 에 포함하거나, 입력×응답을 단일 Target으로 일반화**해야 한다 — spec 단계
  핵심 결정.
- **공동 budget**: `requestMutationBudget` 와 `responseVariantBudget` 의 소비 규칙(우선순위: 상태코드/에러
  경로 먼저?)로 구체화.
- **stub 교체 ↔ drain 순서**: `HttpCaptureServer.drainNewExchanges()` 는 누적 `drainedCount` 기반이다. 변형
  교체 시 교환이 섞이지 않게 **교체 전 drain → 교체 → 재 drain** 프로토콜을 명시.
- **외부 호출 ↔ OpenAPI operation 매칭**: path 템플릿(`/items/{id}`)·method·query 매칭 규칙.
- **결정성**: 변형은 **시드 기반 결정적** 생성(프로젝트의 no-LLM·재현성 원칙). `index`로 변형 다양화.
- **다중 외부 서비스 / 체인 호출**: 여러 의존, 한 path에서 다중 외부 호출 시 변형 좌표계.
- **응답 ↔ DB 시드 정합**(향후): 외부 응답의 id가 DB 시드 엔티티와 일치해야 하는 경로 — 이번 범위 밖.

---

## 3. override의 `SPRING_APPLICATION_JSON` / `JAVA_TOOL_OPTIONS` 치환 — 감지 + 경고

### 문제

attach 모드의 `OverrideComposeGenerator` 는 app 서비스에 `SPRING_APPLICATION_JSON`(SQL 로깅)과
`JAVA_TOOL_OPTIONS`(에이전트)를 **set** 한다. docker compose는 `environment` 의 **스칼라 값을 머지하지
않고 치환**하므로, 사용자 compose의 app 서비스가 같은 키를 이미 쓰고 있으면 **조용히 소실**된다
([docs/26](26-attach-mode.md) v1 한계).

> **흔한 셋업은 무해** — SUT 설정을 `--spring.config.location=file:...` (Dockerfile ENTRYPOINT args)나
> `SPRING_CONFIG_LOCATION` env, compose `command:` 로 주입하는 경우, override는 `environment` 키만 더하고
> ENTRYPOINT/command를 건드리지 않으므로 충돌이 없다. 우리 `SPRING_APPLICATION_JSON`(로깅)은 Spring
> 우선순위상 설정 파일보다 높아 **같은 키는 덮어쓴다** — 단 로깅 전용 키(`hibernate.SQL`, `jdbc.bind`)는
> 사용자 yml에 거의 없어 실질 충돌이 없고 yml의 앱 설정은 그대로 로드된다. **`SPRING_APPLICATION_JSON`
> env / `JAVA_TOOL_OPTIONS` env 를 SUT가 직접 쓰는 경우에만** 치환이 문제된다.

### 제안 방향 (B — 감지+경고)

override 생성 시 **사용자 compose의 app 서비스가 `SPRING_APPLICATION_JSON`/`JAVA_TOOL_OPTIONS` 를 이미
설정했는지 감지**하고, 덮어쓰게 되면 **조용한 치환 대신 명확히 경고(또는 실패)** 한다. 사용자는 그 설정을
개별 env로 옮기거나 충돌을 인지한 채 진행한다.

딥머지(SAJ JSON object 머지 + JTO 문자열 append)는 **실제 수요가 생기면 그때 승급**한다 — 흔한 셋업이
무해한 상황에 미리 머지를 짓는 건 과투자다.

### 통합 지점

- `OverrideComposeGenerator` / `BuilderCli.runAttached` — override 작성 전 사용자 compose의 app 서비스
  `environment` 읽기 + 키 존재 감지 → 경고/실패. env의 **map/list 두 포맷** 파싱은 `ComposeInspector.envValue`
  의 YAML 처리 패턴을 재사용(예: `ComposeInspector` 에 `readServiceEnv(service)` 공개 메서드 추가).

### 핵심 결정·리스크·오픈 질문

- **기본 동작 (제안)**: 기본은 **경고 후 진행**, `--strict-compose-env` 시 **실패**. (로드맵 단계에서 기본값을
  하나로 못 박아 후속 plan의 CLI UX·테스트 기대값이 갈리지 않게 한다.)
- 키 감지에는 `${VAR}` 보간 값 해석이 불필요(키 존재만 확인).
- 승급 트리거: 딥머지 수요가 확인되면 A(키별 적절 머지 — SAJ JSON 딥머지 + JTO append)로.

---

## 출처 (OTEL 검증)

- semconv DB spans — <https://opentelemetry.io/docs/specs/semconv/db/database-spans/>
- OTel Java agent instrumentation config — <https://opentelemetry.io/docs/zero-code/java/agent/instrumentation/>
- 바인딩 캡처 이슈 — <https://github.com/open-telemetry/opentelemetry-java-instrumentation/issues/11724>

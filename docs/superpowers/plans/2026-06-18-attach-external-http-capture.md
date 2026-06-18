# attach 모드 외부 HTTP 캡처 배선 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`).

**Goal:** attach 모드(컨테이너 SUT 분석)에서 SUT의 외부 HTTP 호출을 호스트 임베디드 WireMock으로 받아 `CapturedHttpCall`로 캡처한다(`--sql-capture` 무관 항상). 호스트 WireMock은 0.0.0.0 bind + per-run 토큰(경로 접두사)으로 보호하되, 토큰은 캡처 데이터에 남기지 않는다.

**Architecture:** OTLP 리시버 attach 배선을 미러링 — `runAttached`가 호스트 `HttpCaptureServer`(WireMock)를 토큰+외부stub으로 시작, `{{wiremock}}` placeholder를 `host.docker.internal:<port>/<token>`로 치환해 override app env에 주입, `extra_hosts host-gateway`를 attach 항상 추가, env가 서버 소유·`httpCapture()` 노출. WireMock `RequestFilterV2`가 토큰 접두사를 검증(401)·strip(사용자 stub 토큰 무관 매칭); `drainNewExchanges`는 빌더가 아는 토큰으로 접두사를 직접 제거해 깨끗한 `CapturedHttpCall.urlPath`를 기록 → 기존 Phase 2 목 파이프라인(test-generator `HttpMockComposer` → testlib `scope.http().stub`)이 그대로 활용.

**Tech Stack:** Java 17, Gradle, JUnit 5, AssertJ, WireMock 3.13(`RequestFilterV2`/`RequestWrapper`), Testcontainers, docker compose.

**Spec:** [docs/superpowers/specs/2026-06-18-attach-external-http-capture-design.md](../specs/2026-06-18-attach-external-http-capture-design.md)

**검증된 사실:** `HttpCaptureServer`는 WireMock `dynamicPort`(기본 0.0.0.0 bind). `drainNewExchanges`가 `event.getRequest().getUrl()`(원본 URL)을 기록 → 토큰 strip은 drain에서 직접. `EndpointExplorationRunner`는 `httpCapture==null?List.of():drainNewExchanges()`(attach가 non-null이면 자동 캡처). `BuilderCli.runAttached`에 OtlpReceiver 배선 선례(start("0.0.0.0",secret)+hostEndpoint+warnIfHostGatewayUnsupported+try-with-resources env). `AttachedComposeEnvironment` 2-arg/3-arg(otlpReceiver) ctor 존재. test-generator `HttpMockComposer`+testlib `WireMockHttpMockClient`는 무변경(소비 경로).

---

## File Structure

- **Modify** `.../env/HttpCaptureServer.java` — token-aware start(RequestFilterV2: 접두사 401/strip) + `port()`/`hostBaseUrl()` + drain 토큰 strip.
- **Modify** `.../env/AttachedComposeEnvironment.java` — 4-arg ctor(OtlpReceiver+HttpCaptureServer 둘 다 소유), `httpCapture()` non-null, close 시 둘 다 stop.
- **Modify** `.../env/ExplorationEnvironment.java` — `httpCapture()` 주석 갱신.
- **Modify** `.../cli/BuilderCli.java` runAttached — 호스트 WireMock(token+stubs) 시작, `{{wiremock}}`→hostBaseUrl 치환을 Spec.extraEnv에, addHostGateway 항상 true, warnIfHostGatewayUnsupported otel 밖으로, 시작 후 실패 정리(try/finally), 4-arg env.
- **Create** `e2e/run-attach-ext-http-e2e.sh` — 수용(컨테이너→호스트 inventory 캡처).
- **Modify** `docs/26-attach-mode.md`(한계 #3 제거 + 외부 HTTP 캡처/토큰/Docker 20.10+), `docs/decisions/mock-services-security-model.md`(attach 0.0.0.0+토큰 예외).
- **Test** `HttpCaptureServerTokenTest.java`(토큰 필터·strip·drain), `OverrideComposeGeneratorTest`(attach host-gateway 항상) 보강.

---

## Phase 1 — E2E 수용 (outer loop, red)

### Task 1: attach 외부 HTTP 캡처 수용 스크립트 (red)

**Files:** Create `e2e/run-attach-ext-http-e2e.sh`

- [ ] **Step 1: 스크립트 작성** — `e2e/run-attach-e2e.sh`를 본떠(같은 docker-compose.yml/override 패턴), 단 PROJECT=`grb-attach-order-exthttp`, app-port `58081`, jacoco-port `16301`(기존 e2e와 충돌 회피), 그리고 빌더 attach 호출에 `--external-stubs $ROOT/e2e/external-stubs --sut-env EXTERNAL_INVENTORY_URL={{wiremock}}` 추가. 빌더 로그를 파일로 tee. 검증(python):
```python
import json,sys,os
out=sys.argv[1]
g=json.load(open(os.path.join(out,"graph.json")))
http=g.get("httpCalls", [])
inv=[c for c in http if "inventory" in (c.get("urlPath") or "")]
assert inv, "no external inventory HTTP captured in attach mode"
# 토큰 누출 금지: 캡처 urlPath에 토큰(32+ hex 세그먼트)이 없어야 함
import re
assert not any(re.search(r"/[0-9a-f]{16,}/", c["urlPath"]) for c in inv), "token leaked into captured urlPath"
print("OK external http captured:", [c["urlPath"] for c in inv][:3])
```
그리고 빌더 로그에 `analysis wiremock`(또는 attach wiremock) 활성 + teardown 후 잔여 컨테이너 0 검증(run-attach-e2e.sh의 cleanup 패턴 재사용). `chmod +x`.

- [ ] **Step 2: red 확인** — Run: `bash e2e/run-attach-ext-http-e2e.sh`
Expected: FAIL — `no external inventory HTTP captured`(현재 attach `httpCapture()=null` → httpCalls 비어 있음).

- [ ] **Step 3: Commit** — `git commit -m "test(e2e): attach external HTTP capture acceptance (red)"` (트레일러: Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>).

> Docker 무거운 outer loop. red 한 번 확인 후 Phase 2~3은 inner-loop 단위 TDD로, Task 5에서 green 재실행.

---

## Phase 2 — HttpCaptureServer 토큰 + host URL

### Task 2: HttpCaptureServer token filter + hostBaseUrl + drain strip

**Files:** Modify `graph-rag-builder/src/main/java/io/graphrag/builder/env/HttpCaptureServer.java`; Test `graph-rag-builder/src/test/java/io/graphrag/builder/env/HttpCaptureServerTokenTest.java`

- [ ] **Step 1: 실패 테스트**
```java
package io.graphrag.builder.env;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class HttpCaptureServerTokenTest {
    private HttpCaptureServer server;

    @AfterEach void tearDown() { if (server != null) server.close(); }

    private static Path stubDir() throws Exception {
        Path d = Files.createTempDirectory("stubs");
        Files.writeString(d.resolve("inv.json"), """
            {"request":{"method":"GET","urlPath":"/inventory/stock"},
             "response":{"status":200,"jsonBody":{"available":50}}}""");
        return d;
    }
    private static int get(String url) throws Exception {
        return HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test void withToken_rejectsMissingPrefix_servesWithPrefix_drainStripsToken() throws Exception {
        server = new HttpCaptureServer();
        server.start(stubDir(), "tok123");                 // token 모드
        String base = "http://127.0.0.1:" + server.port();
        // 토큰 없는 경로 → 401 (보안)
        assertThat(get(base + "/inventory/stock?type=EXPRESS")).isEqualTo(401);
        // 토큰 접두사 경로 → stub 매칭 200 (사용자 stub은 토큰 무관)
        assertThat(get(base + "/tok123/inventory/stock?type=EXPRESS")).isEqualTo(200);
        // 드레인된 urlPath는 토큰 제거된 깨끗한 경로
        var ex = server.drainNewExchanges();
        assertThat(ex).isNotEmpty();
        assertThat(ex.get(0).urlPath()).isEqualTo("/inventory/stock");   // /tok123 없음
        assertThat(ex.get(0).status()).isEqualTo(200);
    }

    @Test void hostBaseUrl_includesHostGatewayAndToken() throws Exception {
        server = new HttpCaptureServer();
        server.start(null, "tok123");
        assertThat(server.hostBaseUrl()).isEqualTo("http://host.docker.internal:" + server.port() + "/tok123");
    }

    @Test void noToken_servesDirectly_analysisMode() throws Exception {
        server = new HttpCaptureServer();
        server.start(stubDir(), null);                     // analysis(무토큰)
        assertThat(get("http://127.0.0.1:" + server.port() + "/inventory/stock")).isEqualTo(200);
        assertThat(server.drainNewExchanges().get(0).urlPath()).isEqualTo("/inventory/stock");
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew :graph-rag-builder:test --tests '*HttpCaptureServerTokenTest*'`
Expected: FAIL — `start(Path,String)`/`port`/`hostBaseUrl` 없음.

- [ ] **Step 3: 구현** — `HttpCaptureServer` 수정:
  - 필드 `private String authToken;`. 기존 `start(Path)`는 `start(stubsDir, null)`로 위임.
  - 새 `public void start(Path stubsDir, String authToken)`:
    - `this.authToken = authToken;`
    - WireMock 구성: `WireMockConfiguration.options().dynamicPort()` (기본 0.0.0.0 bind). authToken != null이면 `.extensions(new TokenPrefixFilter(authToken))`로 RequestFilter 등록. (extensions는 server 생성 시점 옵션이므로, `server`를 final 필드가 아니라 start에서 생성하도록 변경 — 또는 생성자에서 authToken을 받게. 구현 편의상 `server`를 start에서 생성: 필드를 `private WireMockServer server;`로 바꾸고 생성자는 비움, start에서 options 구성.)
    - `server.start(); loadStubs(stubsDir);`
  - `public int port() { return server.port(); }`
  - `public String hostBaseUrl()` — `"http://host.docker.internal:" + port() + (authToken == null ? "" : "/" + authToken)`.
  - `drainNewExchanges`: url 기록부 `event.getRequest().getUrl().split("\\?")[0]`를, authToken != null이면 선행 `"/" + authToken` 접두사를 제거하도록 변경:
```java
    String path = event.getRequest().getUrl().split("\\?")[0];
    if (authToken != null) {
        String prefix = "/" + authToken;
        if (path.equals(prefix)) path = "/";
        else if (path.startsWith(prefix + "/")) path = path.substring(prefix.length());
    }
    // ... new RawHttpExchange(method, path, query, ...)
```
  - **TokenPrefixFilter**(내부/별도 클래스, `StubRequestFilterV2` 구현):
```java
    private static final class TokenPrefixFilter implements
            com.github.tomakehurst.wiremock.extension.requestfilter.StubRequestFilterV2 {
        private final String prefix;   // "/<token>"
        TokenPrefixFilter(String token) { this.prefix = "/" + token; }
        @Override public String getName() { return "grb-token-prefix"; }
        @Override public com.github.tomakehurst.wiremock.extension.requestfilter.RequestFilterAction filter(
                com.github.tomakehurst.wiremock.http.Request request,
                com.github.tomakehurst.wiremock.stubbing.ServeEvent serveEvent) {
            String url = request.getUrl();
            boolean ok = url.equals(prefix) || url.startsWith(prefix + "/") || url.startsWith(prefix + "?");
            if (!ok) {
                return com.github.tomakehurst.wiremock.extension.requestfilter.RequestFilterAction.stopWith(
                        com.github.tomakehurst.wiremock.http.ResponseDefinition.notAuthorised());  // 401
            }
            String stripped = url.substring(prefix.length());
            if (stripped.isEmpty() || stripped.charAt(0) != '/' ) stripped = "/" + stripped;
            com.github.tomakehurst.wiremock.http.Request wrapped =
                com.github.tomakehurst.wiremock.extension.requestfilter.RequestWrapper.create()
                    .transformAbsoluteRequestUrl(u -> u)   // 상대 URL만 바꾸려면 아래 사용
                    .wrap(request);
            // 실제 URL rewrite: RequestWrapper.Builder의 URL 변환 메서드로 prefix 제거.
            // (WireMock 3.13 정확한 메서드명은 구현 시 확인 — setUrl/transformRequestUrl 등. 목표:
            //  필터 이후 stub 매칭이 prefix 없는 url로 이뤄지게 한다.)
            return com.github.tomakehurst.wiremock.extension.requestfilter.RequestFilterAction.continueWith(wrapped);
        }
    }
```
  > 구현 주의: `RequestWrapper`로 URL의 토큰 접두사를 제거해 `continueWith`해야 사용자 stub(토큰 무관)이 매칭된다. WireMock 3.13의 정확한 URL 변환 API(예: `RequestWrapper.Builder`의 url 변환 메서드)는 구현 시 확인하고, 단위 테스트(Step 1의 200 매칭)가 이를 검증한다. `ResponseDefinition.notAuthorised()` 명칭도 버전 확인(없으면 `new ResponseDefinitionBuilder().withStatus(401).build()`).

- [ ] **Step 4: 통과 확인** — Run: `./gradlew :graph-rag-builder:test --tests '*HttpCaptureServerTokenTest*'`
Expected: PASS (3 tests). 기존 `HttpCaptureServer` 사용처(analysis) 회귀: `./gradlew :graph-rag-builder:test --tests '*BuilderE2eTest*'`는 Docker 필요 — 최소 `:graph-rag-builder:compileJava`로 컴파일 확인.

- [ ] **Step 5: Commit** — `feat(env): HttpCaptureServer per-run token (path-prefix filter + drain strip) + hostBaseUrl`.

---

## Phase 3 — env 소유 + runAttached 배선

### Task 3: AttachedComposeEnvironment가 HttpCaptureServer 소유

**Files:** Modify `AttachedComposeEnvironment.java`, `ExplorationEnvironment.java`

- [ ] **Step 1: 4-arg 생성자 + httpCapture + close**
  - 필드 추가: `private final HttpCaptureServer httpCapture;`
  - 기존 `(Config, DbType)` → `this(config, dbType, null, null)`; 기존 `(Config, DbType, OtlpTraceReceiver)` → `this(config, dbType, otlpReceiver, null)`; 신규 `(Config, DbType, OtlpTraceReceiver, HttpCaptureServer)`가 둘 다 보관.
  - `@Override public HttpCaptureServer httpCapture() { return httpCapture; }` (기존 `return null` 교체).
  - `close()`에 `if (httpCapture != null) { httpCapture.close(); }` 추가(otlpReceiver stop 옆).
- [ ] **Step 2: ExplorationEnvironment 주석** — `HttpCaptureServer httpCapture();      // nullable (attach v1 → null)` → `// nullable — attach는 --external-stubs/--sut-env 배선 시 non-null`.
- [ ] **Step 3: 컴파일** — Run: `./gradlew :graph-rag-builder:compileJava` → SUCCESS(호출부 runAttached는 Task 4에서 갱신; 그 전엔 3-arg ctor 그대로라 OK).
- [ ] **Step 4: Commit** — `feat(env): AttachedComposeEnvironment owns HttpCaptureServer (httpCapture non-null)`.

### Task 4: BuilderCli.runAttached 배선

**Files:** Modify `BuilderCli.java` (runAttached)

- [ ] **Step 1: 구현** — runAttached 수정(현 otlp 배선 블록 전후):
  - `warnIfHostGatewayUnsupported();`를 **otel `if` 블록 밖**으로 빼서 attach 시작부에서 1회 호출(host-gateway가 외부 HTTP에도 필요).
  - 호스트 WireMock 시작(override YAML 생성 전):
```java
        String httpToken = newOtlpSecret();   // per-run 토큰(공용 생성기)
        HttpCaptureServer httpCapture = new HttpCaptureServer();
        httpCapture.start(config.externalStubsDir(), httpToken);
        // {{wiremock}} → host.docker.internal:<port>/<token>
        java.util.Map<String, String> sutEnv = new java.util.LinkedHashMap<>();
        config.sutEnv().forEach((k, v) -> sutEnv.put(k,
                v.replace(io.graphrag.builder.env.AnalysisEnvironment.WIREMOCK_PLACEHOLDER, httpCapture.hostBaseUrl())));
```
  - override `Spec.extraEnv`에 들어갈 env를 `otelEnv + sutEnv` 병합(LinkedHashMap에 둘 다 put). `addHostGateway`/`disableBatch` 인자: addHostGateway는 **항상 true**(`new OverrideComposeGenerator.Spec(..., mergedEnv, true, otelSqlCapture)`); disableBatch는 기존대로 otelSqlCapture.
  - env 생성: `new AttachedComposeEnvironment(envCfg, config.dbConfig().type(), otlpReceiver, httpCapture)`.
  - **실패 정리**: httpCapture(및 otlpReceiver) 시작 이후 try-with-resources 진입 전 예외 시 stop. try-with-resources가 둘을 env로 넘기므로, 위험 구간은 `httpCapture.start(...)` ~ `new AttachedComposeEnvironment(...)` 사이(override 생성/쓰기). 이 구간을 try/catch로 감싸 예외 시 `httpCapture.close()`(+otlpReceiver.stop()) 후 rethrow:
```java
        try {
            String overrideYaml = ...; Files.writeString(overridePath, overrideYaml);
            var envCfg = ...;
            try (AttachedComposeEnvironment env =
                    new AttachedComposeEnvironment(envCfg, config.dbConfig().type(), otlpReceiver, httpCapture)) {
                env.start(workDir);
                return explore(...);
            }
        } catch (RuntimeException | java.io.IOException e) {
            httpCapture.close();
            if (otlpReceiver != null) otlpReceiver.stop();
            throw e;
        }
```
    (try-with-resources 성공 시 env.close()가 둘을 닫고, catch는 그 전 실패만 처리 — 이중 close 무해하나 주의. 더 단순히: try-with-resources 진입 전 구간만 별도 try/finally로 감싸도 됨. 구현자가 이중-close 없는 형태로 정리.)
  - import: `io.graphrag.builder.env.HttpCaptureServer`.
- [ ] **Step 2: OverrideComposeGenerator host-gateway 항상** — 위 Spec 인자에서 addHostGateway=true 고정(코드 1줄). `OverrideComposeGeneratorTest`에 attach가 항상 host-gateway 주입함을 검증하는 단위가 없으면 추가(기존 테스트가 false 케이스만 보면 true 케이스 추가).
- [ ] **Step 3: 컴파일 + 단위** — Run: `./gradlew :graph-rag-builder:test --tests '*OverrideComposeGenerator*'` + `:graph-rag-builder:compileJava`.
- [ ] **Step 4: Commit** — `feat(cli): runAttached wires host WireMock (token + {{wiremock}}→host.docker.internal) + host-gateway always + failure cleanup`.

---

## Phase 4 — E2E green + 회귀 + 문서

### Task 5: 수용 green + 회귀 + docs

- [ ] **Step 1: 수용 green** — Run: `bash e2e/run-attach-ext-http-e2e.sh`
Expected: PASS — graph.json httpCalls에 inventory CapturedHttpCall(깨끗한 urlPath), 토큰 미누출, teardown clean.
- [ ] **Step 2: 기존 attach 회귀** — Run: `bash e2e/run-attach-e2e.sh` + `bash e2e/run-attach-otel-e2e.sh` → 둘 다 PASS(외부 HTTP 배선이 기존 attach를 깨지 않음; otel은 otlp+http 둘 다 소유).
- [ ] **Step 3: 전체 단위/통합 회귀** — Run: `./gradlew test` → 0 failures(analysis/기존 attach 무변경).
- [ ] **Step 4: docs** — `docs/26-attach-mode.md`: "v1 한계 #3"(외부 HTTP 미지원) 제거; "attach 외부 HTTP 캡처" 절 추가(`--external-stubs`/`--sut-env {{wiremock}}` 사용, host.docker.internal:<port>/<token> 경유, per-run 토큰으로 0.0.0.0 보호, Docker 20.10+ 필요·미만 시 캡처 0 경고). `docs/decisions/mock-services-security-model.md`: attach 호스트 WireMock이 0.0.0.0 노출 시 per-run 토큰으로 보호(compose-내부 전제의 예외)임을 기록.
- [ ] **Step 5: Commit** — `docs: attach external HTTP capture + token security model note; remove v1 limit #3`.

---

## Definition of Done

- [ ] `run-attach-ext-http-e2e.sh` green (inventory CapturedHttpCall 캡처, urlPath 토큰 미누출, teardown clean).
- [ ] 단위: HttpCaptureServer 토큰 필터(401/strip 매칭/drain strip)·hostBaseUrl·무토큰 analysis 경로; OverrideComposeGenerator attach 항상 host-gateway; AttachedComposeEnvironment httpCapture non-null + close stop.
- [ ] 기존 attach e2e(run-attach-e2e/run-attach-otel-e2e) + `./gradlew test` green — analysis/기존 attach 무변경.
- [ ] docs/26 + decision doc 갱신.
- [ ] PR 전 spec-compliance + 코드 품질 리뷰 트리아지.

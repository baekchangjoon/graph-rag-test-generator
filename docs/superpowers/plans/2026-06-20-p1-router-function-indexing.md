# P1 — RouterFunction(WebFlux 함수형 라우팅) 인덱싱 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** graph-rag가 `RouterFunctions.route().GET/POST/...(path, handler)`로 선언된 WebFlux 함수형 REST 라우트를 정적으로 발견·인덱싱해 기존 explore/generate 파이프라인에 합류시킨다(현재: `@RestController`/`@*Mapping`만 봐서 함수형 SUT는 0개 발견).

**Architecture:** 기존 `KafkaListenerIndexer` 패턴을 그대로 따른다 — 자체 Spoon `Launcher`(noClasspath)를 가진 새 `RouterFunctionIndexer`가 `@Bean`/임의 메서드 본문의 `CtInvocation` 체인에서 `.GET/.POST/.PUT/.DELETE/.PATCH(path, handler)`를 순회해 `Endpoint`를 산출한다. 산출물을 `BuilderCli.build()`에서 기존 `EndpointIndexer` 결과와 `IndexResult.merge`로 합쳐 explore/selector가 그대로 재사용한다.

**Tech Stack:** Java 17, Spoon(noClasspath), JUnit5 + AssertJ + `@TempDir`(테스트 패턴: `KafkaListenerIndexerTest`), Gradle.

**REQ 커버리지:** REQ-001(라우트 발견)·REQ-002(body/path-var 추출)·REQ-003(폴백+synthetic shape)·REQ-004(IndexResult merge + explore 도달) + 횡단 REQ-021(무-LLM ArchUnit)·REQ-022(기존 e2e 회귀).

> 출처: `docs/superpowers/requirements/2026-06-20-method1-tool-gaps-requirements.md`, `docs/2026-06-20-method1-tainted-spring-tool-gaps.md`(§5 P1).

---

## File Structure

| 파일 | 책임 | 변경 |
|---|---|---|
| `graph-rag-builder/.../index/EndpointIds.java` | 엔드포인트 id 생성 규약(method+path) 단일 소스 | Create(추출) |
| `graph-rag-builder/.../index/EndpointIndexer.java` | 기존 id 인라인 로직을 `EndpointIds` 호출로 교체 | Modify |
| `graph-rag-builder/.../index/RouterFunctionIndexer.java` | 함수형 라우트 → `IndexResult` | Create |
| `graph-rag-builder/.../index/IndexResult.java` | `merge(other)` 추가(불변 병합) | Modify |
| `graph-rag-builder/.../cli/BuilderCli.java` | RouterFunctionIndexer 배선 + merge | Modify(L160 인근) |
| `graph-rag-builder/src/test/java/.../index/RouterFunctionIndexerTest.java` | 단위 TDD | Create |
| `graph-rag-builder/src/test/java/.../index/IndexResultMergeTest.java` | merge 단위 TDD | Create |
| `graph-rag-builder/src/test/java/.../index/RouterFunctionFixtureIT.java` | 내부 fixture 통합 테스트(REQ-001/004 수용, CI 강제) | Create |
| `graph-rag-builder/src/test/java/.../arch/NoLlmDependencyTest.java` | REQ-021 금지-import 가드 | Create |

---

## Task 0: `EndpointIds` 공유 헬퍼 추출 (id 규약 단일 소스)

**REQ-IDs:** REQ-004 (id 네이밍 호환)

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/index/EndpointIds.java`
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/index/EndpointIndexer.java` (기존 `endpointId(...)` 호출부)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/index/EndpointIdsTest.java`

- [ ] **Step 1: 기존 id 규약 확인**

Run: `grep -n "private static String endpointId" graph-rag-builder/src/main/java/io/graphrag/builder/index/EndpointIndexer.java`
→ 그 메서드 본문을 그대로 복사해 새 `EndpointIds.of(httpMethod, path)`로 옮긴다(규약 변경 금지 — 기존 graph.json/selector 호환).

- [ ] **Step 2: 실패 테스트 작성** `EndpointIdsTest.java`

```java
package io.graphrag.builder.index;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EndpointIdsTest {
    @Test
    void of_matchesLegacyEndpointIdScheme() {
        // EndpointIndexer가 만들던 기존 id와 동일해야 한다(회귀 0).
        assertThat(EndpointIds.of("POST", "/api/orders")).isEqualTo("post-api-orders");
        assertThat(EndpointIds.of("GET", "/api/orders/{id}")).isEqualTo("get-api-orders-id");
    }
}
```
> 주: 기대값은 Step 1에서 확인한 **실제 규약**으로 맞춘다(위는 일반적 형태 예시). 규약이 다르면 기대값을 실제 출력에 맞추고, 절대 규약 자체를 바꾸지 않는다.

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests '*EndpointIdsTest' -q`
Expected: FAIL — `EndpointIds` 클래스 없음(컴파일 에러).

- [ ] **Step 4: `EndpointIds` 구현 + EndpointIndexer 위임**

`EndpointIds.java`에 Step 1에서 복사한 로직을 `public static String of(String httpMethod, String path)`로 넣고, `EndpointIndexer`의 `endpointId(...)` 호출을 `EndpointIds.of(...)`로 교체(기존 private 메서드 제거).

- [ ] **Step 5: 테스트 통과 + 기존 인덱서 테스트 회귀 확인**

Run: `./gradlew :graph-rag-builder:test --tests '*EndpointIdsTest' --tests '*EndpointIndexerTest' --tests '*EndpointIndexerCollectionTest' -q`
Expected: PASS (id 규약 불변이므로 기존 EndpointIndexer 테스트도 green).

- [ ] **Step 6: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/index/EndpointIds.java \
        graph-rag-builder/src/main/java/io/graphrag/builder/index/EndpointIndexer.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/index/EndpointIdsTest.java
git commit -m "refactor(index): extract EndpointIds shared id-scheme helper (REQ-004 prep)"
```

---

## Task 1: `RouterFunctionIndexer` — 함수형 라우트 발견

**REQ-IDs:** REQ-001

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/index/RouterFunctionIndexer.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/index/RouterFunctionIndexerTest.java`

- [ ] **Step 1: 실패 테스트 작성** (KafkaListenerIndexerTest 패턴 — `@TempDir` + 소스 작성)

```java
package io.graphrag.builder.index;

import io.graphrag.model.Endpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class RouterFunctionIndexerTest {

    private static Path writeRouter(Path dir) throws Exception {
        Path pkg = Files.createDirectories(dir.resolve("com/x"));
        Files.writeString(pkg.resolve("Routes.java"),
                "package com.x;\n"
              + "import org.springframework.context.annotation.Bean;\n"
              + "import org.springframework.web.reactive.function.server.RouterFunction;\n"
              + "import org.springframework.web.reactive.function.server.ServerResponse;\n"
              + "import static org.springframework.web.reactive.function.server.RouterFunctions.route;\n"
              + "public class Routes {\n"
              + "  @Bean RouterFunction<ServerResponse> r(Handler h) {\n"
              + "    return route()\n"
              + "      .POST(\"/internal/counseling/sessions\", h::create)\n"
              + "      .POST(\"/internal/counseling/sessions/{id}/messages\", h::addMessage)\n"
              + "      .build();\n"
              + "  }\n"
              + "}\n");
        Files.writeString(pkg.resolve("Handler.java"),
                "package com.x;\n"
              + "import org.springframework.web.reactive.function.server.ServerRequest;\n"
              + "import org.springframework.web.reactive.function.server.ServerResponse;\n"
              + "import reactor.core.publisher.Mono;\n"
              + "public class Handler {\n"
              + "  Mono<ServerResponse> create(ServerRequest req) { return ServerResponse.ok().build(); }\n"
              + "  Mono<ServerResponse> addMessage(ServerRequest req) { return ServerResponse.ok().build(); }\n"
              + "}\n");
        return dir;
    }

    @Test
    void index_discoversFunctionalRoutes(@TempDir Path dir) throws Exception {  // REQ-001
        IndexResult result = new RouterFunctionIndexer().index(writeRouter(dir));

        assertThat(result.endpoints())
                .extracting(Endpoint::httpMethod, Endpoint::path)
                .containsExactlyInAnyOrder(
                        tuple("POST", "/internal/counseling/sessions"),
                        tuple("POST", "/internal/counseling/sessions/{id}/messages"));
        assertThat(result.endpoints()).allSatisfy(e ->
                assertThat(e.handlerClass()).isEqualTo("com.x.Routes"));
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests '*RouterFunctionIndexerTest' -q`
Expected: FAIL — `RouterFunctionIndexer` 클래스 없음.

- [ ] **Step 3: 최소 구현** `RouterFunctionIndexer.java`

```java
package io.graphrag.builder.index;

import io.graphrag.model.Endpoint;
import io.graphrag.model.EndpointParam;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.visitor.filter.TypeFilter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WebFlux 함수형 라우팅(RouterFunctions.route().GET/POST/...(path, handler)) 인덱싱.
 * @RestController/@*Mapping이 없어 EndpointIndexer가 보지 못하는 라우트를 정적으로 발견한다.
 * 별도 indexer 패턴(KafkaListenerIndexer 참조).
 */
public class RouterFunctionIndexer {

    private static final Set<String> HTTP_METHODS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH");

    public IndexResult index(Path sutSrcDir) {
        Launcher launcher = new Launcher();
        launcher.addInputResource(sutSrcDir.toString());
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.getEnvironment().setComplianceLevel(17);
        CtModel model = launcher.buildModel();

        List<Endpoint> endpoints = new ArrayList<>();
        Map<String, BodyShape> bodyShapes = new HashMap<>();

        for (CtType<?> type : model.getAllTypes()) {
            for (CtMethod<?> method : type.getMethods()) {
                for (CtInvocation<?> inv : method.getElements(new TypeFilter<>(CtInvocation.class))) {
                    String verb = inv.getExecutable().getSimpleName();
                    if (!HTTP_METHODS.contains(verb) || inv.getArguments().isEmpty()) {
                        continue;
                    }
                    // .GET/.POST 등의 첫 인자가 path 문자열 리터럴인 것만 라우트 정의로 본다
                    // (RequestPredicates.method() 등 동명 메서드는 첫 인자가 리터럴 path가 아니라 자동 제외).
                    if (!(inv.getArguments().get(0) instanceof CtLiteral<?> lit)
                            || !(lit.getValue() instanceof String path)) {
                        continue;
                    }
                    List<EndpointParam> params = new ArrayList<>();   // Task 2에서 채움
                    endpoints.add(new Endpoint(
                            EndpointIds.of(verb, path),
                            verb,
                            path,
                            type.getQualifiedName().replace('$', '.'),
                            method.getSimpleName(),
                            params,
                            false));
                }
            }
        }
        endpoints.sort(Comparator.comparing(Endpoint::id));
        return new IndexResult(endpoints, bodyShapes, Set.of());
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :graph-rag-builder:test --tests '*RouterFunctionIndexerTest' -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/index/RouterFunctionIndexer.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/index/RouterFunctionIndexerTest.java
git commit -m "feat(index): RouterFunctionIndexer discovers functional routes (REQ-001)"
```

---

## Task 2: handler body-shape / path-var 추출

**REQ-IDs:** REQ-002

**Files:**
- Modify: `graph-rag-builder/.../index/RouterFunctionIndexer.java`
- Test: `graph-rag-builder/.../index/RouterFunctionIndexerTest.java`

- [ ] **Step 1: 실패 테스트 추가** (RouterFunctionIndexerTest에 메서드 추가)

```java
    @Test
    void index_extractsBodyShapeAndPathVar(@TempDir Path dir) throws Exception {  // REQ-002
        Path pkg = Files.createDirectories(dir.resolve("com/x"));
        Files.writeString(pkg.resolve("Dto.java"),
                "package com.x;\npublic record Dto(String title, int score) {}\n");
        Files.writeString(pkg.resolve("Routes.java"),
                "package com.x;\n"
              + "import org.springframework.context.annotation.Bean;\n"
              + "import org.springframework.web.reactive.function.server.*;\n"
              + "import static org.springframework.web.reactive.function.server.RouterFunctions.route;\n"
              + "public class Routes {\n"
              + "  @Bean RouterFunction<ServerResponse> r(Handler h) {\n"
              + "    return route().POST(\"/sessions/{id}/messages\", h::add).build();\n"
              + "  }\n}\n");
        Files.writeString(pkg.resolve("Handler.java"),
                "package com.x;\n"
              + "import com.x.Dto;\n"
              + "import org.springframework.web.reactive.function.server.*;\n"
              + "import reactor.core.publisher.Mono;\n"
              + "public class Handler {\n"
              + "  Mono<ServerResponse> add(ServerRequest req) {\n"
              + "    String id = req.pathVariable(\"id\");\n"
              + "    return req.bodyToMono(Dto.class).flatMap(d -> ServerResponse.ok().build());\n"
              + "  }\n}\n");

        IndexResult result = new RouterFunctionIndexer().index(dir);
        Endpoint ep = result.endpoints().get(0);
        assertThat(ep.params()).extracting(EndpointParam::name, EndpointParam::kind)
                .contains(tuple("id", io.graphrag.model.ParamKind.PATH),
                          tuple("body", io.graphrag.model.ParamKind.BODY));
        assertThat(result.bodyShapes().get("com.x.Dto").fields())
                .extracting(BodyShape.BodyField::name).contains("title", "score");
    }
```
(import 추가: `io.graphrag.model.EndpointParam`.)

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests '*RouterFunctionIndexerTest.index_extractsBodyShapeAndPathVar' -q`
Expected: FAIL — params 비어 있음.

- [ ] **Step 3: 구현 — handler 메서드 본문 역추적**

`RouterFunctionIndexer.index`의 endpoint 생성 직전에, handler 참조(`inv.getArguments().get(1)`)가 메서드 참조면 그 타입의 동명 메서드 body에서 `pathVariable("…")`·`bodyToMono(T.class)` 호출을 찾아 params/bodyShape를 채운다. 추출 헬퍼:

```java
    // inv.getArguments().get(1) (handler)가 CtExecutableReferenceExpression(메서드 참조)이면
    // 그 메서드 body를 찾아 path-var/body-shape를 추출. 해석 실패 시 빈 params(Task 3 폴백).
    private void enrichFromHandler(CtModel model, CtInvocation<?> inv, String path,
                                   List<EndpointParam> params, Map<String, BodyShape> bodyShapes) {
        CtMethod<?> handler = resolveHandlerMethod(inv);   // best-effort; null 가능
        if (handler == null || handler.getBody() == null) {
            return;
        }
        for (CtInvocation<?> call : handler.getElements(new TypeFilter<>(CtInvocation.class))) {
            String name = call.getExecutable().getSimpleName();
            if ("pathVariable".equals(name) && !call.getArguments().isEmpty()
                    && call.getArguments().get(0) instanceof CtLiteral<?> l
                    && l.getValue() instanceof String var) {
                params.add(new EndpointParam(var, "java.lang.String", io.graphrag.model.ParamKind.PATH));
            } else if ("bodyToMono".equals(name) && !call.getArguments().isEmpty()) {
                bodyTypeFromClassArg(call.getArguments().get(0)).ifPresent(fqn -> {
                    params.add(new EndpointParam("body", fqn, io.graphrag.model.ParamKind.BODY));
                    BodyShapeExtractor.extract(model, fqn).ifPresent(s -> bodyShapes.put(fqn, s));
                });
            }
        }
    }
```
`resolveHandlerMethod`는 KafkaListenerIndexer의 `readValueTargetType`처럼 `CtExecutableReference`/`CtTypeAccess`를 통해 best-effort로 메서드를 찾고, `bodyTypeFromClassArg`는 `T.class`(=`CtFieldRead`+`CtTypeAccess`)에서 FQN을 뽑는다(KafkaListenerIndexer L101~108 동일 패턴 재사용 — 그 로직을 참조해 작성). endpoint 생성부에서 `enrichFromHandler(model, inv, path, params, bodyShapes)` 호출.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :graph-rag-builder:test --tests '*RouterFunctionIndexerTest' -q`
Expected: PASS(두 테스트 모두).

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/index/RouterFunctionIndexer.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/index/RouterFunctionIndexerTest.java
git commit -m "feat(index): extract body-shape/path-var from functional handlers (REQ-002)"
```

---

## Task 3: 해석 실패 폴백 + synthetic body-shape

**REQ-IDs:** REQ-003 (explore skip 통과 보장)

**배경:** `BuilderCli.explore`는 `shape == null && !GET && !hasPathParam`이면 라우트를 skip한다(BuilderCli L573~575). path param 없는 POST 함수형 라우트의 body 해석이 실패하면 영영 탐색에 못 가므로, 그 경우 **synthetic 최소 body-shape**를 부여해 explore 진입을 보장한다.

**Files:**
- Modify: `graph-rag-builder/.../index/RouterFunctionIndexer.java`
- Test: `graph-rag-builder/.../index/RouterFunctionIndexerTest.java`

- [ ] **Step 1: 실패 테스트 추가**

```java
    @Test
    void index_unresolvedBodyPost_getsSyntheticShapeForExplore(@TempDir Path dir) throws Exception {  // REQ-003
        Path pkg = Files.createDirectories(dir.resolve("com/x"));
        Files.writeString(pkg.resolve("Routes.java"),
                "package com.x;\n"
              + "import org.springframework.context.annotation.Bean;\n"
              + "import org.springframework.web.reactive.function.server.*;\n"
              + "import static org.springframework.web.reactive.function.server.RouterFunctions.route;\n"
              + "public class Routes {\n"
              + "  @Bean RouterFunction<ServerResponse> r() {\n"
              + "    return route().POST(\"/sessions\", req -> ServerResponse.ok().build()).build();\n"
              + "  }\n}\n");   // 람다 handler — body 타입 해석 불가

        IndexResult result = new RouterFunctionIndexer().index(dir);
        Endpoint ep = result.endpoints().get(0);
        // path param 없는 POST → explore skip을 피하도록 BODY param 또는 bodyShape가 존재해야 한다.
        boolean hasPath = ep.params().stream().anyMatch(p -> p.kind() == io.graphrag.model.ParamKind.PATH);
        boolean hasBody = ep.params().stream().anyMatch(p -> p.kind() == io.graphrag.model.ParamKind.BODY);
        assertThat(hasPath || hasBody).isTrue();   // explore 게이트 통과 가능
    }
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests '*RouterFunctionIndexerTest.index_unresolvedBodyPost*' -q`
Expected: FAIL — params 비어 있음(POST, no path var).

- [ ] **Step 3: 구현 — synthetic shape 폴백**

endpoint 생성 직전, `verb`가 GET이 아니고 params에 PATH/BODY가 없으면 synthetic BODY param + 빈 `BodyShape`(또는 단일 자유 필드)를 추가한다. 추가 사실을 인덱싱-단계 경고로 남긴다(REQ-003 — `validationWarnings`는 explore 단계 필드라 부적합하므로, 빌더 로그 + 후속 `IndexResult` indexingWarnings 채널은 Task 5에서 BuilderCli 로그로 처리). 최소 구현:

```java
    if (!"GET".equals(verb) && params.stream().noneMatch(
            p -> p.kind() == io.graphrag.model.ParamKind.PATH
              || p.kind() == io.graphrag.model.ParamKind.BODY)) {
        String synth = "io.graphrag.synthetic.Body";
        params.add(new EndpointParam("body", synth, io.graphrag.model.ParamKind.BODY));
        bodyShapes.putIfAbsent(synth, BodyShape.empty());   // 빈 shape — happyInput이 {}로 합성
        log.warn("functional route {} {}: body 타입 미해석 → synthetic shape (best-effort)", verb, path);
    }
```
> `BodyShape.empty()`가 없으면 빈 fields 리스트로 생성하는 팩토리를 `BodyShape`에 추가(소규모, 같은 커밋). `log`는 `org.slf4j.Logger`(KafkaListenerIndexer는 로깅 없으니 BuilderCli 스타일로 `LoggerFactory.getLogger(RouterFunctionIndexer.class)` 필드 추가).

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :graph-rag-builder:test --tests '*RouterFunctionIndexerTest' -q`
Expected: PASS(세 테스트 모두).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(index): synthetic body-shape fallback so unresolved functional POST reaches explore (REQ-003)"
```

---

## Task 4: `IndexResult.merge` (불변 병합)

**REQ-IDs:** REQ-004

**Files:**
- Modify: `graph-rag-builder/.../index/IndexResult.java`
- Test: `graph-rag-builder/.../index/IndexResultMergeTest.java`

- [ ] **Step 1: 실패 테스트 작성**

```java
package io.graphrag.builder.index;

import io.graphrag.model.Endpoint;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class IndexResultMergeTest {
    @Test
    void merge_concatsEndpointsAndUnionsMaps() {  // REQ-004
        Endpoint a = new Endpoint("a", "GET", "/a", "C", "m", List.of(), false);
        Endpoint b = new Endpoint("b", "POST", "/b", "C", "m", List.of(), false);
        IndexResult left = new IndexResult(List.of(a), Map.of("S1", BodyShape.empty()), Set.of("a"));
        IndexResult right = new IndexResult(List.of(b), Map.of("S2", BodyShape.empty()), Set.of());

        IndexResult merged = left.merge(right);

        assertThat(merged.endpoints()).extracting(Endpoint::id).containsExactlyInAnyOrder("a", "b");
        assertThat(merged.bodyShapes()).containsKeys("S1", "S2");
        assertThat(merged.validBodyEndpointIds()).containsExactly("a");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests '*IndexResultMergeTest' -q`
Expected: FAIL — `merge` 메서드 없음.

- [ ] **Step 3: 구현 — IndexResult.merge**

```java
    /** 다른 IndexResult를 병합한 새 인스턴스(불변). endpoints concat, bodyShapes putAll, validBodyEndpointIds addAll. */
    public IndexResult merge(IndexResult other) {
        List<Endpoint> mergedEndpoints = new java.util.ArrayList<>(this.endpoints);
        mergedEndpoints.addAll(other.endpoints);
        Map<String, BodyShape> mergedShapes = new java.util.HashMap<>(this.bodyShapes);
        mergedShapes.putAll(other.bodyShapes);
        Set<String> mergedValid = new java.util.LinkedHashSet<>(this.validBodyEndpointIds);
        mergedValid.addAll(other.validBodyEndpointIds);
        return new IndexResult(mergedEndpoints, mergedShapes, mergedValid);
    }
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :graph-rag-builder:test --tests '*IndexResultMergeTest' -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/index/IndexResult.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/index/IndexResultMergeTest.java
git commit -m "feat(index): IndexResult.merge for combining indexer outputs (REQ-004)"
```

---

## Task 5: `BuilderCli` 배선 + merge (explore 도달)

**REQ-IDs:** REQ-004

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java` (L160 인근, 인덱서 배선부)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/index/RouterFunctionFixtureIT.java`

- [ ] **Step 1: 실패 통합 테스트 작성** (내부 fixture — CI 강제, Docker 불필요)

```java
package io.graphrag.builder.index;

import io.graphrag.model.Endpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class RouterFunctionFixtureIT {
    @Test
    void functionalRoutes_mergeIntoEndpointIndex(@TempDir Path dir) throws Exception {  // REQ-001/004 수용
        // @RestController 0개 + 함수형 라우트 2개인 fixture SUT.
        Path pkg = Files.createDirectories(dir.resolve("com/x"));
        Files.writeString(pkg.resolve("Routes.java"),
                "package com.x;\n"
              + "import org.springframework.context.annotation.Bean;\n"
              + "import org.springframework.web.reactive.function.server.*;\n"
              + "import static org.springframework.web.reactive.function.server.RouterFunctions.route;\n"
              + "public class Routes {\n"
              + "  @Bean RouterFunction<ServerResponse> r() {\n"
              + "    return route()\n"
              + "      .POST(\"/internal/counseling/sessions\", req -> ServerResponse.ok().build())\n"
              + "      .GET(\"/internal/counseling/sessions/{id}\", req -> ServerResponse.ok().build())\n"
              + "      .build();\n"
              + "  }\n}\n");

        IndexResult annotated = new EndpointIndexer().index(dir);   // @RestController 0 → empty
        IndexResult functional = new RouterFunctionIndexer().index(dir);
        IndexResult merged = annotated.merge(functional);

        assertThat(merged.endpoints()).extracting(Endpoint::path)
                .contains("/internal/counseling/sessions", "/internal/counseling/sessions/{id}");
        assertThat(merged.endpoints()).hasSizeGreaterThanOrEqualTo(2);   // E2E-1 임계: ≥2
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests '*RouterFunctionFixtureIT' -q`
Expected: PASS 가능성 있음(merge·indexer 이미 구현) — 이 테스트는 **수용 게이트**다. 만약 PASS면 Step 3의 BuilderCli 배선은 "런타임 파이프라인에도 합류"를 보장하는 변경으로 진행(테스트는 BuilderCli 경유로 강화).

- [ ] **Step 3: BuilderCli.build에 배선 + merge**

`BuilderCli.build()`의 인덱서 생성부(현재):
```java
IndexResult index = new EndpointIndexer().index(config.sutSrc(), config.authConfig());
```
바로 아래에 추가:
```java
IndexResult functional = new RouterFunctionIndexer().index(config.sutSrc());
if (!functional.endpoints().isEmpty()) {
    log.info("found {} functional route(s) (RouterFunction)", functional.endpoints().size());
    index = index.merge(functional);
}
```
이후 `index.endpoints()`를 쓰는 모든 하류(explore/selector/IncrementalBuildPlanner)는 자동으로 함수형 라우트를 포함한다(변경 불필요).

- [ ] **Step 4: 통과 + 기존 빌더 테스트 회귀 확인**

Run: `./gradlew :graph-rag-builder:test -q`
Expected: PASS(전체 graph-rag-builder 단위/통합 — 함수형 라우트 없는 기존 SUT는 `functional.endpoints()` empty라 merge no-op → 회귀 0).

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/index/RouterFunctionFixtureIT.java
git commit -m "feat(cli): wire RouterFunctionIndexer + merge into build pipeline (REQ-004)"
```

---

## Task 6: REQ-021 무-LLM 금지-import 가드 (횡단, P1에서 확립)

**REQ-IDs:** REQ-021

**Files:**
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/arch/NoLlmDependencyTest.java`

- [ ] **Step 1: 가드 테스트 작성** (신규 indexer 패키지에 LLM/네트워크 직접 의존 금지)

```java
package io.graphrag.builder.arch;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;

class NoLlmDependencyTest {
    @Test
    void indexers_haveNoLlmOrDirectHttpClientImports() throws Exception {  // REQ-021
        Path idx = Path.of("src/main/java/io/graphrag/builder/index");
        try (Stream<Path> files = Files.walk(idx)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                String src = readString(p);
                assertThat(src)
                    .as("%s must not import LLM/network clients", p)
                    .doesNotContain("anthropic").doesNotContain("openai")
                    .doesNotContain("java.net.http.HttpClient")
                    .doesNotContain("okhttp3");
            });
        }
    }
    private static String readString(Path p) { try { return Files.readString(p); } catch (Exception e) { throw new RuntimeException(e); } }
}
```
> ArchUnit 라이브러리가 이미 의존성에 있으면 ArchUnit 규칙으로 대체 가능(`grep -rn archunit graph-rag-builder/build.gradle.kts`). 없으면 위 파일-스캔 가드로 충분.

- [ ] **Step 2: 통과 확인** (현재 indexer는 LLM 의존 0)

Run: `./gradlew :graph-rag-builder:test --tests '*NoLlmDependencyTest' -q`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add graph-rag-builder/src/test/java/io/graphrag/builder/arch/NoLlmDependencyTest.java
git commit -m "test(arch): guard indexers against LLM/network imports (REQ-021)"
```

---

## Task 7: REQ-022 기존 e2e 회귀 확인 (하한 게이트)

**REQ-IDs:** REQ-022

**Files:** (코드 변경 없음 — 검증 단계)

- [ ] **Step 1: 전체 단위/통합 + 샘플 e2e 회귀**

Run:
```bash
./gradlew :graph-rag-builder:test :test-generator:test :shared-model:test -q
./e2e/run-e2e.sh
```
Expected: 전부 PASS (order-service 53 테스트 green). 함수형 라우트가 없는 order-service는 merge no-op이라 회귀 0이어야 한다.
> Docker/Testcontainers 미가용 환경이면 `./e2e/run-e2e.sh`는 skip하고 그 사실을 PR 본문에 명시(단위/통합은 필수).

- [ ] **Step 2: REQ 매트릭스 갱신**

`docs/superpowers/requirements/2026-06-20-method1-tool-gaps-requirements.md`의 매트릭스에서 REQ-001/002/003/004/021/022 행 Status를 🟢로, Coverage 줄을 갱신. 각 🟢가 실제 통과 테스트와 대응하는지 테스트명 대조.

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/requirements/2026-06-20-method1-tool-gaps-requirements.md
git commit -m "docs(req): P1 REQ-001~004 green (RouterFunction indexing)"
```

---

## Self-Review (작성자 체크)

- **Spec coverage:** REQ-001(Task 1)·REQ-002(Task 2)·REQ-003(Task 3)·REQ-004(Task 4,5)·REQ-021(Task 6)·REQ-022(Task 7) 전부 task로 매핑됨. P1의 in-scope REQ 누락 없음.
- **타입 일관성:** `IndexResult(List<Endpoint>, Map<String,BodyShape>, Set<String>)`·`Endpoint(7-arg)`·`EndpointParam(name,javaType,kind)`·`EndpointIds.of(method,path)`·`IndexResult.merge` — task 간 시그니처 일치 확인.
- **확인 필요(실행 시 grounding):** ① `EndpointIndexer.endpointId` 실제 규약(Task 0 Step 1) ② `BodyShape.empty()` 존재 여부(없으면 Task 3에서 팩토리 추가) ③ `BodyShapeExtractor.extract(model, fqn)` 시그니처(Task 2 — KafkaListenerIndexer L76에서 동일 호출 확인됨) ④ handler 메서드 참조 해석(`resolveHandlerMethod`)은 noClasspath best-effort라 일부 fixture에서 null 가능 → Task 3 폴백이 안전망.
- **미해결 의존:** 없음(각 task는 앞 task 산출물만 사용).

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-20-p1-router-function-indexing.md`. Two execution options:

1. **Subagent-Driven (recommended)** — task별 fresh subagent + 2단계 리뷰(spec-compliance → code-quality), task 간 체크인.
2. **Inline Execution** — 이 세션에서 executing-plans로 배치 실행 + 체크포인트.

P2~P5 plan은 P1 완료 후 같은 형식으로 이어 작성한다(P1이 확립한 EndpointIds·IndexResult.merge·fixture-IT 패턴·NoLlm 가드를 재사용).

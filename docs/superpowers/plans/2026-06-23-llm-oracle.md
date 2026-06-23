# LlmOracle (LLM 값 오라클) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 핸들러 소스+제약을 LLM에 단일 구조화 호출해 도메인 그럴듯한 *문자열 값*을 생성하고, 캐시로 결정적·오프라인화하여 `InputCandidates.strings` union에 더해 깊은 분기 커버리지를 올린다.

**Architecture:** `LlmOracle implements InputOracle`가 엔드포인트를 순회하며 (HandlerSourceExtractor 본문 + EndpointFieldSelector 선별 필드 + @Pattern 제약)으로 `LlmRequest`를 만들고, `LlmValueCache`(classpath read / fs write) 우선 → miss & 키 존재 시 `AnthropicValueClient`(structured, temp 0) 호출 → `ShapeGate`로 그라운딩 → strings 누적. `BuilderCli.explore()`에서 `--llm-oracle` 플래그(`BuildConfig` 경유) 뒤 merge. CI는 cache-or-skip(오프라인).

**Tech Stack:** Java 23, Gradle, Spoon(소스 파싱), Anthropic Java SDK(`com.anthropic:anthropic-java`), JUnit5/AssertJ, 기존 `InputOracle`/`InputCandidates` SPI.

## Global Constraints

- 모든 LLM/HTTP 코드는 `io.graphrag.builder.oracle` 패키지에만. `io.graphrag.builder.index`에 `anthropic`/`openai`/`java.net.http.HttpClient`/`okhttp3` import 금지(`NoLlmDependencyTest` GREEN 유지). [REQ-010]
- LlmOracle는 **strings 채널만** 기여(numeric/reals/tuples 미사용). [design 비목표]
- 결정성: 캐시가 하드 보장, temperature 0은 보조. 캐시 키 = `sha256(endpoint.id + "\n" + 핸들러 본문 소스 + "\n" + 정렬 필드셋(name:type) + "\n" + modelId)`. [REQ-002, REQ-003]
- 기본 모델 ID: `claude-haiku-4-5-20251001`. 에스컬레이션은 수동(`--llm-model claude-sonnet-4-6`). [design]
- `--llm-oracle` 미지정 시 코드 경로 완전 no-op(회귀 0). [REQ-009, REQ-011]
- 커밋 author/committer: `baekchangjoon <changjoon.baek@icloud.com>` (env vars). repo는 rebase-only.
- `ANTHROPIC_API_KEY` 절대 커밋 금지.

## File Structure

신규 (`graph-rag-builder/src/main/java/io/graphrag/builder/oracle/`):
- `LlmFieldValues.java` — record: 필드→문자열 후보값.
- `LlmRequest.java` — record: endpointId, handlerSource, fields, patternByField, emailFields, modelId.
- `LlmValueClient.java` — interface `generate(LlmRequest) → LlmFieldValues`.
- `AnthropicValueClient.java` — SDK structured 구현 (lazy 키).
- `LlmValueCache.java` — key/read(classpath)/write(fs).
- `ShapeGate.java` — BodyShape 그라운딩 필터.
- `EndpointFieldSelector.java` — 엄격검증 필드 선별.
- `HandlerSourceExtractor.java` — 핸들러 메서드 본문(Spoon).
- `LlmOracle.java` — InputOracle 구현, 컴포넌트 오케스트레이션.

수정:
- `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuildConfig.java` — `+boolean llmOracle, +String llmModel`.
- `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java` — main 파싱 + explore 배선 + CLI help 주석.
- `gradle/libs.versions.toml`, `graph-rag-builder/build.gradle.kts` — SDK 의존.
- `samples/order-service/src/main/java/io/graphrag/sample/orders/CouponController.java` — E2E fixture(신규).
- `graph-rag-builder/src/main/resources/llm-oracle-cache/<key>.json` — E2E 캐시 fixture(신규, 수기).
- `graph-rag-builder/src/test/.../BuilderIntegrationTest.java` — containsExactly 목록 갱신(fixture 엔드포인트 추가분).

테스트 (`graph-rag-builder/src/test/java/io/graphrag/builder/oracle/`): `LlmRequestTest`, `LlmValueCacheTest`, `ShapeGateTest`, `EndpointFieldSelectorTest`, `HandlerSourceExtractorTest`, `AnthropicValueClientTest`, `LlmOracleTest`, 그리고 `LlmOracleE2E`(integration 위치).

---

## Task 1: Anthropic SDK 의존 + 값 레코드 (LlmFieldValues, LlmRequest)

**REQ-IDs:** REQ-014 (프롬프트 주입·비용 완화: 본문만+구조화 분리)

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `graph-rag-builder/build.gradle.kts`
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/oracle/LlmFieldValues.java`
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/oracle/LlmRequest.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/oracle/LlmRequestTest.java`

**Interfaces:**
- Produces:
  - `record LlmFieldValues(Map<String,List<String>> stringValuesByField)` + `static LlmFieldValues empty()`.
  - `record LlmRequest(String endpointId, String handlerSource, List<BodyShape.BodyField> fields, Map<String,String> patternByField, Set<String> emailFields, String modelId)`.

- [ ] **Step 1: 의존성 추가** — `gradle/libs.versions.toml` `[versions]`에 `anthropicJava = "2.34.0"`, `[libraries]`에 `anthropic-java = { module = "com.anthropic:anthropic-java", version.ref = "anthropicJava" }`. `graph-rag-builder/build.gradle.kts` `dependencies {}`에 `implementation(libs.anthropic.java)`.

- [ ] **Step 2: 실패 테스트 작성** — `LlmRequestTest.java`:

```java
package io.graphrag.builder.oracle;

import io.graphrag.builder.index.BodyShape;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class LlmRequestTest {
    @Test
    void carriesBodyOnlySourceAndSeparatedFieldConstraints() {  // REQ-014
        var fields = List.of(new BodyShape.BodyField("couponCode", "java.lang.String"));
        var req = new LlmRequest("post-api-coupons", "if (couponCode.startsWith(\"GOLD\")) {...}",
                fields, Map.of("couponCode", "[A-Z]{4}-\\d{4}"), Set.of(), "claude-haiku-4-5-20251001");
        assertThat(req.handlerSource()).doesNotContain("class ").doesNotContain("import ");
        assertThat(req.patternByField()).containsEntry("couponCode", "[A-Z]{4}-\\d{4}");
        assertThat(req.fields()).extracting(BodyShape.BodyField::name).containsExactly("couponCode");
    }

    @Test
    void emptyFieldValues() {
        assertThat(LlmFieldValues.empty().stringValuesByField()).isEmpty();
    }
}
```

- [ ] **Step 3: 실패 확인** — Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.oracle.LlmRequestTest'`. Expected: FAIL (LlmRequest/LlmFieldValues 미존재, 컴파일 에러).

- [ ] **Step 4: 레코드 구현** —

```java
// LlmFieldValues.java
package io.graphrag.builder.oracle;
import java.util.List;
import java.util.Map;
public record LlmFieldValues(Map<String, List<String>> stringValuesByField) {
    public static LlmFieldValues empty() { return new LlmFieldValues(Map.of()); }
}
```

```java
// LlmRequest.java
package io.graphrag.builder.oracle;
import io.graphrag.builder.index.BodyShape;
import java.util.List;
import java.util.Map;
import java.util.Set;
public record LlmRequest(String endpointId, String handlerSource,
                         List<BodyShape.BodyField> fields,
                         Map<String, String> patternByField,
                         Set<String> emailFields, String modelId) {
}
```

- [ ] **Step 5: 통과 확인** — Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.oracle.LlmRequestTest'`. Expected: PASS.

- [ ] **Step 6: 커밋** —

```bash
git add gradle/libs.versions.toml graph-rag-builder/build.gradle.kts \
  graph-rag-builder/src/main/java/io/graphrag/builder/oracle/LlmFieldValues.java \
  graph-rag-builder/src/main/java/io/graphrag/builder/oracle/LlmRequest.java \
  graph-rag-builder/src/test/java/io/graphrag/builder/oracle/LlmRequestTest.java
git commit -m "feat(oracle): Anthropic SDK 의존 + LlmRequest/LlmFieldValues 값 레코드 (REQ-014)"
```

---

## Task 2: LlmValueCache (키·classpath read·fs write·write-fail 관용)

**REQ-IDs:** REQ-003, REQ-004, REQ-015(write 실패)

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/oracle/LlmValueCache.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/oracle/LlmValueCacheTest.java`

**Interfaces:**
- Consumes: `LlmFieldValues`, `BodyShape.BodyField`.
- Produces:
  - `static String key(String endpointId, String handlerSource, List<BodyShape.BodyField> fields, String modelId)` — sha256 hex.
  - `Optional<LlmFieldValues> read(String key)` — classpath `/llm-oracle-cache/<key>.json`.
  - `void write(String key, LlmFieldValues values)` — fs `src/main/resources/llm-oracle-cache/`; 실패 시 `log.warn` 삼킴.
  - 생성자 `LlmValueCache(Path writeDir)` + `static LlmValueCache defaultClasspath()` (writeDir=`graph-rag-builder/src/main/resources/llm-oracle-cache`).

- [ ] **Step 1: 실패 테스트 작성** — `LlmValueCacheTest.java`:

```java
package io.graphrag.builder.oracle;

import io.graphrag.builder.index.BodyShape;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;

class LlmValueCacheTest {
    private static final List<BodyShape.BodyField> F =
            List.of(new BodyShape.BodyField("b", "java.lang.String"),
                    new BodyShape.BodyField("a", "java.lang.String"));

    @Test
    void keyStableUnderFieldOrder() {  // REQ-003
        var reordered = List.of(F.get(1), F.get(0));
        assertThat(LlmValueCache.key("e1", "body", F, "m"))
                .isEqualTo(LlmValueCache.key("e1", "body", reordered, "m"));
    }

    @Test
    void keyChangesOnBodyOrModel() {  // REQ-003
        String base = LlmValueCache.key("e1", "body", F, "m");
        assertThat(LlmValueCache.key("e1", "BODY2", F, "m")).isNotEqualTo(base);
        assertThat(LlmValueCache.key("e1", "body", F, "m2")).isNotEqualTo(base);
        assertThat(LlmValueCache.key("e2", "body", F, "m")).isNotEqualTo(base);
    }

    @Test
    void writeThenReadRoundTrips(@TempDir Path dir) {  // REQ-004
        var cache = new LlmValueCache(dir);
        var key = "deadbeef";
        cache.write(key, new LlmFieldValues(Map.of("code", List.of("GOLD-1234"))));
        // read는 classpath 기반이므로 이 테스트는 fs 직접 검증으로 대체:
        assertThat(dir.resolve(key + ".json")).exists();
    }

    @Test
    void readMissReturnsEmpty() {  // REQ-004
        assertThat(new LlmValueCache(Path.of("/tmp")).read("no-such-key")).isEqualTo(Optional.empty());
    }

    @Test
    void writeFailureIsSwallowed() {  // REQ-015
        var cache = new LlmValueCache(Path.of("/proc/should-not-be-writable-xyz"));
        // 예외 없이 반환되어야 함
        cache.write("k", new LlmFieldValues(Map.of("a", List.of("x"))));
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.oracle.LlmValueCacheTest'`. Expected: FAIL (LlmValueCache 미존재).

- [ ] **Step 3: 구현** —

```java
package io.graphrag.builder.oracle;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphrag.builder.index.BodyShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

public final class LlmValueCache {
    private static final Logger log = LoggerFactory.getLogger(LlmValueCache.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CP_PREFIX = "/llm-oracle-cache/";
    private final Path writeDir;

    public LlmValueCache(Path writeDir) { this.writeDir = writeDir; }

    public static LlmValueCache defaultClasspath() {
        return new LlmValueCache(Path.of("graph-rag-builder/src/main/resources/llm-oracle-cache"));
    }

    public static String key(String endpointId, String handlerSource,
                             List<BodyShape.BodyField> fields, String modelId) {
        TreeSet<String> sorted = new TreeSet<>();
        for (BodyShape.BodyField f : fields) {
            sorted.add(f.name() + ":" + f.javaType());
        }
        String canonical = endpointId + "\n" + handlerSource + "\n"
                + String.join(",", sorted) + "\n" + modelId;
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public Optional<LlmFieldValues> read(String key) {
        try (InputStream in = LlmValueCache.class.getResourceAsStream(CP_PREFIX + key + ".json")) {
            if (in == null) return Optional.empty();
            return Optional.of(MAPPER.readValue(in, LlmFieldValues.class));
        } catch (Exception e) {
            log.warn("llm cache read failed for key {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    public void write(String key, LlmFieldValues values) {
        try {
            Files.createDirectories(writeDir);
            Files.writeString(writeDir.resolve(key + ".json"), MAPPER.writeValueAsString(values));
        } catch (Exception e) {
            log.warn("llm cache write failed for key {} (kept value, uncached): {}", key, e.getMessage());
        }
    }
}
```

- [ ] **Step 4: 통과 확인** — Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.oracle.LlmValueCacheTest'`. Expected: PASS.

- [ ] **Step 5: 커밋** —

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/oracle/LlmValueCache.java \
  graph-rag-builder/src/test/java/io/graphrag/builder/oracle/LlmValueCacheTest.java
git commit -m "feat(oracle): LlmValueCache 키/classpath read/fs write + write-fail 관용 (REQ-003,004,015)"
```

---

## Task 3: ShapeGate (그라운딩 — 존재·String 타입만)

**REQ-IDs:** REQ-007

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/oracle/ShapeGate.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/oracle/ShapeGateTest.java`

**Interfaces:**
- Consumes: `LlmFieldValues`, `BodyShape`.
- Produces: `static Map<String, java.util.Set<String>> filter(LlmFieldValues raw, BodyShape shape)` — 존재하는 `java.lang.String` 필드만, 값은 TreeSet.

- [ ] **Step 1: 실패 테스트 작성** —

```java
package io.graphrag.builder.oracle;

import io.graphrag.builder.index.BodyShape;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ShapeGateTest {
    private static final BodyShape SHAPE = new BodyShape("Dto",
            List.of(new BodyShape.BodyField("couponCode", "java.lang.String"),
                    new BodyShape.BodyField("quantity", "int")), false);

    @Test
    void acceptsExistingStringField() {  // REQ-007
        var out = ShapeGate.filter(new LlmFieldValues(Map.of("couponCode", List.of("GOLD-1234"))), SHAPE);
        assertThat(out).containsOnlyKeys("couponCode");
        assertThat(out.get("couponCode")).containsExactly("GOLD-1234");
    }

    @Test
    void rejectsNonExistentField() {  // REQ-007
        assertThat(ShapeGate.filter(new LlmFieldValues(Map.of("ghost", List.of("x"))), SHAPE)).isEmpty();
    }

    @Test
    void rejectsNonStringField() {  // REQ-007
        assertThat(ShapeGate.filter(new LlmFieldValues(Map.of("quantity", List.of("5"))), SHAPE)).isEmpty();
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.oracle.ShapeGateTest'`. Expected: FAIL.

- [ ] **Step 3: 구현** —

```java
package io.graphrag.builder.oracle;

import io.graphrag.builder.index.BodyShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class ShapeGate {
    private static final Logger log = LoggerFactory.getLogger(ShapeGate.class);
    private ShapeGate() {}

    public static Map<String, Set<String>> filter(LlmFieldValues raw, BodyShape shape) {
        Map<String, String> typeByName = new TreeMap<>();
        for (BodyShape.BodyField f : shape.fields()) typeByName.put(f.name(), f.javaType());
        Map<String, Set<String>> out = new TreeMap<>();
        for (var e : raw.stringValuesByField().entrySet()) {
            String type = typeByName.get(e.getKey());
            if (type == null) {
                log.warn("llm oracle: dropping value for unknown field {}", e.getKey());
                continue;
            }
            if (!type.equals("java.lang.String")) {
                log.warn("llm oracle: dropping non-String field {} ({})", e.getKey(), type);
                continue;
            }
            out.computeIfAbsent(e.getKey(), k -> new TreeSet<>()).addAll(e.getValue());
        }
        return out;
    }
}
```

- [ ] **Step 4: 통과 확인** — Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.oracle.ShapeGateTest'`. Expected: PASS.

- [ ] **Step 5: 커밋** —

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/oracle/ShapeGate.java \
  graph-rag-builder/src/test/java/io/graphrag/builder/oracle/ShapeGateTest.java
git commit -m "feat(oracle): ShapeGate 그라운딩 — 존재·String 필드만 수용 (REQ-007)"
```

---

## Task 4: HandlerSourceExtractor (Spoon 메서드 본문)

**REQ-IDs:** REQ-008

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/oracle/HandlerSourceExtractor.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/oracle/HandlerSourceExtractorTest.java`

**Interfaces:**
- Produces: `HandlerSourceExtractor(Path srcDir)` + `String extract(String handlerClass, String handlerMethod)` — 메서드 본문 텍스트(없으면 빈 문자열).

참고 패턴: 기존 `ConstraintExtractor`/`LiteralExtractor`가 Spoon `Launcher`로 `srcDir`를 파싱하는 방식을 따른다(`graph-rag-builder/src/main/java/io/graphrag/builder/index/ConstraintExtractor.java`의 Launcher 구성 참조).

- [ ] **Step 1: 테스트 픽스처 + 실패 테스트** — 테스트는 `graph-rag-builder/src/test/resources/sample-src/`의 기존 fixture를 재사용한다. 본문 추출 검증:

```java
package io.graphrag.builder.oracle;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class HandlerSourceExtractorTest {
    private static final Path SRC = Path.of("src/test/resources/sample-src");

    @Test
    void extractsMethodBody() {  // REQ-008
        var ex = new HandlerSourceExtractor(SRC);
        String body = ex.extract("io.graphrag.sample.validation.ValidatedController", "create");
        // ValidatedController.create 본문이 비어있지 않고 메서드 시그니처/필드 비교를 포함
        assertThat(body).isNotBlank();
    }

    @Test
    void missingMethodReturnsEmpty() {  // REQ-008
        var ex = new HandlerSourceExtractor(SRC);
        assertThat(ex.extract("io.graphrag.sample.validation.ValidatedController", "noSuch")).isEmpty();
    }
}
```

> **선행 확인**: `src/test/resources/sample-src/io/graphrag/sample/validation/`에 `ValidatedController`가 있는지 확인(`ValidatedRequest`는 존재). 없으면 이 Step에서 최소 컨트롤러를 추가한다:
> ```java
> // src/test/resources/sample-src/io/graphrag/sample/validation/ValidatedController.java
> package io.graphrag.sample.validation;
> public class ValidatedController {
>     public String create(ValidatedRequest req) {
>         if (req.code().startsWith("ABC")) { return "special"; }
>         return "ok";
>     }
> }
> ```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.oracle.HandlerSourceExtractorTest'`. Expected: FAIL.

- [ ] **Step 3: 구현** — Spoon으로 `srcDir` 파싱 → `CtType`에서 메서드 찾기 → `CtMethod.getBody().toString()` 반환:

```java
package io.graphrag.builder.oracle;

import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;

import java.nio.file.Path;

public final class HandlerSourceExtractor {
    private final Path srcDir;
    private CtModel model;  // lazy 단일 파싱

    public HandlerSourceExtractor(Path srcDir) { this.srcDir = srcDir; }

    private CtModel model() {
        if (model == null) {
            Launcher launcher = new Launcher();
            launcher.getEnvironment().setNoClasspath(true);
            launcher.addInputResource(srcDir.toString());
            launcher.buildModel();
            model = launcher.getModel();
        }
        return model;
    }

    public String extract(String handlerClass, String handlerMethod) {
        CtType<?> type = model().getAllTypes().stream()
                .filter(t -> t.getQualifiedName().equals(handlerClass))
                .findFirst().orElse(null);
        if (type == null) return "";
        return type.getMethodsByName(handlerMethod).stream()
                .map(CtMethod::getBody)
                .filter(java.util.Objects::nonNull)
                .map(Object::toString)
                .findFirst().orElse("");
    }
}
```

- [ ] **Step 4: 통과 확인** — Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.oracle.HandlerSourceExtractorTest'`. Expected: PASS.

- [ ] **Step 5: 커밋** —

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/oracle/HandlerSourceExtractor.java \
  graph-rag-builder/src/test/java/io/graphrag/builder/oracle/HandlerSourceExtractorTest.java \
  graph-rag-builder/src/test/resources/sample-src/io/graphrag/sample/validation/ValidatedController.java
git commit -m "feat(oracle): HandlerSourceExtractor — Spoon 메서드 본문 추출 (REQ-008)"
```

---

## Task 5: EndpointFieldSelector (엄격검증 필드 선별)

**REQ-IDs:** REQ-006

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/oracle/EndpointFieldSelector.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/oracle/EndpointFieldSelectorTest.java`

**Interfaces:**
- Consumes: `BodyShape.BodyField`, `ValidationConstraintExtractor.FieldConstraint`, `Map<String,List<String>> enumConstants`(Java enum 타입 판별).
- Produces:
  - `record Selected(List<BodyShape.BodyField> fields, Map<String,String> patternByField, Set<String> emailFields)`.
  - `static Selected select(List<BodyShape.BodyField> fields, Map<String,List<FieldConstraint>> constraints, Map<String,List<String>> enumConstants)`.
  - 도메인 코드 키워드 상수: `{"status","type","code","tier","grade","category","level"}`.

- [ ] **Step 1: 실패 테스트** —

```java
package io.graphrag.builder.oracle;

import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.ValidationConstraintExtractor.FieldConstraint;
import io.graphrag.builder.index.ValidationConstraintExtractor.Kind;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class EndpointFieldSelectorTest {
    @Test
    void selectsPatternEmailStringSkipsNumeric() {  // REQ-006
        var fields = List.of(
            new BodyShape.BodyField("code", "java.lang.String"),
            new BodyShape.BodyField("contact", "java.lang.String"),
            new BodyShape.BodyField("quantity", "int"));
        var constraints = Map.of(
            "code", List.of(new FieldConstraint("code", Kind.PATTERN, 0, "[A-Z]{3}")),
            "contact", List.of(new FieldConstraint("contact", Kind.EMAIL, 0, null)));
        var sel = EndpointFieldSelector.select(fields, constraints, Map.of());
        assertThat(sel.fields()).extracting(BodyShape.BodyField::name)
            .containsExactlyInAnyOrder("code", "contact");
        assertThat(sel.patternByField()).containsEntry("code", "[A-Z]{3}");
        assertThat(sel.emailFields()).containsExactly("contact");
    }

    @Test
    void selectsDomainCodeKeywordStringExcludesEnumType() {  // REQ-006
        var fields = List.of(
            new BodyShape.BodyField("status", "java.lang.String"),
            new BodyShape.BodyField("tier", "io.graphrag.sample.Tier"));   // enum 타입
        var sel = EndpointFieldSelector.select(fields, Map.of(),
            Map.of("io.graphrag.sample.Tier", List.of("GOLD", "SILVER")));
        assertThat(sel.fields()).extracting(BodyShape.BodyField::name).containsExactly("status");
    }

    @Test
    void emptyWhenNothingStrict() {  // REQ-006
        var sel = EndpointFieldSelector.select(
            List.of(new BodyShape.BodyField("plainName", "java.lang.String")), Map.of(), Map.of());
        assertThat(sel.fields()).isEmpty();
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.oracle.EndpointFieldSelectorTest'`. Expected: FAIL.

- [ ] **Step 3: 구현** —

```java
package io.graphrag.builder.oracle;

import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.ValidationConstraintExtractor.FieldConstraint;
import io.graphrag.builder.index.ValidationConstraintExtractor.Kind;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class EndpointFieldSelector {
    private static final Set<String> DOMAIN_KEYWORDS =
            Set.of("status", "type", "code", "tier", "grade", "category", "level");
    private EndpointFieldSelector() {}

    public record Selected(List<BodyShape.BodyField> fields,
                           Map<String, String> patternByField, Set<String> emailFields) {}

    public static Selected select(List<BodyShape.BodyField> fields,
                                  Map<String, List<FieldConstraint>> constraints,
                                  Map<String, List<String>> enumConstants) {
        List<BodyShape.BodyField> chosen = new ArrayList<>();
        Map<String, String> patterns = new TreeMap<>();
        Set<String> emails = new TreeSet<>();
        Set<String> enumTypes = enumConstants.keySet();
        for (BodyShape.BodyField f : fields) {
            if (!f.javaType().equals("java.lang.String")) continue;  // String만
            boolean strict = false;
            for (FieldConstraint c : constraints.getOrDefault(f.name(), List.of())) {
                if (c.kind() == Kind.PATTERN && c.strArg() != null) {
                    patterns.put(f.name(), c.strArg()); strict = true;
                } else if (c.kind() == Kind.EMAIL) {
                    emails.add(f.name()); strict = true;
                }
            }
            // 도메인 코드 휴리스틱: 필드명 키워드 + (enum 타입이 아님 — String이라 이미 enum 타입 아님)
            if (!strict && DOMAIN_KEYWORDS.contains(f.name().toLowerCase())) {
                strict = true;
            }
            if (strict && !enumTypes.contains(f.javaType())) {
                chosen.add(f);
            }
        }
        return new Selected(chosen, patterns, emails);
    }
}
```

- [ ] **Step 4: 통과 확인** — Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.oracle.EndpointFieldSelectorTest'`. Expected: PASS.

- [ ] **Step 5: 커밋** —

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/oracle/EndpointFieldSelector.java \
  graph-rag-builder/src/test/java/io/graphrag/builder/oracle/EndpointFieldSelectorTest.java
git commit -m "feat(oracle): EndpointFieldSelector — @Pattern/@Email/도메인코드 선별 (REQ-006)"
```

---

## Task 6: LlmValueClient + AnthropicValueClient (lazy, structured, temp 0)

**REQ-IDs:** REQ-005(lazy 생성), REQ-013(structured·temp0·모델핀)

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/oracle/LlmValueClient.java`
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/oracle/AnthropicValueClient.java`
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/oracle/FakeValueClient.java` (test helper)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/oracle/AnthropicValueClientTest.java`

**Interfaces:**
- Produces:
  - `interface LlmValueClient { LlmFieldValues generate(LlmRequest request); }`
  - `AnthropicValueClient implements LlmValueClient` + `static AnthropicValueClient fromEnv(String modelId)` (키 없어도 생성 성공). `hasApiKey()` → boolean.
  - test `FakeValueClient implements LlmValueClient` — 생성자에 고정 응답 + 호출 카운터/예외 토글.

> **SDK API 확인(구현 시점)**: claude-api 스킬 Java 문서 기준 structured 출력은
> `StructuredMessageCreateParams<T>` + `.outputConfig(T.class)` + `client.messages().create(params)`,
> 클라이언트 `AnthropicOkHttpClient.fromEnv()`. temperature는 `.temperature(0.0)`(Haiku/Sonnet은 샘플링
> 파라미터 허용). 핀 버전(2.34.0)의 실제 메서드명을 컴파일로 최종 확인하고, 불일치 시 `javap`/컴파일
> 에러로 교정한다. Haiku 4.5는 `thinking` 불필요.

- [ ] **Step 1: 실패 테스트 + Fake** — `FakeValueClient.java`:

```java
package io.graphrag.builder.oracle;
import java.util.Map;
public final class FakeValueClient implements LlmValueClient {
    private final LlmFieldValues response;
    private final boolean throwOnCall;
    public int calls = 0;
    public FakeValueClient(LlmFieldValues response) { this(response, false); }
    public FakeValueClient(LlmFieldValues response, boolean throwOnCall) {
        this.response = response; this.throwOnCall = throwOnCall;
    }
    @Override public LlmFieldValues generate(LlmRequest r) {
        calls++;
        if (throwOnCall) throw new RuntimeException("simulated API failure");
        return response;
    }
    public static FakeValueClient of(String field, String value) {
        return new FakeValueClient(new LlmFieldValues(Map.of(field, java.util.List.of(value))));
    }
}
```

`AnthropicValueClientTest.java`:

```java
package io.graphrag.builder.oracle;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatCode;

class AnthropicValueClientTest {
    @Test
    void constructsWithoutApiKey() {  // REQ-005
        // 키 없는 환경에서도 생성자 자체는 실패하지 않아야 한다(lazy).
        assertThatCode(() -> AnthropicValueClient.fromEnv("claude-haiku-4-5-20251001"))
                .doesNotThrowAnyException();
    }

    @Test
    void modelIdPinned() {  // REQ-013
        var c = AnthropicValueClient.fromEnv("claude-sonnet-4-6");
        org.assertj.core.api.Assertions.assertThat(c.modelId()).isEqualTo("claude-sonnet-4-6");
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.oracle.AnthropicValueClientTest'`. Expected: FAIL.

- [ ] **Step 3: 구현** — interface + 클라이언트. 키는 lazy(생성 시 SDK 클라이언트 미생성; `generate` 호출 시점에 `AnthropicOkHttpClient.fromEnv()` 생성 — 키 없으면 그때 SDK가 예외, 이는 REQ-015에서 LlmOracle가 삼킴):

```java
// LlmValueClient.java
package io.graphrag.builder.oracle;
public interface LlmValueClient {
    LlmFieldValues generate(LlmRequest request);
}
```

```java
// AnthropicValueClient.java (스켈레톤 — SDK 호출부는 핀 버전 API로 컴파일 확정)
package io.graphrag.builder.oracle;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
// structured 타입은 핀 버전에서 import 확정

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class AnthropicValueClient implements LlmValueClient {
    private final String modelId;
    private AnthropicClient client;   // lazy

    private AnthropicValueClient(String modelId) { this.modelId = modelId; }
    public static AnthropicValueClient fromEnv(String modelId) { return new AnthropicValueClient(modelId); }
    public String modelId() { return modelId; }
    public boolean hasApiKey() {
        String k = System.getenv("ANTHROPIC_API_KEY");
        return k != null && !k.isBlank();
    }

    @Override
    public LlmFieldValues generate(LlmRequest request) {
        if (client == null) client = AnthropicOkHttpClient.fromEnv();  // 키 없으면 여기서 SDK 예외
        // structured 출력으로 LlmFieldValues를 직접 파싱(StructuredMessageCreateParams<LlmFieldValues>)
        // 프롬프트: system=값 생성 지침, user=구조화(필드/제약) + handlerSource(본문만).
        // temperature 0, modelId 핀. 응답을 LlmFieldValues로 반환.
        // (정확한 빌더 호출은 핀 버전 API로 컴파일 확정.)
        throw new UnsupportedOperationException("filled during impl with pinned SDK API");
    }
}
```

> 구현 노트: `generate`의 SDK 본문은 핀 버전 컴파일로 확정한다. 단위 테스트(위)는 **키 없는 생성·모델
> 핀만** 검증(실 API 무호출). 실제 호출 경로는 E2E에서 캐시로 우회되므로 CI에서 실행되지 않는다.

- [ ] **Step 4: 통과 확인** — Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.oracle.AnthropicValueClientTest'`. Expected: PASS.

- [ ] **Step 5: 커밋** —

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/oracle/LlmValueClient.java \
  graph-rag-builder/src/main/java/io/graphrag/builder/oracle/AnthropicValueClient.java \
  graph-rag-builder/src/test/java/io/graphrag/builder/oracle/FakeValueClient.java \
  graph-rag-builder/src/test/java/io/graphrag/builder/oracle/AnthropicValueClientTest.java
git commit -m "feat(oracle): LlmValueClient + AnthropicValueClient(lazy, 모델핀) (REQ-005,013)"
```

---

## Task 7: LlmOracle (오케스트레이션 — SPI·결정성·skip·에러관용)

**REQ-IDs:** REQ-001, REQ-002, REQ-005(skip), REQ-015(client-fail)

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/oracle/LlmOracle.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/oracle/LlmOracleTest.java`

**Interfaces:**
- Consumes: `IndexResult`, `ValidationConstraintExtractor`, `HandlerSourceExtractor`, `EndpointFieldSelector`, `ShapeGate`, `LlmValueClient`, `LlmValueCache`, `Map<String,List<String>> enumConstants`, `String modelId`.
- Produces: `LlmOracle implements InputOracle` — `name()="llm"`, `analyze(SutCode) → InputCandidates`(strings만).

> 엔드포인트→shape 매핑은 `bodyShapeFor` 로직(BODY/FORM param → bodyShapes.get(javaType))을 oracle
> 패키지에 5줄 private helper로 복제(cli 패키지 의존 회피). enumConstants는 `StaticIndex`에서 이미
> 추출되어 있으므로 BuilderCli 배선에서 주입(`si.enumConstants()` 또는 동등).

- [ ] **Step 1: 실패 테스트** (FakeValueClient 사용, API 무호출) —

```java
package io.graphrag.builder.oracle;

import io.graphrag.builder.index.*;
import io.graphrag.model.Endpoint;
import io.graphrag.model.EndpointParam;
import io.graphrag.model.ParamKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class LlmOracleTest {
    // 최소 IndexResult: @Pattern code 필드를 가진 1개 엔드포인트
    private IndexResult index() {
        var shape = new BodyShape("io.x.Dto",
            List.of(new BodyShape.BodyField("code", "java.lang.String")), false);
        var ep = new Endpoint("post-x", "POST", "/x", "io.x.XController", "create",
            List.of(new EndpointParam("body", "io.x.Dto", ParamKind.BODY)), false);
        return new IndexResult(List.of(ep), Map.of("io.x.Dto", shape), java.util.Set.of("post-x"));
    }
    // @Pattern 제약을 돌려주는 가짜 ValidationConstraintExtractor 대체:
    // 실제로는 srcDir 파싱이 필요하므로, 테스트는 sample-src fixture(ValidatedController/Request)를 쓴다.

    @Test
    void implementsSpiAndContributesStringsOnly(@TempDir Path writeDir) {
        // 상세 배선은 sample-src fixture 기반(아래 노트). 여기서는 핵심 단언만 표기.
        // name()=="llm", analyze 결과 strings 채널만 채워지고 numeric/reals/tuples 비어있음.
    }
}
```

> **테스트 설계 노트**: `LlmOracle`는 `srcDir` 파싱(ValidationConstraintExtractor·HandlerSourceExtractor)
> 이 필요하므로, 단위 테스트는 `src/test/resources/sample-src`의 `ValidatedController`/`ValidatedRequest`
> (Task 4에서 보강)를 `SutCode(srcDir, jar=null)`로 가리키고, `FakeValueClient.of("code","ABC")`를 주입한다.
> 검증 항목:
> - `name()` == `"llm"`. [REQ-001]
> - `analyze`가 `code`를 `strings`에, numeric/tuples/reals 비움. [REQ-001]
> - 같은 입력 2회 호출 결과 동일. [REQ-002] (`deterministicOutputOnSameInput`)
> - 캐시에 미리 write 후 호출 시 `FakeValueClient.calls == 0`. [REQ-002] (`cacheHitSkipsClientCall`)
> - 캐시 miss + `FakeValueClient`가 키 없음 상황 모사(client가 사용 안 됨 분기) → skip 빈 기여. 단,
>   FakeValueClient는 항상 응답하므로 "키 없음 skip"은 `AnthropicValueClient.hasApiKey()==false` 분기로
>   LlmOracle가 client 호출 자체를 건너뛰는 것을 별도 테스트(`noKeyCacheMissSkips`, hasApiKey=false인
>   stub client)로 검증. [REQ-005]
> - `throwOnCall=true` FakeValueClient → 해당 엔드포인트만 skip, 예외 전파 없음. [REQ-015]
>   (`clientFailureSkipsEndpointOnly`)

각 단언을 개별 `@Test`로 작성한다(REQ별 1 테스트). 위 클래스에 메서드 추가.

- [ ] **Step 2: 실패 확인** — Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.oracle.LlmOracleTest'`. Expected: FAIL.

- [ ] **Step 3: 구현** —

```java
package io.graphrag.builder.oracle;

import io.graphrag.builder.index.*;
import io.graphrag.builder.index.ValidationConstraintExtractor.FieldConstraint;
import io.graphrag.model.Endpoint;
import io.graphrag.model.EndpointParam;
import io.graphrag.model.ParamKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class LlmOracle implements InputOracle {
    private static final Logger log = LoggerFactory.getLogger(LlmOracle.class);
    private final IndexResult index;
    private final ValidationConstraintExtractor valid;
    private final HandlerSourceExtractor handlerSrc;
    private final LlmValueClient client;
    private final LlmValueCache cache;
    private final Map<String, List<String>> enumConstants;
    private final String modelId;
    private final boolean clientUsable;   // AnthropicValueClient면 hasApiKey, Fake면 true

    public LlmOracle(IndexResult index, ValidationConstraintExtractor valid,
                     HandlerSourceExtractor handlerSrc, LlmValueClient client,
                     LlmValueCache cache, Map<String, List<String>> enumConstants,
                     String modelId, boolean clientUsable) {
        this.index = index; this.valid = valid; this.handlerSrc = handlerSrc;
        this.client = client; this.cache = cache; this.enumConstants = enumConstants;
        this.modelId = modelId; this.clientUsable = clientUsable;
    }

    @Override public String name() { return "llm"; }

    @Override
    public InputCandidates analyze(SutCode sut) {
        InputCandidates acc = InputCandidates.empty();
        for (Endpoint ep : index.endpoints()) {
            BodyShape shape = bodyShapeFor(ep, index.bodyShapes());
            if (shape == null) continue;
            Map<String, List<FieldConstraint>> constraints =
                    valid.extract(sut.srcDir(), shape.javaType());
            var selected = EndpointFieldSelector.select(shape.fields(), constraints, enumConstants);
            if (selected.fields().isEmpty()) continue;
            String body = handlerSrc.extract(ep.handlerClass(), ep.handlerMethod());
            String key = LlmValueCache.key(ep.id(), body, selected.fields(), modelId);
            LlmFieldValues vals = cache.read(key).orElse(null);
            if (vals == null) {
                if (!clientUsable) {
                    log.info("llm oracle: cache miss + no API key → skip {}", ep.id());
                    continue;
                }
                try {
                    vals = client.generate(new LlmRequest(ep.id(), body, selected.fields(),
                            selected.patternByField(), selected.emailFields(), modelId));
                    cache.write(key, vals);
                } catch (Exception e) {
                    log.warn("llm oracle: generate failed for {} → skip: {}", ep.id(), e.getMessage());
                    continue;
                }
            }
            Map<String, Set<String>> gated = ShapeGate.filter(vals, shape);
            if (!gated.isEmpty()) {
                acc = acc.merge(new InputCandidates(Map.of(), gated));
            }
        }
        return acc;
    }

    private static BodyShape bodyShapeFor(Endpoint ep, Map<String, BodyShape> shapes) {
        return ep.params().stream()
                .filter(p -> p.kind() == ParamKind.BODY || p.kind() == ParamKind.FORM)
                .map(p -> shapes.get(p.javaType()))
                .filter(Objects::nonNull)
                .findFirst().orElse(null);
    }
}
```

> 주의: `new InputCandidates(Map.of(), gated)`는 2-arg 호환 ctor(numeric, strings)를 사용 — strings만
> 채우고 나머지 채널 비움. [design 불변식]

- [ ] **Step 4: 통과 확인** — Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.oracle.LlmOracleTest'`. Expected: PASS.

- [ ] **Step 5: 커밋** —

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/oracle/LlmOracle.java \
  graph-rag-builder/src/test/java/io/graphrag/builder/oracle/LlmOracleTest.java
git commit -m "feat(oracle): LlmOracle 오케스트레이션 — SPI/결정성/skip/에러관용 (REQ-001,002,005,015)"
```

---

## Task 8: BuilderCli 배선 + BuildConfig 플래그 (off no-op, 패키지 경계)

**REQ-IDs:** REQ-009, REQ-010

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuildConfig.java`
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/cli/BuilderCliLlmFlagTest.java`
- Verify: `graph-rag-builder/src/test/java/io/graphrag/builder/arch/NoLlmDependencyTest.java` (기존, 변경 없음)

**Interfaces:**
- Produces: `BuildConfig.llmOracle()` (boolean), `BuildConfig.llmModel()` (String).

- [ ] **Step 1: 실패 테스트** — `BuilderCliLlmFlagTest.java` (parseArgs→BuildConfig 매핑 검증):

```java
package io.graphrag.builder.cli;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BuilderCliLlmFlagTest {
    @Test
    void flagAbsentMeansOffAndDefaultModel() {  // REQ-009
        var opts = BuilderCli.parseArgs(new String[]{"--sut-id", "x"});
        assertThat(opts.containsKey("--llm-oracle")).isFalse();
    }

    @Test
    void flagAndModelParse() {  // REQ-009
        var opts = BuilderCli.parseArgs(
            new String[]{"--llm-oracle", "--llm-model", "claude-sonnet-4-6"});
        assertThat(opts.containsKey("--llm-oracle")).isTrue();
        assertThat(opts.getOrDefault("--llm-model", "claude-haiku-4-5-20251001"))
            .isEqualTo("claude-sonnet-4-6");
    }
}
```

> `parseArgs`는 이미 package-private static(현 814행). flag-only 인자(`--llm-oracle`)는 값 없이
> `options.put(key, "")` 되는 기존 동작을 활용. `BuildConfig` 매핑은 main()에서 수행.

- [ ] **Step 2: 실패 확인** — Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.cli.BuilderCliLlmFlagTest'`. Expected: FAIL 또는 PASS(파싱만이면 PASS일 수 있음). 핵심 실패는 다음 Step의 BuildConfig 필드 미존재.

- [ ] **Step 3: BuildConfig 필드 추가** — canonical record에 `boolean llmOracle, String llmModel` 추가(끝에). compact ctor 기본값(`llmModel = llmModel == null ? "claude-haiku-4-5-20251001" : llmModel;`). 기존 4개 편의 생성자 위임 호출에 `false, "claude-haiku-4-5-20251001"` 추가.

- [ ] **Step 4: main() 매핑 + explore() 배선 + CLI help** —
  - main()의 `BuildConfig` 생성에 `options.containsKey("--llm-oracle")`,
    `options.getOrDefault("--llm-model", "claude-haiku-4-5-20251001")` 전달.
  - `explore()` 오라클 merge 지점(현 573–578)에:
    ```java
    if (config.llmOracle()) {
        var handlerSrc = new io.graphrag.builder.oracle.HandlerSourceExtractor(config.sutSrc());
        var anthropic = io.graphrag.builder.oracle.AnthropicValueClient.fromEnv(config.llmModel());
        var llm = new io.graphrag.builder.oracle.LlmOracle(index,
                new io.graphrag.builder.index.ValidationConstraintExtractor(),
                handlerSrc, anthropic, io.graphrag.builder.oracle.LlmValueCache.defaultClasspath(),
                enumConstants, config.llmModel(), anthropic.hasApiKey());
        inputCandidates = inputCandidates.merge(llm.analyze(sutCode));
        log.info("llm oracle merged (model={}, apiKey={})", config.llmModel(), anthropic.hasApiKey());
    }
    ```
    (`enumConstants`는 explore() 스코프에 이미 존재 — 현 656행 부근. 없으면 `si.enumConstants()`로 전달.)
  - BuilderCli 상단 CLI help 주석에 `--llm-oracle`, `--llm-model` 추가.

- [ ] **Step 5: 통과 + 회귀 확인** — Run:
  `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.cli.BuilderCliLlmFlagTest' --tests 'io.graphrag.builder.arch.NoLlmDependencyTest'`
  Expected: PASS (플래그 매핑 + index 패키지 무오염).

- [ ] **Step 6: 커밋** —

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuildConfig.java \
  graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java \
  graph-rag-builder/src/test/java/io/graphrag/builder/cli/BuilderCliLlmFlagTest.java
git commit -m "feat(cli): --llm-oracle/--llm-model 플래그 + explore 배선 (REQ-009,010)"
```

---

## Task 9: E2E — fixture 컨트롤러 + 캐시 fixture + LlmOracleE2E (off/on 커버리지)

**REQ-IDs:** REQ-011, REQ-012

**Files:**
- Create: `samples/order-service/src/main/java/io/graphrag/sample/orders/CouponController.java`
- Create: `graph-rag-builder/src/main/resources/llm-oracle-cache/<computed-key>.json`
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/e2e/LlmOracleE2E.java`
- Modify: `graph-rag-builder/src/test/java/.../BuilderIntegrationTest.java` (containsExactly에 `post-api-coupons` 추가)

**Interfaces:**
- Consumes: 전 태스크 산출물 + `BuilderCli.build(BuildConfig)`.

- [ ] **Step 1: fixture 컨트롤러 추가** — `CouponController.java`:

```java
package io.graphrag.sample.orders;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** @Pattern 게이트 + 도메인 접두 분기 — LLM 값 오라클 커버리지 시연(REQ-012). */
@RestController
@RequestMapping("/api/coupons")
public class CouponController {

    public record RedeemRequest(
            @Pattern(regexp = "[A-Z]{4}-\\d{4}") String couponCode,
            @Min(1) int quantity) {
    }

    @PostMapping
    public String redeem(@Valid @RequestBody RedeemRequest req) {
        if (req.couponCode().startsWith("GOLD")) {
            return "gold-tier:" + req.quantity();   // 깊은 분기 — 유효 도메인 값에서만 도달
        }
        return "standard";
    }
}
```

- [ ] **Step 2: 캐시 키 계산 헬퍼 실행** — fixture의 핸들러 본문 해시 키를 **실제 구현으로 계산**한다.
  임시 단위 테스트(또는 `./gradlew` 일회성)로 출력:

```java
// 임시: graph-rag-builder/src/test/java/io/graphrag/builder/oracle/PrintCouponKey.java
package io.graphrag.builder.oracle;
import io.graphrag.builder.index.BodyShape;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
class PrintCouponKey {
    @Test void print() {
        var ex = new HandlerSourceExtractor(Path.of("../samples/order-service/src/main/java"));
        String body = ex.extract("io.graphrag.sample.orders.CouponController", "redeem");
        String key = LlmValueCache.key("post-api-coupons", body,
            List.of(new BodyShape.BodyField("couponCode", "java.lang.String")),
            "claude-haiku-4-5-20251001");
        System.out.println("COUPON_CACHE_KEY=" + key);
    }
}
```
  Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.oracle.PrintCouponKey'` → 로그에서 키 확보. (endpoint.id 형식은 실제 인덱서 산출값으로 확인 — `post-api-coupons`가 아닐 수 있으니 BuilderIntegrationTest의 id 규칙에 맞춤.)

- [ ] **Step 3: 캐시 fixture 수기 작성** — Step 2의 키로:

```json
// graph-rag-builder/src/main/resources/llm-oracle-cache/<COUPON_CACHE_KEY>.json
{ "stringValuesByField": { "couponCode": ["GOLD-1234"] } }
```
  그 후 `PrintCouponKey` 임시 테스트 삭제.

- [ ] **Step 4: E2E 작성(outer-loop)** — `LlmOracleE2E.java` (Docker 가드):

```java
package io.graphrag.builder.e2e;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
// build(BuildConfig) 실행 → GraphAsset에서 post-api-coupons 경로의 branchesTaken 비교

@EnabledIfSystemProperty(named = "sut.jar", matches = ".+")
class LlmOracleE2E {

    @Test @DisplayName("REQ-011: --llm-oracle off 경로 불변 + gold-tier 분기 미도달")
    void offPathDoesNotReachGoldBranch() throws Exception {
        // build(config(llmOracle=false)) → coupon 엔드포인트 path들에 gold-tier 분기 없음
    }

    @Test @DisplayName("REQ-012: 캐시된 LLM on이 gold-tier 깊은 분기 도달(커버리지 증가, API 무호출)")
    void cachedLlmOnReachesGoldBranch() throws Exception {
        // ANTHROPIC_API_KEY 미설정 보장 → build(config(llmOracle=true)) → gold-tier 분기 도달
        // off 결과 대비 covered 분기 ↑
    }
}
```

> 단언 상세는 `BuilderIntegrationTest`의 `GraphAsset`/`ExploredPath` 접근 패턴을 그대로 따른다
> (해당 테스트 본문 참조해 `branchesTaken()`/status 비교). off 실행과 on 실행을 각각 `build()`로 수행.

- [ ] **Step 5: 실패 확인(red)** — Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.e2e.LlmOracleE2E'` (Docker 환경). Expected: 초기엔 red(분기 단언 미충족) → Task 1–8 구현·캐시 fixture 후 green.

- [ ] **Step 6: BuilderIntegrationTest containsExactly 갱신** — fixture 추가로 늘어난 endpoint id를 기존 목록에 추가(의도된 변경, REQ-011).

- [ ] **Step 7: 전체 회귀 + E2E green 확인** — Run:
  `./gradlew :graph-rag-builder:test` (+ Docker e2e). Expected: 전부 PASS(off 불변, on 커버리지 증가).

- [ ] **Step 8: 커밋** —

```bash
git add samples/order-service/src/main/java/io/graphrag/sample/orders/CouponController.java \
  graph-rag-builder/src/main/resources/llm-oracle-cache/ \
  graph-rag-builder/src/test/java/io/graphrag/builder/e2e/LlmOracleE2E.java \
  graph-rag-builder/src/test/java/.../BuilderIntegrationTest.java
git commit -m "test(e2e): @Pattern fixture + 캐시 + LlmOracleE2E off/on 커버리지 (REQ-011,012)"
```

---

## Task 10: 문서 동기화 + 추적 매트릭스 green 확정

**REQ-IDs:** (전체 — 완료 정의)

**Files:**
- Modify: `docs/superpowers/requirements/2026-06-23-llm-oracle-requirements.md` (Status 🔴→🟢, Coverage 갱신)
- Modify: `README.md` (LlmOracle 사용법: `--llm-oracle`/`--llm-model`, 캐시·CI 오프라인·내부 SUT 전용·API 키 env)

- [ ] **Step 1: 매트릭스 green 갱신** — 각 REQ의 실제 통과 테스트명 대조 후 Status 갱신, Coverage `15/15 green (100%)`.
- [ ] **Step 2: README 갱신** — 새 플래그·캐시 동작·`ANTHROPIC_API_KEY`(커밋 금지)·내부 SUT 권고·결정성(캐시) 1절 추가.
- [ ] **Step 3: 전체 회귀 최종 확인** — Run: `./gradlew test` (전 모듈) + Docker e2e. Expected: GREEN.
- [ ] **Step 4: 커밋** —

```bash
git add docs/superpowers/requirements/2026-06-23-llm-oracle-requirements.md README.md
git commit -m "docs: LlmOracle 매트릭스 green + README 사용법 동기화"
```

---

## Self-Review

- **Spec coverage**: REQ-001(T7)·002(T7)·003(T2)·004(T2)·005(T6,T7)·006(T5)·007(T3)·008(T4)·009(T8)·
  010(T8)·011(T9)·012(T9)·013(T6)·014(T1)·015(T2,T7) — 15/15 매핑됨. 갭 없음.
- **Placeholder scan**: AnthropicValueClient.generate 본문은 "핀 버전 SDK API로 컴파일 확정"으로
  명시적 위임(실 API 경로는 CI 무실행, 캐시 우회). 그 외 코드 블록은 완전.
- **Type consistency**: `LlmFieldValues.stringValuesByField()`, `LlmValueCache.key/read/write`,
  `EndpointFieldSelector.Selected{fields,patternByField,emailFields}`, `ShapeGate.filter→Map<String,
  Set<String>>`, `LlmOracle` ctor(index,valid,handlerSrc,client,cache,enumConstants,modelId,clientUsable),
  `InputCandidates(numeric, strings)` 2-arg — 전 태스크 일관.

# 컬렉션 @RequestBody (List<DTO> 등) body shape 지원 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `@RequestBody`(및 Kafka/WS 페이로드)의 컬렉션 — `List`/`Set`/`Collection`/`Iterable<E>`·배열 `E[]`, 원소 E가 DTO/scalar/enum — 를 happy-path(유효 원소 1개 배열)로 탐색·캡처·생성하도록 인덱서·합성·생성기를 컬렉션-aware로 만든다.

**Architecture:** `BodyShape`에 `collection` 플래그를 더해 컬렉션을 표현한다(원소 FQN은 `javaType`, scalar/DTO는 `fields` 유무로 구분). 공유 `BodyShapeExtractor.extractFromType(CtTypeReference)`가 제네릭/배열 원소를 추출하고, `SampleInputSynthesizer`가 `ArrayNode`를 합성한다. 컬렉션 바디는 happy-only이므로 ObjectNode 변이/음수 파이프라인은 `instanceof`/`shape.collection()` 가드로 자동 우회한다. 생성기는 배열 sampleInput을 배열 body로 방출한다.

**Tech Stack:** Java 17, Gradle, JUnit 5, AssertJ, Spoon(noClasspath), Jackson(ObjectNode/ArrayNode/JsonNode), Testcontainers(통합).

**Spec:** [docs/superpowers/specs/2026-06-18-list-dto-body-shape-design.md](../specs/2026-06-18-list-dto-body-shape-design.md)

---

## File Structure

- **Modify** `graph-rag-builder/.../index/BodyShape.java` — `collection` 플래그 + 편의 ctor.
- **Modify** `graph-rag-builder/.../index/BodyShapeExtractor.java` — `extractFromType(model, CtTypeReference)`, `bodyTypeKey(CtTypeReference)`, scalar 판정(공유).
- **Modify** `graph-rag-builder/.../index/EndpointIndexer.java` — private extractBodyShape 제거, 공유본 + bodyTypeKey 사용.
- **Modify** `graph-rag-builder/.../index/KafkaListenerIndexer.java`, `WsEndpointIndexer.java` — extractFromType + bodyTypeKey.
- **Modify** `graph-rag-builder/.../run/SampleInputSynthesizer.java` — `scalarValue` 추출 + 배열 합성.
- **Modify** `graph-rag-builder/.../run/SynthesizedInput.java` — `body` 타입 `ObjectNode`→`JsonNode`.
- **Modify** `graph-rag-builder/.../explore/EndpointTarget.java` — `baseInput` `ObjectNode`→`JsonNode`.
- **Modify** `graph-rag-builder/.../run/EndpointExplorationRunner.java` — happyInput merge 가드, baseInput JsonNode, 음수-검증 skip, `bodyValues` ArrayNode unwrap.
- **Modify** `graph-rag-builder/.../explore/HeuristicExplorer.java`, `CoverageGuidedFuzzer.java`, `InputMutator.java` — `instanceof ObjectNode` 가드.
- **Modify** `graph-rag-builder/.../run/KafkaCaptureRunner.java`, `WsCaptureRunner.java` — JsonNode 페이로드 수용.
- **Modify** `test-generator/.../generator/compose/FixtureComposer.java`, `generator/Generator.java` — 배열 body 방출.
- **Create(SUT)** `samples/order-service/.../OrderBatchController.java` — `POST /api/orders/batch`, `POST /api/orders/by-ids`.
- **Tests** 각 단위 테스트 + `BuilderCollectionE2eTest`(통합).

---

## Phase 1 — E2E 수용 (outer loop, red 먼저)

### Task 1: order-service 컬렉션 엔드포인트 + 수용 테스트(red)

**Files:**
- Create: `samples/order-service/src/main/java/io/graphrag/sample/orders/OrderBatchController.java`
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/cli/BuilderCollectionE2eTest.java`

- [ ] **Step 1: SUT 컬렉션 엔드포인트 추가**

`OrderBatchController.java`:
```java
package io.graphrag.sample.orders;

import io.graphrag.sample.orders.OrderController.CreateOrderRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 컬렉션 @RequestBody 회귀 가드: List<DTO> + List<scalar>. */
@RestController
@RequestMapping("/api/orders")
public class OrderBatchController {

    public record BatchResponse(int created) {}
    public record CountResponse(long count) {}

    private final UserRepository users;
    private final OrderRepository orders;

    public OrderBatchController(UserRepository users, OrderRepository orders) {
        this.users = users;
        this.orders = orders;
    }

    @PostMapping("/batch")
    @ResponseStatus(HttpStatus.CREATED)
    public BatchResponse batch(@RequestBody List<CreateOrderRequest> requests) {
        int created = 0;
        for (CreateOrderRequest r : requests) {
            if (r.userId() == null || r.amount() == null || r.amount() <= 0 || r.type() == null) {
                continue;
            }
            User user = users.findById(r.userId()).orElse(null);
            if (user == null) {
                continue;
            }
            orders.save(new Order(user, r.amount(), r.type(), "PENDING"));
            created++;
        }
        return new BatchResponse(created);
    }

    @PostMapping("/by-ids")
    public CountResponse byIds(@RequestBody List<String> userIds) {
        long count = 0;
        for (String id : userIds) {
            count += orders.findByUser_Id(id).size();
        }
        return new CountResponse(count);
    }
}
```

- [ ] **Step 2: 수용 테스트 작성 (red — 현재는 batch가 skip되어 path 부재)**

`BuilderCollectionE2eTest.java`:
```java
package io.graphrag.builder.cli;

import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.run.AuthConfig;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.GraphAsset;
import io.graphrag.model.SqlBinding;
import io.graphrag.model.BindingOrigin;
import io.graphrag.model.ExploredPath;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 수용: 컬렉션 @RequestBody(List<DTO>·List<scalar>)가 skip되지 않고 배열 body로 탐색·캡처된다. Docker 필요. */
@Tag("integration")
@EnabledIfSystemProperty(named = "sut.jar", matches = ".+")
class BuilderCollectionE2eTest {

    @TempDir Path out;

    @Test
    void collectionRequestBody_exploredWithArrayBodyAndCapturedSql() throws Exception {
        Path sutSrc = Path.of(System.getProperty("sut.src"));
        Path sutJar = Path.of(System.getProperty("sut.jar"));
        AuthConfig auth = new AuthConfig("/api/auth/login", "admin", "password",
                "token", "Authorization", "Bearer", List.of());

        GraphAsset asset = BuilderCli.build(new BuildConfig(
                sutSrc, sutSrc.resolveSibling("resources"), sutJar, out,
                "order-service", "test",
                new DbConfig(DbConfig.Type.POSTGRES, "postgres:15", "app", "app", "app"),
                60, null, Path.of(System.getProperty("external.stubs")),
                Map.of("EXTERNAL_INVENTORY_URL", "{{wiremock}}"),
                null, null, auth, false, true, null,
                null, io.graphrag.model.RequestHeaders.empty(), List.of(), "otel"));

        // 1) batch 엔드포인트가 skip되지 않고 인덱싱+탐색됨
        assertThat(asset.endpoints()).extracting(e -> e.id()).contains("post-api-orders-batch");
        List<ExploredPath> batch = asset.paths().stream()
                .filter(p -> p.id().startsWith("post-api-orders-batch")).toList();
        assertThat(batch).as("batch가 탐색됨(skip 아님)").isNotEmpty();

        // 2) 합성 body가 JSON 배열
        assertThat(batch).anyMatch(p -> p.sampleInput() != null && p.sampleInput().isArray());

        // 3) DTO 컬렉션 happy가 INSERT를 만들고, API_PARAM bind가 있음
        ExploredPath happy = batch.stream().filter(p -> p.expectedStatus() / 100 == 2)
                .findFirst().orElseThrow();
        List<CapturedSql> sql = asset.sql().stream()
                .filter(s -> s.pathId().equals(happy.id())).toList();
        assertThat(sql).anyMatch(s -> s.sqlKind().equals("INSERT") && s.tableName().equals("orders"));
        assertThat(sql.stream().flatMap(s -> s.bindings().stream()))
                .anyMatch(b -> b.origin() == BindingOrigin.API_PARAM);

        // 5) scalar 컬렉션도 skip되지 않음
        assertThat(asset.endpoints()).extracting(e -> e.id()).contains("post-api-orders-by-ids");
        assertThat(asset.paths()).anyMatch(p -> p.id().startsWith("post-api-orders-by-ids")
                && p.sampleInput() != null && p.sampleInput().isArray());
    }
}
```

- [ ] **Step 3: red 확인** — Run: `./gradlew :graph-rag-builder:test --tests '*BuilderCollectionE2eTest*'`
Expected: FAIL — `endpoints`에 `post-api-orders-batch`는 있으나 `paths`가 비어 있음(현재 shape null → skip). (배열 단언/INSERT도 실패.)

- [ ] **Step 4: Commit**
```bash
git add samples/order-service/src/main/java/io/graphrag/sample/orders/OrderBatchController.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/cli/BuilderCollectionE2eTest.java
git commit -m "test(e2e): collection @RequestBody acceptance (red) + order-service batch/by-ids endpoints"
```

> 이 테스트는 Phase 2~5가 끝나면 green이 된다(outer loop). 각 Phase는 inner-loop 단위 TDD.

---

## Phase 2 — BodyShape + 추출기(컬렉션 인지)

### Task 2: BodyShape collection 플래그

**Files:** Modify `graph-rag-builder/src/main/java/io/graphrag/builder/index/BodyShape.java`

- [ ] **Step 1: 구현 (record 확장 + 편의 ctor)**
```java
package io.graphrag.builder.index;

import java.util.List;

/** @RequestBody 타입의 필드 구조. sample input 합성에 사용 (builder 내부 전용). */
public record BodyShape(String javaType, List<BodyField> fields, boolean collection) {

    /** 객체 바디 편의 생성자(기존 호출부 호환): collection=false. */
    public BodyShape(String javaType, List<BodyField> fields) {
        this(javaType, fields, false);
    }

    public record BodyField(String name, String javaType) {
    }
}
```

- [ ] **Step 2: 컴파일** — Run: `./gradlew :graph-rag-builder:compileJava`
Expected: BUILD SUCCESSFUL (기존 `new BodyShape(qn, fields)` 호출부는 편의 ctor로 호환).

- [ ] **Step 3: Commit** — `git commit -am "feat(index): BodyShape collection flag (+compat ctor)"`

### Task 3: BodyShapeExtractor.extractFromType + bodyTypeKey + scalar 판정

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/index/BodyShapeExtractor.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/index/BodyShapeExtractorTest.java`

- [ ] **Step 1: 실패 테스트**

`BodyShapeExtractorTest.java`:
```java
package io.graphrag.builder.index;

import org.junit.jupiter.api.Test;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BodyShapeExtractorTest {

    /** 소스 문자열을 Spoon noClasspath 모델로. */
    private static CtModel model(String src) {
        Launcher l = new Launcher();
        l.getEnvironment().setNoClasspath(true);
        l.getEnvironment().setComplianceLevel(17);
        l.addInputResource(new spoon.support.compiler.VirtualFile(src, "In.java"));
        return l.buildModel();
    }

    /** 모델 첫 메서드의 첫 파라미터 타입 참조. */
    private static CtTypeReference<?> firstParamType(CtModel m) {
        for (CtType<?> t : m.getAllTypes()) {
            for (CtMethod<?> mt : t.getMethods()) {
                if (!mt.getParameters().isEmpty()) {
                    return mt.getParameters().get(0).getType();
                }
            }
        }
        throw new IllegalStateException("no param");
    }

    private static final String DTO = "package p; class Dto { String name; int amount; } ";

    @Test void listOfDto_isCollectionWithElementFields() {
        CtModel m = model(DTO + "class In { void h(java.util.List<p.Dto> b){} }");
        Optional<BodyShape> s = BodyShapeExtractor.extractFromType(m, firstParamType(m));
        assertThat(s).isPresent();
        assertThat(s.get().collection()).isTrue();
        assertThat(s.get().javaType()).isEqualTo("p.Dto");
        assertThat(s.get().fields()).extracting(BodyShape.BodyField::name).contains("name", "amount");
    }

    @Test void arrayOfDto_isCollection() {
        CtModel m = model(DTO + "class In { void h(p.Dto[] b){} }");
        BodyShape s = BodyShapeExtractor.extractFromType(m, firstParamType(m)).orElseThrow();
        assertThat(s.collection()).isTrue();
        assertThat(s.javaType()).isEqualTo("p.Dto");
    }

    @Test void listOfScalar_isCollectionEmptyFields() {
        CtModel m = model("class In { void h(java.util.List<java.lang.String> b){} }");
        BodyShape s = BodyShapeExtractor.extractFromType(m, firstParamType(m)).orElseThrow();
        assertThat(s.collection()).isTrue();
        assertThat(s.javaType()).isEqualTo("java.lang.String");
        assertThat(s.fields()).isEmpty();
    }

    @Test void listOfEnum_isScalarNotDto() {
        CtModel m = model("package p; enum E { A, B } class In { void h(java.util.List<p.E> b){} }");
        BodyShape s = BodyShapeExtractor.extractFromType(m, firstParamType(m)).orElseThrow();
        assertThat(s.collection()).isTrue();
        assertThat(s.javaType()).isEqualTo("p.E");
        assertThat(s.fields()).isEmpty();   // enum은 scalar — 상수를 필드로 추출하지 않음
    }

    @Test void rawList_isEmpty() {
        CtModel m = model("class In { void h(java.util.List b){} }");
        assertThat(BodyShapeExtractor.extractFromType(m, firstParamType(m))).isEmpty();
    }

    @Test void plainDto_isObjectNotCollection() {
        CtModel m = model(DTO + "class In { void h(p.Dto b){} }");
        BodyShape s = BodyShapeExtractor.extractFromType(m, firstParamType(m)).orElseThrow();
        assertThat(s.collection()).isFalse();
        assertThat(s.javaType()).isEqualTo("p.Dto");
    }

    @Test void bodyTypeKey_encodesElement() {
        CtModel m = model(DTO + "class In { void h(java.util.List<p.Dto> b){} }");
        assertThat(BodyShapeExtractor.bodyTypeKey(firstParamType(m))).isEqualTo("java.util.List<p.Dto>");
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew :graph-rag-builder:test --tests '*BodyShapeExtractorTest*'`
Expected: FAIL — `extractFromType`/`bodyTypeKey` 없음(컴파일 에러).

- [ ] **Step 3: 구현** — `BodyShapeExtractor`에 추가(기존 `extract(model, qn)`/`findNested`는 유지):
```java
    private static final java.util.Set<String> COLLECTION_TYPES = java.util.Set.of(
            "java.util.List", "java.util.Set", "java.util.Collection", "java.lang.Iterable");

    /** scalar 인식 FQN — SampleInputSynthesizer 합성 가능 타입과 일치시킨다. */
    private static final java.util.Set<String> SCALAR_TYPES = java.util.Set.of(
            "java.lang.String", "java.lang.Boolean", "boolean",
            "java.lang.Integer", "int", "java.lang.Long", "long", "java.lang.Short", "short",
            "java.lang.Double", "double", "java.lang.Float", "float", "java.math.BigDecimal",
            "java.time.LocalDate", "java.time.LocalDateTime", "java.time.LocalTime",
            "java.time.Instant", "java.time.OffsetDateTime", "java.time.ZonedDateTime");

    /** 컬렉션/배열을 원소 shape로 환원. 객체면 기존 동작. */
    public static Optional<BodyShape> extractFromType(CtModel model,
            spoon.reflect.reference.CtTypeReference<?> type) {
        spoon.reflect.reference.CtTypeReference<?> element = elementType(type);
        if (element == null) {
            return extract(model, type.getQualifiedName());   // 비컬렉션 → 객체(기존)
        }
        return elementShape(model, element).map(s ->
                new BodyShape(s.javaType(), s.fields(), true));   // collection=true
    }

    /** 컬렉션/배열이면 원소 타입 참조, 아니면 null. */
    private static spoon.reflect.reference.CtTypeReference<?> elementType(
            spoon.reflect.reference.CtTypeReference<?> type) {
        if (type instanceof spoon.reflect.reference.CtArrayTypeReference<?> arr) {
            return arr.getComponentType();
        }
        if (COLLECTION_TYPES.contains(type.getQualifiedName())
                && type.getActualTypeArguments().size() == 1) {
            return type.getActualTypeArguments().get(0);
        }
        return null;
    }

    /** 원소 shape: enum/scalar는 fields 빈 BodyShape(javaType=원소 FQN), DTO는 필드 추출. */
    private static Optional<BodyShape> elementShape(CtModel model,
            spoon.reflect.reference.CtTypeReference<?> element) {
        String qn = element.getQualifiedName();
        CtType<?> decl = element.getTypeDeclaration();   // 모델에 있으면 비-null
        if (decl != null && decl.isEnum()) {
            return Optional.of(new BodyShape(qn, List.of()));   // enum → scalar
        }
        if (decl != null) {
            return extract(model, qn);   // DTO → 필드 추출
        }
        if (SCALAR_TYPES.contains(qn)) {
            return Optional.of(new BodyShape(qn, List.of()));   // scalar
        }
        return Optional.empty();
    }

    /** body shape 맵 키 — 컬렉션/배열은 원소 인코딩(충돌 방지). */
    public static String bodyTypeKey(spoon.reflect.reference.CtTypeReference<?> type) {
        if (type instanceof spoon.reflect.reference.CtArrayTypeReference<?> arr) {
            return arr.getComponentType().getQualifiedName() + "[]";
        }
        spoon.reflect.reference.CtTypeReference<?> el = elementType(type);
        return el == null ? type.getQualifiedName()
                : type.getQualifiedName() + "<" + el.getQualifiedName() + ">";
    }
```
(import 추가: `spoon.reflect.declaration.CtType` 이미 있음. `extract`가 반환하는 BodyShape는 collection=false 편의 ctor — `elementShape`의 DTO 분기에서 `extract`가 준 객체-shape의 fields/javaType만 쓰고 `extractFromType`이 collection=true로 재포장.)

- [ ] **Step 4: 통과 확인** — Run: `./gradlew :graph-rag-builder:test --tests '*BodyShapeExtractorTest*'`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit** — `git commit -am "feat(index): BodyShapeExtractor.extractFromType + bodyTypeKey (collection/array/scalar/enum)"`

### Task 4: 인덱서 배선 (EndpointIndexer + Kafka/WS) — extractFromType + bodyTypeKey

**Files:** Modify `EndpointIndexer.java`, `KafkaListenerIndexer.java`, `WsEndpointIndexer.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/index/EndpointIndexerCollectionTest.java`

- [ ] **Step 1: 실패 테스트**
```java
package io.graphrag.builder.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EndpointIndexerCollectionTest {

    @Test void listBodyEndpoint_hasCollectionShape(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("C.java"), """
            package p;
            import org.springframework.web.bind.annotation.*;
            import java.util.List;
            record Dto(String name, int amount) {}
            @RestController class C {
              @PostMapping("/batch") void batch(@RequestBody List<Dto> rs){}
            }
            """);
        IndexResult r = new EndpointIndexer().index(dir, null);
        var ep = r.endpoints().stream().filter(e -> e.id().equals("post-batch")).findFirst().orElseThrow();
        String key = ep.params().stream().filter(p -> p.kind() == io.graphrag.model.ParamKind.BODY)
                .map(io.graphrag.model.EndpointParam::javaType).findFirst().orElseThrow();
        assertThat(key).isEqualTo("java.util.List<p.Dto>");
        BodyShape shape = r.bodyShapes().get(key);
        assertThat(shape).isNotNull();
        assertThat(shape.collection()).isTrue();
        assertThat(shape.fields()).extracting(BodyShape.BodyField::name).contains("name", "amount");
    }
}
```
(주: `EndpointIndexer().index(dir, authConfig)` 시그니처는 기존 사용처 확인 후 맞춘다 — `BuilderCli`/기존 `EndpointIndexerTest` 참조.)

- [ ] **Step 2: 실패 확인** → FAIL (현재 key="java.util.List", shape null).

- [ ] **Step 3: 구현** — `EndpointIndexer.extractParams`의 BODY 분기(현 158~160) 교체:
```java
            if (findAnnotation(parameter, REQUEST_BODY) != null) {
                String bodyType = BodyShapeExtractor.bodyTypeKey(parameter.getType());
                params.add(new EndpointParam(parameter.getSimpleName(), bodyType, ParamKind.BODY));
                BodyShapeExtractor.extractFromType(model, parameter.getType())
                        .ifPresent(s -> bodyShapes.put(bodyType, s));
            }
```
FORM 분기(현 175~)도 동일 패턴으로:
```java
                String formType = BodyShapeExtractor.bodyTypeKey(parameter.getType());
                java.util.Optional<BodyShape> shape =
                        BodyShapeExtractor.extractFromType(model, parameter.getType());
```
그리고 `EndpointIndexer`의 private `extractBodyShape`/`findNested` **제거**(공유 `BodyShapeExtractor` 사용). `import io.graphrag.builder.index.BodyShapeExtractor`는 동일 패키지라 불필요.

`KafkaListenerIndexer`/`WsEndpointIndexer`: payload 파라미터 처리에서 `getQualifiedName()`+`BodyShapeExtractor.extract(model, qn)` → `BodyShapeExtractor.bodyTypeKey(type)` 키 + `BodyShapeExtractor.extractFromType(model, type)`로 교체(각 인덱서의 payloadShapes 맵 키도 bodyTypeKey).

- [ ] **Step 4: 통과 + 기존 인덱서 테스트 green** — Run: `./gradlew :graph-rag-builder:test --tests '*EndpointIndexer*' --tests '*KafkaListenerIndexer*' --tests '*WsEndpoint*'`
Expected: PASS.

- [ ] **Step 5: Commit** — `git commit -am "feat(index): wire extractFromType+bodyTypeKey into Endpoint/Kafka/Ws indexers; drop dup extractBodyShape"`

---

## Phase 3 — 합성 (배열 생성) + 파급 가드

### Task 5: SynthesizedInput.body → JsonNode + SampleInputSynthesizer 배열 합성

**Files:** Modify `SynthesizedInput.java`, `SampleInputSynthesizer.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/run/SampleInputSynthesizerCollectionTest.java`

- [ ] **Step 1: SynthesizedInput.body 타입 변경**
```java
package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public record SynthesizedInput(JsonNode body, List<SeedRow> seeds) {
    public record SeedRow(String table, List<String> columns, List<Object> values) {
    }
}
```

- [ ] **Step 2: 실패 테스트 (배열 합성)**
```java
package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.node.ArrayNode;
import io.graphrag.builder.index.BodyShape;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SampleInputSynthesizerCollectionTest {

    @Test void dtoCollection_synthesizesSingleElementObjectArray() {
        BodyShape shape = new BodyShape("p.Dto",
                List.of(new BodyShape.BodyField("name", "java.lang.String"),
                        new BodyShape.BodyField("amount", "int")), true);
        var in = new SampleInputSynthesizer().synthesize(shape, List.of());
        assertThat(in.body()).isInstanceOf(ArrayNode.class);
        ArrayNode arr = (ArrayNode) in.body();
        assertThat(arr).hasSize(1);
        assertThat(arr.get(0).get("name").asText()).isEqualTo("sample-name");
        assertThat(arr.get(0).get("amount").asInt()).isEqualTo(1);
    }

    @Test void scalarCollection_synthesizesSingleScalarArray() {
        BodyShape shape = new BodyShape("java.lang.String", List.of(), true);
        var in = new SampleInputSynthesizer().synthesize(shape, List.of());
        ArrayNode arr = (ArrayNode) in.body();
        assertThat(arr).hasSize(1);
        assertThat(arr.get(0).isTextual()).isTrue();
    }

    @Test void objectBody_stillObjectNode() {
        BodyShape shape = new BodyShape("p.Dto",
                List.of(new BodyShape.BodyField("name", "java.lang.String")));
        var in = new SampleInputSynthesizer().synthesize(shape, List.of());
        assertThat(in.body().isObject()).isTrue();
    }
}
```

- [ ] **Step 3: 실패 확인** → FAIL (배열 미생성/컴파일 에러).

- [ ] **Step 4: 구현** — `SampleInputSynthesizer`:
  1. `synthesize(shape, tables, fieldConstraints)` 시작에서 객체 합성을 `synthesizeObject(...)` private로 추출(현 45~60 본문). 반환 `ObjectNode body` + seeds.
  2. `putScalar`의 타입별 값 결정을 `scalarValue(String javaType, List<FieldConstraint> cons)` → `JsonNode`(또는 적절 put)로 추출. `putScalar`는 `body.set(field.name(), scalarValue(field.javaType(), cons))`.
  3. 공개 `synthesize`:
```java
    public SynthesizedInput synthesize(BodyShape shape, List<TableSchema> tables,
                                       Map<String, List<FieldConstraint>> fieldConstraints) {
        if (shape.collection()) {
            com.fasterxml.jackson.databind.node.ArrayNode arr = Json.mapper().createArrayNode();
            List<SynthesizedInput.SeedRow> seeds = new java.util.ArrayList<>();
            if (shape.fields().isEmpty()) {                 // scalar/enum 원소
                arr.add(scalarValue(shape.javaType(), List.of()));
            } else {                                         // DTO 원소
                ObjResult el = synthesizeObject(shape, tables, fieldConstraints);
                arr.add(el.body());
                seeds.addAll(el.seeds());
            }
            return new SynthesizedInput(arr, seeds);
        }
        ObjResult o = synthesizeObject(shape, tables, fieldConstraints);
        return new SynthesizedInput(o.body(), o.seeds());
    }

    private record ObjResult(ObjectNode body, List<SynthesizedInput.SeedRow> seeds) {}
```
  `scalarValue`는 enumConstants/INT/FLOAT/시간/email/String 로직을 `putScalar`에서 옮겨 `JsonNode` 반환(숫자는 `LongNode`/`DoubleNode`, 불리언 `BooleanNode`, 그 외 `TextNode`). `putScalar`는 이를 `body.set(name, scalarValue(...))`로 호출.

- [ ] **Step 5: 통과 확인** → Run: `./gradlew :graph-rag-builder:test --tests '*SampleInputSynthesizer*'` → PASS (신규 + 기존).

- [ ] **Step 6: Commit** — `git commit -am "feat(run): SampleInputSynthesizer array synthesis (DTO/scalar/enum) + JsonNode body"`

### Task 6: ObjectNode 전제 파급 가드 (컴파일 + 런타임)

**Files:** Modify `EndpointTarget.java`, `EndpointExplorationRunner.java`, `HeuristicExplorer.java`, `CoverageGuidedFuzzer.java`, `InputMutator.java`, `KafkaCaptureRunner.java`, `WsCaptureRunner.java`

- [ ] **Step 1: 컴파일러로 파급 지점 전수 확인**

Run: `./gradlew :graph-rag-builder:compileJava 2>&1 | grep -E "error:|ObjectNode|JsonNode" | head -40`
이 시점에 `SynthesizedInput.body()`가 `JsonNode`라 `ObjectNode x = ...body()` 대입부가 전부 컴파일 에러로 드러난다. 아래를 처리한다.

- [ ] **Step 2: EndpointTarget.baseInput → JsonNode**

`EndpointTarget.java`: record 컴포넌트 `ObjectNode baseInput` → `JsonNode baseInput`(+편의 ctor 시그니처). `import com.fasterxml.jackson.databind.JsonNode`.

- [ ] **Step 3: 변이/탐색 엔진 가드 (컬렉션=happy-only)**

- `EndpointExplorationRunner` line 156 `ObjectNode baseInput = happy.body();` → `JsonNode baseInput = happy.body();`.
- `happyInput`(line 787) merge 가드: `bodyPart.body()`가 ObjectNode일 때만 merge, 아니면 ArrayNode 그대로:
```java
            JsonNode bodyNode = bodyPart.body();
            if (!(bodyNode instanceof ObjectNode bodyObj)) {
                return new SynthesizedInput(bodyNode, bodyPart.seeds());   // 컬렉션+path: 합성 안 함(비목표)
            }
            ObjectNode merged = bodyObj.deepCopy();
            merged.setAll((ObjectNode) pathPart.body());
            ...
```
- 음수-검증 패스(line 271): `if (shape != null && !shape.collection()) finalPaths.addAll(exploreNegativeValidationVariants(...))` — 컬렉션이면 skip.
- `HeuristicExplorer`(line ~20) / `CoverageGuidedFuzzer`(line ~42) / `InputMutator`: `target.baseInput()`/body가 `ObjectNode`가 아니면 변이 후보 0개 반환(=happy만):
```java
        if (!(target.baseInput() instanceof ObjectNode)) {
            return List.of();   // 컬렉션 바디: 변이 없음(happy 1회)
        }
```
(각 엔진의 진입부에 가드. `InputMutator`의 `copy`/`firstOrder`/`constraintDirected`도 ObjectNode 전제이므로 호출 전 가드되도록 엔진에서 막는다.)

- [ ] **Step 4: bodyValues ArrayNode unwrap (bind 귀속)**

`EndpointExplorationRunner.bodyValues`(line 1018):
```java
    private static Set<String> bodyValues(JsonNode body) {
        Set<String> values = new HashSet<>();
        if (body instanceof com.fasterxml.jackson.databind.node.ArrayNode arr) {
            arr.forEach(el -> collectFieldValues(el, values));
        } else {
            collectFieldValues(body, values);
        }
        return values;
    }
    private static void collectFieldValues(JsonNode node, Set<String> values) {
        node.fields().forEachRemaining(e -> {
            if (!e.getValue().isNull()) { values.add(e.getValue().asText()); }
        });
    }
```

- [ ] **Step 5: Kafka/WS 러너 JsonNode 수용**

- `KafkaCaptureRunner`: `ObjectNode payload = happy.body();` → `JsonNode payload = happy.body();`. `Json.mapper().writeValueAsString(payload)`는 JsonNode도 직렬화 OK. key 추출 `payload.has("userId")...`는 `payload.isObject() && payload.has(...)` 가드. variant 합성(`missingFieldPayload` 등)은 객체 전용 경로이므로 컬렉션 페이로드면 happy만(변종 skip 가드: payload가 ObjectNode일 때만 변종).
- `WsCaptureRunner`: `List<ObjectNode>` → `List<JsonNode>`; `happy.body().deepCopy()` 등 ObjectNode 전제는 `instanceof ObjectNode` 가드(컬렉션 페이로드 happy-only).

- [ ] **Step 6: 컴파일 + 기존 테스트 green** — Run: `./gradlew :graph-rag-builder:test`
Expected: PASS (객체 바디/Kafka/WS 회귀 불변 — 컬렉션 경로는 가드로 happy-only).

- [ ] **Step 7: Commit** — `git commit -am "feat(run/explore): JsonNode body propagation + collection happy-only guards + bodyValues array unwrap"`

---

## Phase 4 — 생성기 (배열 body 방출)

### Task 7: FixtureComposer + Generator 배열 body

**Files:** Modify `test-generator/.../compose/FixtureComposer.java`, `generator/Generator.java`
- Test: `test-generator/src/test/java/io/graphrag/generator/GeneratorCollectionBodyTest.java`

- [ ] **Step 1: 실패 테스트 (배열 sampleInput → 배열 body 방출)**
```java
package io.graphrag.generator;

import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.model.Json;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratorCollectionBodyTest {

    @Test void arraySampleInput_emittedAsArrayBody() throws Exception {
        JsonNode arr = Json.mapper().readTree("[{\"name\":\"sample-name\",\"amount\":1}]");
        // FixtureComposer/Generator의 body 방출 헬퍼를 직접 호출하는 최소 경로로 검증
        // (구현 시 노출된 메서드/소형 통합으로): 생성된 body 문자열이 '[' 로 시작하고 빈 {} 아님
        String body = io.graphrag.generator.compose.FixtureComposer.bodyFormatFor(arr);
        assertThat(body).startsWith("[").contains("\"name\"");
    }
}
```
(주: 정확한 검증 진입점은 구현에 맞춘다 — `FixtureComposer`에 `static String bodyFormatFor(JsonNode)`를 추출하거나, Generator 단위 경로로. 핵심 단언: 배열 입력 → `[` 시작 body, `{}` 아님.)

- [ ] **Step 2: 실패 확인** → FAIL.

- [ ] **Step 3: 구현**

- `FixtureComposer`(line 141~163 bodyFormat 생성): `path.sampleInput().isArray()`이면 배열을 그대로 직렬화한 문자열을 bodyFormat으로(placeholder 치환 없음 — 컬렉션 happy는 리터럴):
```java
        if (path.sampleInput() != null && path.sampleInput().isArray()) {
            // 컬렉션 바디: 배열 리터럴을 그대로 body로(원소는 합성 happy 값).
            return ...ComposedFixture(..., path.sampleInput().toString(), List.of(), ...);
        }
```
(`bodyFormat`은 `String.format` 대상이므로 `%`는 `%%`로 이스케이프하거나 bodyArgs 빈 채 그대로 방출. 안전하게 `toString()` 결과의 `%`→`%%` 치환.)
- `Generator.jsonBodyFromInput`(line 351~): `input instanceof ArrayNode`면 `return input.toString();`(현재 `"{}"` 반환 버그 수정). `ObjectNode` 가정 단언(`knownByField` line 277)·`requestPath`는 입력이 ObjectNode일 때만(이미 line 277 `instanceof ObjectNode` 가드 존재 — 확인).

- [ ] **Step 4: 통과 확인** → Run: `./gradlew :test-generator:test --tests '*GeneratorCollectionBody*'` → PASS.

- [ ] **Step 5: Commit** — `git commit -am "feat(generator): emit array request body for collection sampleInput (FixtureComposer+Generator)"`

---

## Phase 5 — E2E green + 회귀 + 문서

### Task 8: 수용 green + 전체 회귀 + docs

- [ ] **Step 1: 수용 테스트 green** — Run: `./gradlew :graph-rag-builder:test --tests '*BuilderCollectionE2eTest*'`
Expected: PASS (Task 1의 outer loop가 이제 green — batch skip 안 됨, sampleInput 배열, INSERT+API_PARAM bind, by-ids 배열).

- [ ] **Step 2: 전체 회귀** — Run: `./gradlew test` 그리고 `bash e2e/run-e2e.sh`
Expected: 전 모듈 green; run-e2e.sh E2E PASS(기존 + batch/by-ids 생성 테스트 포함, 빈 {} 아님).

- [ ] **Step 3: docs 갱신** — `docs/03-graph-rag-builder.md`(컬렉션 바디 지원/한계), 필요 시 `docs/04-test-generator.md`. 컬렉션 한계(happy-only, deferred D1~D7)를 명시.

- [ ] **Step 4: Commit** — `git commit -am "docs: collection @RequestBody support (happy-only) + limits"`

---

## Definition of Done

- [ ] `BuilderCollectionE2eTest` green (batch/by-ids: skip 안 됨·배열 sampleInput·INSERT+API_PARAM bind).
- [ ] 단위: BodyShapeExtractor(list/set/collection/array/raw/scalar/enum/dto·bodyTypeKey), SampleInputSynthesizer(배열 DTO/scalar), Generator/FixtureComposer(배열 body), bodyValues unwrap.
- [ ] 전체 회귀(`./gradlew test` + `run-e2e.sh`) green — 객체 바디/Kafka/WS/OTEL 회귀 불변.
- [ ] docs 갱신.
- [ ] PR 전 spec-compliance + 코드 품질 리뷰 트리아지.

# 컬렉션 @RequestBody (List<DTO> 등) body shape 지원 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`).

**Goal:** HTTP `@RequestBody` + Kafka `@KafkaListener` + WS 페이로드의 컬렉션(`List`/`Set`/`Collection`/`Iterable<E>`·배열 `E[]`, 원소 E=DTO/scalar/enum)을 happy-path(유효 원소 1개 배열)로 탐색·캡처·생성한다.

**Architecture:** `BodyShape`에 `collection` 플래그 추가(원소 FQN=`javaType`, scalar/DTO는 `fields` 유무). 공유 `BodyShapeExtractor.extractFromType(CtTypeReference)`가 제네릭/배열 원소를 환원. `SampleInputSynthesizer`가 `ArrayNode`를 합성. `SynthesizedInput.body`를 `JsonNode`로 넓히되 **컬렉션 바디는 happy 1회만 실행**(변이/음수/by-id는 `instanceof ObjectNode`/`shape.collection()` 가드로 skip — happy 호출 자체는 유지). 생성기는 배열 body를 방출.

**Tech Stack:** Java 17, Gradle, JUnit 5, AssertJ, Spoon(noClasspath), Jackson(JsonNode/ObjectNode/ArrayNode), Testcontainers.

**Spec:** [docs/superpowers/specs/2026-06-18-list-dto-body-shape-design.md](../specs/2026-06-18-list-dto-body-shape-design.md)

**검증된 사실(구현 시 의존):** `EndpointInvoker.invoke(JsonNode)` 이미 JsonNode. `doSend(..., JsonNode input, ...)` 이미 JsonNode. happy 호출은 `HeuristicExplorer.explore` 안에서 `target.invoker().invoke(base)`로 일어난다(=컬렉션도 happy가 돌게 하려면 explorer가 early-return하면 안 됨).

---

## File Structure

- **Modify** `.../index/BodyShape.java` — `collection` 플래그 + 편의 ctor.
- **Modify** `.../index/BodyShapeExtractor.java` — `extractFromType`, `bodyTypeKey`, scalar 집합, getTypeDeclaration fallback.
- **Modify** `.../index/EndpointIndexer.java` — 공유 추출기 + bodyTypeKey, private dup 제거.
- **Modify** `.../index/KafkaListenerIndexer.java`, `WsEndpointIndexer.java` — payload 타입을 CtTypeReference로 해석(컬렉션 제네릭 보존).
- **Modify** `.../run/SynthesizedInput.java` — `body` `ObjectNode`→`JsonNode`.
- **Modify** `.../run/SampleInputSynthesizer.java` — `scalarValue` 추출 + 배열 합성.
- **Modify** `.../explore/EndpointTarget.java` — `baseInput` `ObjectNode`→`JsonNode`.
- **Modify** `.../explore/HeuristicExplorer.java` — happy는 JsonNode로 항상 실행, 변이만 ObjectNode 가드.
- **Modify** `.../explore/CoverageGuidedFuzzer.java`, `InputMutator.java` — ObjectNode 가드(컬렉션=변이 없음).
- **Modify** `.../run/EndpointExplorationRunner.java` — baseInput JsonNode, happyInput merge 가드, 음수-검증 collection skip + cast, 공유 `collectBodyValues`.
- **Modify** `.../run/ReadInputSynthesizer.java` — `(ObjectNode)` cast(read 경로는 객체).
- **Modify** `.../run/KafkaCaptureRunner.java`, `WsCaptureRunner.java` — JsonNode payload + 배열 happy-only + 공유 `collectBodyValues`.
- **Modify** `test-generator/.../compose/FixtureComposer.java`, `generator/Generator.java` — 배열 body 방출 + 원소 unwrap.
- **Create(SUT)** order-service: `OrderBatchController`(HTTP), `BatchEventConsumer`(Kafka List), WS 컬렉션 핸들러.
- **Tests** 각 단위 + `BuilderCollectionE2eTest`(통합).

---

## Phase 1 — E2E 수용 (outer loop, red 먼저)

### Task 1: SUT 컬렉션 엔드포인트(HTTP/Kafka/WS) + 수용 테스트(red)

**Files:**
- Create: `samples/order-service/.../OrderBatchController.java`, `BatchEventConsumer.java`; Modify `OrderCountWsController.java`(WS 컬렉션 핸들러 추가)
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/cli/BuilderCollectionE2eTest.java`

- [ ] **Step 1: HTTP 컬렉션 엔드포인트** — `OrderBatchController.java`:
```java
package io.graphrag.sample.orders;

import io.graphrag.sample.orders.OrderController.CreateOrderRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderBatchController {
    public record BatchResponse(int created) {}
    public record CountResponse(long count) {}
    private final UserRepository users;
    private final OrderRepository orders;
    public OrderBatchController(UserRepository users, OrderRepository orders) {
        this.users = users; this.orders = orders;
    }
    @PostMapping("/batch")
    @ResponseStatus(HttpStatus.CREATED)
    public BatchResponse batch(@RequestBody List<CreateOrderRequest> requests) {
        int created = 0;
        for (CreateOrderRequest r : requests) {
            if (r.userId() == null || r.amount() == null || r.amount() <= 0 || r.type() == null) continue;
            User user = users.findById(r.userId()).orElse(null);
            if (user == null) continue;
            orders.save(new Order(user, r.amount(), r.type(), "PENDING"));
            created++;
        }
        return new BatchResponse(created);
    }
    @PostMapping("/by-ids")
    public CountResponse byIds(@RequestBody List<String> userIds) {
        long count = 0;
        for (String id : userIds) count += orders.findByUser_Id(id).size();
        return new CountResponse(count);
    }
}
```
(`CreateOrderRequest`=record(String userId, Integer amount, String type); `Order(User, Integer, String, String)`; `OrderRepository.findByUser_Id(String)→List<Order>` — 모두 기존 확인됨.)

- [ ] **Step 2: Kafka 컬렉션 리스너** — `BatchEventConsumer.java`(order-service 패턴: `@ConditionalOnProperty("spring.kafka.bootstrap-servers")`):
```java
package io.graphrag.sample.orders;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import java.util.List;

/** 컬렉션 Kafka payload 회귀 가드: List<OrderEventPayload>. */
@Component
@ConditionalOnProperty("spring.kafka.bootstrap-servers")
public class BatchEventConsumer {
    public record Item(String eventId, String type, String userId) {}
    private final OrderEventRepository repository;
    public BatchEventConsumer(OrderEventRepository repository) { this.repository = repository; }

    @KafkaListener(topics = "order.events.batch", groupId = "order-service-batch")
    public void onBatch(List<Item> items) {
        for (Item it : items) {
            if (it.eventId() == null || it.userId() == null) continue;
            if (!repository.existsById(it.eventId())) {
                repository.save(new OrderEvent(it.eventId(),
                        it.type() == null ? "UNKNOWN" : it.type(), it.userId()));
            }
        }
    }
}
```
(주: Spring Kafka의 List 페이로드는 batch listener 또는 단일 List 메시지 형태. 인덱서는 리스너 param 타입 `List<Item>`을 컬렉션으로 인식하면 충분. e2e/docker-compose.yml에 `order.events.batch` 토픽은 auto-create로 처리.)

- [ ] **Step 3: WS 컬렉션 핸들러** — `OrderCountWsController`에 List payload 매핑 메서드 1개 추가(기존 WS 패턴 따름; 핸들러 시그니처 `List<...>` payload). 구체 코드는 기존 `OrderCountWsController`의 `@MessageMapping` 패턴을 그대로 따라 `List<String> ids` 받는 메서드 추가.

- [ ] **Step 4: 수용 테스트(red)** — `BuilderCollectionE2eTest.java`:
```java
package io.graphrag.builder.cli;

import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.run.AuthConfig;
import io.graphrag.model.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@EnabledIfSystemProperty(named = "sut.jar", matches = ".+")
class BuilderCollectionE2eTest {
    @TempDir Path out;

    @Test
    void httpCollectionBody_exploredWithArrayAndCapturedSql() throws Exception {
        GraphAsset asset = build();
        // 1) batch skip 안 됨 + 탐색됨
        assertThat(asset.endpoints()).extracting(Endpoint::id).contains("post-api-orders-batch");
        List<ExploredPath> batch = asset.paths().stream()
                .filter(p -> p.id().startsWith("post-api-orders-batch")).toList();
        assertThat(batch).as("batch explored (not skipped)").isNotEmpty();
        // 2) 합성 body가 JSON 배열
        assertThat(batch).anyMatch(p -> p.sampleInput() != null && p.sampleInput().isArray());
        // 3) DTO happy → INSERT + API_PARAM bind
        ExploredPath happy = batch.stream().filter(p -> p.expectedStatus()/100==2).findFirst().orElseThrow();
        List<CapturedSql> sql = asset.sql().stream().filter(s -> s.pathId().equals(happy.id())).toList();
        assertThat(sql).anyMatch(s -> s.sqlKind().equals("INSERT") && s.tableName().equals("orders"));
        assertThat(sql.stream().flatMap(s -> s.bindings().stream()))
                .anyMatch(b -> b.origin() == BindingOrigin.API_PARAM);
        // 5) scalar 컬렉션도 배열
        assertThat(asset.paths()).anyMatch(p -> p.id().startsWith("post-api-orders-by-ids")
                && p.sampleInput() != null && p.sampleInput().isArray());
    }

    @Test
    void kafkaCollectionPayload_capturedAsArray() throws Exception {
        GraphAsset asset = build();
        // Kafka 컬렉션 리스너가 인덱싱되고 교환 payload가 배열
        assertThat(asset.kafkaConsumers()).extracting(c -> c.topic()).contains("order.events.batch");
        var ex = asset.kafkaExchanges().stream()
                .filter(e -> e.kafkaConsumerId().contains("batch") && !e.variant()).findFirst().orElseThrow();
        assertThat(ex.payload().isArray()).isTrue();
    }

    private GraphAsset build() throws Exception {
        Path sutSrc = Path.of(System.getProperty("sut.src"));
        AuthConfig auth = new AuthConfig("/api/auth/login","admin","password",
                "token","Authorization","Bearer", List.of());
        return BuilderCli.build(new BuildConfig(
                sutSrc, sutSrc.resolveSibling("resources"), Path.of(System.getProperty("sut.jar")), out,
                "order-service","test",
                new DbConfig(DbConfig.Type.POSTGRES,"postgres:15","app","app","app"),
                60, null, Path.of(System.getProperty("external.stubs")),
                Map.of("EXTERNAL_INVENTORY_URL","{{wiremock}}"),
                null, null, auth, false, true, null,
                null, io.graphrag.model.RequestHeaders.empty(), List.of(), "otel"));
    }
}
```
(주: `KafkaExchange.payload()` 반환 타입이 `ObjectNode`면 `JsonNode`로 넓혀야 배열 수용 — Task 6에서 shared-model `KafkaExchange.payload` 타입 확인·조정. 빌드 시 컴파일러가 잡음.)

- [ ] **Step 5: red 확인** — Run: `./gradlew :graph-rag-builder:test --tests '*BuilderCollectionE2eTest*'`
Expected: FAIL — batch paths 비어 있음(skip), kafka batch payload 배열 아님.

- [ ] **Step 6: Commit** — `git commit -m "test(e2e): collection HTTP/Kafka/WS acceptance (red) + order-service collection endpoints"`

---

## Phase 2 — BodyShape + 추출기(컬렉션 인지)

### Task 2: BodyShape collection 플래그
(동일 — 아래 구현 후 `compileJava` green, 기존 `new BodyShape(qn,fields)` 호환)
```java
public record BodyShape(String javaType, List<BodyField> fields, boolean collection) {
    public BodyShape(String javaType, List<BodyField> fields) { this(javaType, fields, false); }
    public record BodyField(String name, String javaType) {}
}
```
- [ ] 구현 → `./gradlew :graph-rag-builder:compileJava` → Commit `feat(index): BodyShape collection flag`.

### Task 3: BodyShapeExtractor.extractFromType + bodyTypeKey (+getTypeDeclaration fallback)

**Files:** Modify `BodyShapeExtractor.java`; Test `BodyShapeExtractorTest.java`

- [ ] **Step 1: 실패 테스트** — list/array/set/collection/iterable/scalar/enum/raw/plainDTO + bodyTypeKey. (각 케이스 `extractFromType(model, firstParamType(m))` 단언; helper는 이전 초안의 `model(src)`/`firstParamType` 사용. **Set/Collection/Iterable**도 각각 `collection()==true` + `bodyTypeKey`=`"java.util.Set<p.Dto>"` 등 추가. **enum**: `List<p.E>` → `collection()==true && fields().isEmpty() && javaType=="p.E"`.)

- [ ] **Step 2: 실패 확인** → FAIL.

- [ ] **Step 3: 구현** — `BodyShapeExtractor`에 추가:
```java
    private static final java.util.Set<String> COLLECTION_TYPES = java.util.Set.of(
            "java.util.List","java.util.Set","java.util.Collection","java.lang.Iterable");
    private static final java.util.Set<String> SCALAR_TYPES = java.util.Set.of(
            "java.lang.String","java.lang.Boolean","boolean",
            "java.lang.Integer","int","java.lang.Long","long","java.lang.Short","short",
            "java.lang.Double","double","java.lang.Float","float","java.math.BigDecimal",
            "java.time.LocalDate","java.time.LocalDateTime","java.time.LocalTime",
            "java.time.Instant","java.time.OffsetDateTime","java.time.ZonedDateTime");

    public static Optional<BodyShape> extractFromType(CtModel model,
            spoon.reflect.reference.CtTypeReference<?> type) {
        var element = elementType(type);
        if (element == null) return extract(model, type.getQualifiedName());   // 비컬렉션
        return elementShape(model, element).map(s -> new BodyShape(s.javaType(), s.fields(), true));
    }

    private static spoon.reflect.reference.CtTypeReference<?> elementType(
            spoon.reflect.reference.CtTypeReference<?> type) {
        if (type instanceof spoon.reflect.reference.CtArrayTypeReference<?> arr) return arr.getComponentType();
        if (COLLECTION_TYPES.contains(type.getQualifiedName())
                && type.getActualTypeArguments().size() == 1) return type.getActualTypeArguments().get(0);
        return null;
    }

    private static Optional<BodyShape> elementShape(CtModel model,
            spoon.reflect.reference.CtTypeReference<?> element) {
        String qn = element.getQualifiedName();
        CtType<?> decl = element.getTypeDeclaration();
        if (decl == null) decl = findInModel(model, qn);   // noClasspath fallback (다른 파일 원소)
        if (decl != null && decl.isEnum()) return Optional.of(new BodyShape(qn, List.of()));
        if (decl != null) return extract(model, qn);
        if (SCALAR_TYPES.contains(qn)) return Optional.of(new BodyShape(qn, List.of()));
        return Optional.empty();
    }

    /** getTypeDeclaration()이 null일 때 모델 전수 탐색 폴백(기존 extract와 동일 기반). */
    private static CtType<?> findInModel(CtModel model, String qn) {
        for (CtType<?> t : model.getAllTypes()) {
            CtType<?> found = findNested(t, qn);
            if (found != null) return found;
        }
        return null;
    }

    public static String bodyTypeKey(spoon.reflect.reference.CtTypeReference<?> type) {
        if (type instanceof spoon.reflect.reference.CtArrayTypeReference<?> arr)
            return arr.getComponentType().getQualifiedName() + "[]";
        var el = elementType(type);
        return el == null ? type.getQualifiedName()
                : type.getQualifiedName() + "<" + el.getQualifiedName() + ">";
    }
```

- [ ] **Step 4: 통과** → PASS. **Step 5: Commit** `feat(index): extractFromType + bodyTypeKey (collection/array/scalar/enum + noClasspath fallback)`.

### Task 4: 인덱서 배선 (HTTP + Kafka/WS, CtTypeReference 보존)

**Files:** `EndpointIndexer.java`, `KafkaListenerIndexer.java`, `WsEndpointIndexer.java`; Test `EndpointIndexerCollectionTest.java`(+Kafka/WS 컬렉션 단위).

- [ ] **Step 1: 실패 테스트** — (a) HTTP: `@PostMapping @RequestBody List<Dto>` → BODY param javaType=`"java.util.List<p.Dto>"`, `bodyShapes.get(key).collection()==true`. (b) Kafka: `@KafkaListener void on(List<Dto> items)` → payloadShapes에 컬렉션 shape + payloadType 인코딩 키. (c) WS: 동일.

- [ ] **Step 2: 실패 확인** → FAIL.

- [ ] **Step 3: 구현**
- `EndpointIndexer.extractParams` BODY 분기(158~160):
```java
            if (findAnnotation(parameter, REQUEST_BODY) != null) {
                String bodyType = BodyShapeExtractor.bodyTypeKey(parameter.getType());
                params.add(new EndpointParam(parameter.getSimpleName(), bodyType, ParamKind.BODY));
                BodyShapeExtractor.extractFromType(model, parameter.getType())
                        .ifPresent(s -> bodyShapes.put(bodyType, s));
            }
```
  FORM 분기도 `bodyTypeKey`/`extractFromType`로 교체. private `extractBodyShape`/`findNested` 제거.
- `KafkaListenerIndexer`(53~70): payload **CtTypeReference 보존**:
```java
        var paramType = method.getParameters().isEmpty() ? null : method.getParameters().get(0).getType();
        String payloadType;
        if (paramType != null && BodyShapeExtractor.bodyTypeKey(paramType).contains("<")
                || (paramType instanceof spoon.reflect.reference.CtArrayTypeReference)) {
            // 컬렉션/배열 리스너 param: 제네릭 원소로 shape
            payloadType = BodyShapeExtractor.bodyTypeKey(paramType);
            BodyShapeExtractor.extractFromType(model, paramType)
                    .ifPresent(s -> shapes.put(payloadType, s));
        } else {
            payloadType = paramType == null ? null : paramType.getQualifiedName();
            if ("java.lang.String".equals(payloadType)) {   // readValue 타깃(객체) 기존 경로
                String inner = readValueTargetType(method);
                if (inner != null) payloadType = inner;
            }
            if (payloadType != null) {
                String resolved = payloadType;
                BodyShapeExtractor.extract(model, resolved).ifPresent(s -> shapes.put(resolved, s));
            }
        }
```
  (KafkaConsumer record의 payloadType 인자도 위 `payloadType` 사용.)
- `WsEndpointIndexer`(54~58): `var pt = ...getType(); String key = BodyShapeExtractor.bodyTypeKey(pt); BodyShapeExtractor.extractFromType(model, pt).ifPresent(s -> shapes.put(key, s));` payloadType=key.

- [ ] **Step 4: 통과 + 기존 인덱서 테스트 green** → Run: `./gradlew :graph-rag-builder:test --tests '*Indexer*'`.
- [ ] **Step 5: Commit** `feat(index): collection-aware Endpoint/Kafka/Ws indexers (CtTypeReference, bodyTypeKey)`.

---

## Phase 3 — 합성 배열 + JsonNode 파급(컴파일+런타임)

### Task 5: SynthesizedInput.body→JsonNode + SampleInputSynthesizer 배열 합성

**Files:** `SynthesizedInput.java`, `SampleInputSynthesizer.java`; Test `SampleInputSynthesizerCollectionTest.java`

- [ ] **Step 1: SynthesizedInput.body → JsonNode** (import JsonNode).
- [ ] **Step 2: 실패 테스트** — dtoCollection→ArrayNode size1 객체, scalarCollection→ArrayNode size1 textual, **enumCollection**(enumConstants 주입)→첫 상수, objectBody→ObjectNode.
- [ ] **Step 3: 실패 확인** → FAIL.
- [ ] **Step 4: 구현** — `putScalar` 값 결정을 `JsonNode scalarValue(String javaType, List<FieldConstraint> cons)`로 추출(INT/FLOAT→Long/DoubleNode, boolean→BooleanNode, 시간/email/String→TextNode, enum→enumConstants 첫 상수 TextNode). 객체 합성을 `ObjResult synthesizeObject(...)`로 추출. 공개 `synthesize`:
```java
    public SynthesizedInput synthesize(BodyShape shape, List<TableSchema> tables,
                                       Map<String, List<FieldConstraint>> fieldConstraints) {
        if (shape.collection()) {
            var arr = Json.mapper().createArrayNode();
            List<SynthesizedInput.SeedRow> seeds = new java.util.ArrayList<>();
            if (shape.fields().isEmpty()) {
                arr.add(scalarValue(shape.javaType(), List.of()));
            } else {
                ObjResult el = synthesizeObject(shape, tables, fieldConstraints);
                arr.add(el.body()); seeds.addAll(el.seeds());
            }
            return new SynthesizedInput(arr, seeds);
        }
        ObjResult o = synthesizeObject(shape, tables, fieldConstraints);
        return new SynthesizedInput(o.body(), o.seeds());
    }
    private record ObjResult(ObjectNode body, List<SynthesizedInput.SeedRow> seeds) {}
```
- [ ] **Step 5: 통과** → PASS. **Step 6: Commit** `feat(run): array synthesis (DTO/scalar/enum) + JsonNode body`.

### Task 6: JsonNode 파급 전수 처리 + 컬렉션 happy-only 가드

**Files:** `EndpointTarget.java`, `HeuristicExplorer.java`, `CoverageGuidedFuzzer.java`, `InputMutator.java`, `EndpointExplorationRunner.java`, `ReadInputSynthesizer.java`, `KafkaCaptureRunner.java`, `WsCaptureRunner.java`, (필요 시 `shared-model/.../KafkaExchange.java`/`WsExchange.java` payload 타입).

- [ ] **Step 1: 컴파일러 전수 확인** — Run: `./gradlew :graph-rag-builder:compileJava 2>&1 | grep -E "error:" | head -50`
아래를 모두 처리(컴파일 에러로 드러남):

- [ ] **Step 2: EndpointTarget.baseInput → JsonNode** (record 컴포넌트 + 편의 ctor; import JsonNode). → runner line 172/209의 `new EndpointTarget(..., happy.body()/happy2.body(), ...)` 자동 호환.

- [ ] **Step 3: HeuristicExplorer — happy는 항상 실행, 변이만 가드** (line 20~):
```java
        JsonNode base = target.baseInput().deepCopy();
        tryInput(base, target, budget, known, inputs);          // happy 항상 실행(배열 가능)
        if (base instanceof ObjectNode objBase) {               // 변이는 객체 바디만
            for (var mutation : mutations) {
                tryInput(mutation.apply().apply(InputMutator.copy(objBase)), target, budget, known, inputs);
            }
        }
```
  `tryInput(JsonNode body, ...)`로 시그니처 변경(`invoker().invoke(JsonNode)` 이미 OK).

- [ ] **Step 4: CoverageGuidedFuzzer — 컬렉션이면 변이 없음** (explore 진입):
```java
        if (!(target.baseInput() instanceof ObjectNode)) return List.of();   // 컬렉션: happy는 Heuristic이 이미 수행
```
  (happy는 HeuristicExplorer에서 이미 실행되므로 fuzzer는 컬렉션에서 빈 결과로 충분.) `InputMutator.copy(JsonNode)`가 ObjectNode 아니면 호출되지 않도록 위 가드로 보장.

- [ ] **Step 5: EndpointExplorationRunner**
- line 156: `JsonNode baseInput = happy.body();`.
- 음수-검증(line 269~271): `if (validBody && baseInput instanceof ObjectNode ob && shape != null && !shape.collection())` 일 때만 `exploreNegativeValidationVariants(endpoint, shape, fieldConstraints, ob)` 호출(메서드 param은 `ObjectNode` 유지, cast된 `ob` 전달).
- line 362 `ObjectNode body = variant.input().body().deepCopy();` → `ObjectNode body = (ObjectNode) variant.input().body().deepCopy();`(변종은 객체).
- `happyInput` merge(787): `JsonNode bn = bodyPart.body(); if (!(bn instanceof ObjectNode bo)) return new SynthesizedInput(bn, bodyPart.seeds()); ObjectNode merged = bo.deepCopy(); merged.setAll((ObjectNode) pathPart.body()); ...`.
- 공유 `collectBodyValues`(bodyValues 대체, line 1018):
```java
    static Set<String> collectBodyValues(JsonNode body) {
        Set<String> v = new HashSet<>();
        if (body instanceof ArrayNode arr) { arr.forEach(e -> addNodeValues(e, v)); }
        else addNodeValues(body, v);
        return v;
    }
    private static void addNodeValues(JsonNode node, Set<String> v) {
        if (node.isValueNode()) { if (!node.isNull()) v.add(node.asText()); return; }
        node.fields().forEachRemaining(e -> { if (!e.getValue().isNull()) v.add(e.getValue().asText()); });
    }
```
  `bodyValues(...)` 호출부(line 401 등)를 `collectBodyValues`로 교체.

- [ ] **Step 6: ReadInputSynthesizer** line 193 `ObjectNode vbody = base.body().deepCopy();` → `ObjectNode vbody = (ObjectNode) base.body().deepCopy();`(read 경로는 객체).

- [ ] **Step 7: KafkaCaptureRunner / WsCaptureRunner**
- `KafkaCaptureRunner`: `JsonNode payload = happy.body();` + `publishAndCapture(..., JsonNode payload, ...)` param widen. 중복 변종(payload.deepCopy(), line 103 부근)은 `if (payload instanceof ObjectNode po) { ...po.deepCopy()... }`로 객체일 때만. key 추출 `payload.has("userId")`는 `payload.isObject() && payload.has(...)` 가드(배열이면 exchangeId fallback). `captureSql`의 payload 값 집합은 공유 `collectBodyValues(payload)` 사용(배열 원소 unwrap). `missingFieldPayload` 등 변종은 ObjectNode 전용 → 컬렉션 payload면 happy만.
- `WsCaptureRunner`: `List<JsonNode> payloads`; `happy.body().deepCopy()`/변종은 `instanceof ObjectNode` 가드; captureSql 값은 `collectBodyValues`.
- `KafkaExchange.payload()`/`WsExchange.payload()`가 `ObjectNode`면 `JsonNode`로 넓힘(shared-model) — 컴파일러가 가리킴. graph.json 직렬화는 JsonNode라 무영향.

- [ ] **Step 8: 컴파일 + 기존 테스트 green** → Run: `./gradlew :graph-rag-builder:test`
Expected: PASS(객체/Kafka/WS/OTEL 회귀 불변 — 컬렉션 경로는 happy-only).

- [ ] **Step 9: Commit** `feat(run/explore): JsonNode body propagation; collection happy-only (mutation/negative skip); shared collectBodyValues (array+value-node)`.

---

## Phase 4 — 생성기 (배열 body 방출 + 원소 unwrap)

### Task 7: FixtureComposer + Generator 배열 body

**Files:** `compose/FixtureComposer.java`, `generator/Generator.java`; Test `GeneratorCollectionBodyTest.java`

- [ ] **Step 1: 실패 테스트** — `FixtureComposer`에 `static String bodyFormatFor(JsonNode sampleInput)` 추출(현 bodyFormat 생성 로직을 이 메서드로 분리; 배열이면 `sampleInput.toString()`의 `%`→`%%` 이스케이프 반환, 객체면 기존 `{...}` 템플릿). 테스트:
```java
@Test void arraySampleInput_emittedAsArrayBody() throws Exception {
    JsonNode arr = io.graphrag.model.Json.mapper().readTree("[{\"name\":\"sample-name\",\"amount\":1}]");
    String body = io.graphrag.generator.compose.FixtureComposer.bodyFormatFor(arr);
    assertThat(body).startsWith("[").contains("\"name\"");
}
@Test void objectSampleInput_emittedAsObjectTemplate() throws Exception {
    JsonNode o = io.graphrag.model.Json.mapper().readTree("{\"name\":\"x\"}");
    assertThat(io.graphrag.generator.compose.FixtureComposer.bodyFormatFor(o)).startsWith("{");
}
```
- [ ] **Step 2: 실패 확인** → FAIL(메서드 없음).
- [ ] **Step 3: 구현**
- `FixtureComposer`: bodyFormat 생성부(141~163)를 `bodyFormatFor(path.sampleInput())` 호출로 교체 + 메서드 추출(배열 분기 포함). **변수/cleanup 추출**: 변수/insert/delete 추출 루프(line 89~)에서 `path.sampleInput()`이 배열이면 **첫 원소 ObjectNode**의 fields로 `varsByFieldValue` 계산(없으면 빈 채로 — happy 리터럴). 즉 `JsonNode src = sampleInput.isArray() && sampleInput.size()>0 ? sampleInput.get(0) : sampleInput;` 후 `src.fields()` 순회.
- `Generator.jsonBodyFromInput`(351~): `if (input instanceof ArrayNode) return input.toString();` 추가(현 `"{}"` 버그 수정). `knownByField`(277~) 추출: 배열이면 첫 원소 ObjectNode로 unwrap(없으면 빈 → 응답 단언 notNullValue 폴백).
- [ ] **Step 4: 통과** → Run: `./gradlew :test-generator:test --tests '*GeneratorCollectionBody*'` → PASS.
- [ ] **Step 5: Commit** `feat(generator): array request body (FixtureComposer.bodyFormatFor + Generator) + element unwrap for vars/assertions`.

---

## Phase 5 — E2E green + 회귀 + 문서

### Task 8: 수용 green + 전체 회귀 + docs

- [ ] **Step 1: 수용 green** — Run: `./gradlew :graph-rag-builder:test --tests '*BuilderCollectionE2eTest*'` → PASS(HTTP+Kafka 컬렉션).
- [ ] **Step 2: 전체 회귀** — `./gradlew test` + `bash e2e/run-e2e.sh`(+ `order.events.batch` 토픽 동작 확인) → 전 모듈 green, run-e2e E2E PASS.
- [ ] **Step 3: docs** — `docs/03-graph-rag-builder.md`(컬렉션 바디 지원/한계 D1~D7), 필요 시 `docs/04-test-generator.md`.
- [ ] **Step 4: Commit** `docs: collection @RequestBody/Kafka/WS support (happy-only) + limits`.

---

## Definition of Done

- [ ] `BuilderCollectionE2eTest` green (HTTP batch/by-ids skip 안 됨·배열·INSERT+API_PARAM; Kafka batch payload 배열).
- [ ] 단위: extractFromType(list/set/collection/iterable/array/raw/scalar/enum/dto)·bodyTypeKey, 배열 합성(DTO/scalar/enum)+scalarValue, JsonNode 파급 가드(happy 유지·변이/음수 skip), collectBodyValues(array+value-node), FixtureComposer.bodyFormatFor·Generator 배열 body, Kafka/WS 인덱서 컬렉션·러너 배열.
- [ ] 전체 회귀(`./gradlew test` + `run-e2e.sh`) green — 객체 바디/Kafka/WS/OTEL 회귀 불변.
- [ ] docs 갱신. PR 전 spec-compliance + 코드 품질 리뷰 트리아지.

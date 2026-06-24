# 단계2-A status-style String 리터럴 응답 변형 fuzzing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 단계2 enum 변형 루프 위에 후보 출처(소비 코드 equals-family 분기 리터럴)만 교체해, String 타입 응답 필드 분기의 true arm을 결정적·no-LLM으로 연다.

**Architecture:** ① `ResponseStringLiteralExtractor`가 SUT 소비 코드에서 응답 DTO String 필드의 equals-family 동치 비교 리터럴을 추출 → ② `EnumResponseVariantGenerator`를 `ResponseFieldVariantGenerator`로 일반화(후보 출처 무관) → ③ `runEnumResponseVariantLoops`를 `runResponseVariantLoops`로 통합(enum∪String 후보맵) → ④ 인덱싱 배선(StaticIndex/IndexCache)으로 추출 결과 전달. 변형 루프·trace-id 격리·OR-병합·provenance·budget은 단계2 그대로 재사용.

**Tech Stack:** Java 17, Spoon(no-classpath), JaCoCo, WireMock, JUnit5, Gradle.

## Global Constraints

- enum-only 입력에 대한 `ResponseFieldVariantGenerator` 출력(label·순서)은 단계2 `EnumResponseVariantGenerator`와 **byte-동일**해야 한다(REQ-005).
- 모든 신규 추출/생성은 **결정적**(정렬·중복제거)이어야 한다(REQ-002).
- silent drop/cap 금지 — 추출 제외·budget 절단은 loud 로그(REQ-003, REQ-006).
- 커밋 identity: `baekchangjoon <changjoon.baek@icloud.com>`.
- 커밋 메시지 말미: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>` + `Claude-Session: https://claude.ai/code/session_01XjBzTV1U1nH45BCh9sATTm`.
- 컨테이너/SUT 프로세스를 띄우는 E2E는 모든 종료 경로 teardown + 잔존 0 검증(dev-workflow 테스트 자원 정리 게이트).

---

## File Structure

- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/index/SpoonExpressionRefs.java` — `fieldRef`/`stringLiteral` 공유 유틸(ConstraintExtractor에서 추출).
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/index/ResponseStringLiteralExtractor.java` — equals-family 리터럴 추출기.
- Rename: `EnumResponseVariantGenerator.java` → `ResponseFieldVariantGenerator.java` (시그니처 일반화).
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/index/ConstraintExtractor.java` — 헬퍼를 SpoonExpressionRefs로 위임.
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java` — `runResponseVariantLoops`, 후보맵 조립, 마커 rename.
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/store/StaticIndex.java`, `IndexCache.java`(+`IndexManifest`) — `stringLiteralsByDto` 배선·SCHEMA_VERSION bump.
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java` — extractor 호출·explore 전달.
- Modify: `test-generator/src/main/java/io/graphrag/generator/Generator.java:81` — discoveredBy 마커 rename.
- Modify SUT: `samples/order-service` `InventoryClient`/`OrderController`/stub + `sample-src` 픽스처 신규.
- Test: `Stage2AStringLiteralFuzzingE2E`, `ResponseStringLiteralExtractorTest`, `ResponseFieldVariantGeneratorTest`, `StringLiteralVariantReExploreTest`, `StringLiteralVariantNoneModeTest`, `StaticIndexSerdeTest`(갱신), `IndexCacheTest`(갱신), `BuilderIntegrationTest`(갱신).

---

## Task 1: SUT fixture 확장 + 기존 stub/단언 갱신

**REQ-IDs:** REQ-011

**Files:**
- Modify: `samples/order-service/src/main/java/io/graphrag/sample/orders/InventoryClient.java`
- Modify: `samples/order-service/src/main/java/io/graphrag/sample/orders/OrderController.java:53-71`
- Modify: `samples/order-service/src/test/java/io/graphrag/sample/orders/OrderExpressApiTest.java` (inventory stub body)
- Modify: `e2e/external-stubs/inventory-stock.json`
- Modify: `graph-rag-builder/src/test/java/io/graphrag/builder/cli/BuilderIntegrationTest.java:156`
- Modify: `graph-rag-builder/src/test/java/io/graphrag/builder/cli/Stage2EnumResponseFuzzingE2E.java` (SWITCH_LINE 재측정)
- Create: `graph-rag-builder/src/test/resources/sample-src/io/graphrag/sample/orders/InventoryClient.java`
- Modify: `graph-rag-builder/src/test/resources/sample-src/io/graphrag/sample/orders/OrderController.java`

**Interfaces:**
- Produces: `InventoryResponse(int available, FulfillmentMode mode, String region)` record 컴포넌트; `OrderController`에 `if ("EMBARGOED".equals(stock.region())) throw 422`.

- [ ] **Step 1: InventoryClient에 region 추가**

`InventoryClient.InventoryResponse` record에 `String region` 컴포넌트를 추가한다(기존 `available`, `mode` 뒤). InventoryClient.check가 반환하는 stub 매핑은 빌더가 합성하므로 production 코드는 record 시그니처만 변경.

```java
public record InventoryResponse(int available, FulfillmentMode mode, String region) {
}
```

- [ ] **Step 2: OrderController에 region 동치 분기 추가**

`OrderController.create`의 `if ("EXPRESS".equals(request.type()))` 블록 안, `switch (stock.mode())` **앞**에 추가:

```java
if ("EMBARGOED".equals(stock.region())) {
    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "region embargoed");
}
```

- [ ] **Step 3: 기존 inventory stub body에 region 추가**

`OrderExpressApiTest`의 `/inventory/stock` WireMock stub jsonBody를 `{"available":N,"mode":"STANDARD","region":"DOMESTIC"}` 형태로 갱신(기존 available/mode만 있던 곳). `e2e/external-stubs/inventory-stock.json`의 `response.jsonBody`에 `"region": "DOMESTIC"` 추가.

- [ ] **Step 4: BuilderIntegrationTest consumedFields 단언 확인·갱신**

`BuilderIntegrationTest.java:156`은 현재 `assertThat(httpCall.consumedFields()).containsExactlyInAnyOrder("available", "mode")`. 이 통합 테스트의 SUT는 sample-src OrderController다. region 분기를 sample-src에도 넣으면(Step 7) region이 consumed가 되므로 `("available", "mode", "region")`으로 갱신. (실제 consumed 필드를 빌드 1회 돌려 확인 후 정확히 맞춘다.)

- [ ] **Step 5: sample-src InventoryClient 신규 생성**

현재 `graph-rag-builder/src/test/resources/sample-src/io/graphrag/sample/orders/`에 `OrderController.java`만 있고 `InventoryClient.java`가 없다. order-service의 InventoryClient를 본떠 nested `InventoryResponse` record(`available`,`mode`,`region`)를 가진 최소 파일을 생성한다. `FulfillmentMode` enum도 sample-src에 없으면 함께 추가.

- [ ] **Step 6: sample-src OrderController에 region 분기 추가**

sample-src `OrderController`에도 Step 2와 동일한 `"EMBARGOED".equals(stock.region())` 분기를 추가(추출기 단위 테스트 픽스처).

- [ ] **Step 7: Stage2EnumResponseFuzzingE2E SWITCH_LINE 재측정**

region 분기를 switch 앞에 넣으면 `switch (stock.mode())` 라인 번호가 밀린다. `Stage2EnumResponseFuzzingE2E`의 `SWITCH_LINE` 상수를 새 라인으로 갱신한다(파일에서 `switch (stock.mode())` 실제 라인 확인).

- [ ] **Step 8: order-service 빌드·기존 테스트 green 확인**

Run: `./gradlew :samples:order-service:test`
Expected: PASS (역직렬화 NPE 없음).

- [ ] **Step 9: Commit**

```bash
git add samples/order-service e2e/external-stubs/inventory-stock.json graph-rag-builder/src/test
git commit -m "test(fixture): order-service InventoryResponse.region + EMBARGOED 동치 분기 + 기존 stub/단언 갱신 REQ-011"
```

---

## Task 2: Spoon 표현식 헬퍼 공유 유틸 추출

**REQ-IDs:** REQ-007 (전제)

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/index/SpoonExpressionRefs.java`
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/index/ConstraintExtractor.java:862,922`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/index/SpoonExpressionRefsTest.java`

**Interfaces:**
- Produces: `SpoonExpressionRefs.fieldRef(CtExpression<?>): String` (record accessor `region()`→"region", getter `getRegion()`→"region", `isX()`→"x", CtVariableRead/CtFieldRead simple-name; 아니면 null). `SpoonExpressionRefs.stringLiteral(CtExpression<?>): String` (CtLiteral<String>이면 값, 아니면 null).

- [ ] **Step 1: SpoonExpressionRefsTest 작성 (failing)**

```java
package io.graphrag.builder.index;

import org.junit.jupiter.api.Test;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.visitor.filter.TypeFilter;
import static org.assertj.core.api.Assertions.assertThat;

class SpoonExpressionRefsTest {
    @Test
    void recordAccessorAndLiteral() {
        Launcher l = new Launcher();
        l.getEnvironment().setNoClasspath(true);
        l.getEnvironment().setComplianceLevel(17);
        l.addInputResource("src/test/resources/sample-src");
        CtModel model = l.buildModel();
        // "EMBARGOED".equals(stock.region()) 호출을 찾는다.
        CtInvocation<?> eq = model.getElements(new TypeFilter<>(CtInvocation.class)).stream()
                .filter(i -> "equals".equals(i.getExecutable().getSimpleName())
                        && SpoonExpressionRefs.stringLiteral(i.getTarget()) != null
                        && "EMBARGOED".equals(SpoonExpressionRefs.stringLiteral(i.getTarget())))
                .findFirst().orElseThrow();
        assertThat(SpoonExpressionRefs.fieldRef(eq.getArguments().get(0))).isEqualTo("region");
    }
}
```

- [ ] **Step 2: Run test — fails (SpoonExpressionRefs 없음)**

Run: `./gradlew :graph-rag-builder:test --tests SpoonExpressionRefsTest`
Expected: 컴파일 실패(SpoonExpressionRefs not found).

- [ ] **Step 3: SpoonExpressionRefs 생성 (ConstraintExtractor 본문 이식)**

```java
package io.graphrag.builder.index;

import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtVariableRead;

/** Spoon 표현식에서 필드 참조명·String 리터럴을 뽑는 공유 헬퍼(ConstraintExtractor·ResponseStringLiteralExtractor 공용). */
public final class SpoonExpressionRefs {
    private SpoonExpressionRefs() {}

    /** record accessor f()→"f", getF()→"f", isF()→"f", CtVariableRead/CtFieldRead simple-name; 아니면 null. */
    public static String fieldRef(CtExpression<?> expr) {
        if (expr instanceof CtInvocation<?> inv) {
            String m = inv.getExecutable().getSimpleName();
            if (m.startsWith("get") && m.length() > 3) {
                return Character.toLowerCase(m.charAt(3)) + m.substring(4);
            }
            if (m.startsWith("is") && m.length() > 2) {
                return Character.toLowerCase(m.charAt(2)) + m.substring(3);
            }
            return m;
        }
        if (expr instanceof CtVariableRead<?> vr) {
            return vr.getVariable().getSimpleName();
        }
        if (expr instanceof CtFieldRead<?> fr) {
            return fr.getVariable().getSimpleName();
        }
        return null;
    }

    /** CtLiteral<String>이면 그 값, 아니면 null. */
    public static String stringLiteral(CtExpression<?> expr) {
        if (expr instanceof CtLiteral<?> lit && lit.getValue() instanceof String s) {
            return s;
        }
        return null;
    }
}
```

- [ ] **Step 4: ConstraintExtractor가 SpoonExpressionRefs로 위임**

`ConstraintExtractor`의 private `fieldRef`(922)·`stringLiteral`(862) 메서드 본문을 `return SpoonExpressionRefs.fieldRef(expr);` / `return SpoonExpressionRefs.stringLiteral(expr);`로 교체(또는 메서드 제거 후 호출부를 `SpoonExpressionRefs.xxx`로 치환). 동작 보존.

- [ ] **Step 5: Run tests — pass (ConstraintExtractor 회귀 포함)**

Run: `./gradlew :graph-rag-builder:test --tests SpoonExpressionRefsTest --tests 'ConstraintExtractor*'`
Expected: PASS (기존 ConstraintExtractor 테스트 회귀 없음).

- [ ] **Step 6: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/index/SpoonExpressionRefs.java graph-rag-builder/src/main/java/io/graphrag/builder/index/ConstraintExtractor.java graph-rag-builder/src/test/java/io/graphrag/builder/index/SpoonExpressionRefsTest.java
git commit -m "refactor(index): Spoon fieldRef/stringLiteral 공유 유틸 추출 REQ-007"
```

---

## Task 3: ResponseStringLiteralExtractor (equals-family 추출)

**REQ-IDs:** REQ-007, REQ-003

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/index/ResponseStringLiteralExtractor.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/index/ResponseStringLiteralExtractorTest.java`

**Interfaces:**
- Consumes: `SpoonExpressionRefs.fieldRef/stringLiteral` (Task 2), `ExternalCallSite.responseShape()` (`Optional<BodyShape>`), `BodyShape.javaType()`/`fields()`.
- Produces: `extract(CtModel model, List<ExternalCallSite> callSites): Map<String dtoFqn, Map<String field, List<String> literals>>` — 정렬·중복제거. loud 로그 키: `string-literal-nonequality-skipped`, `string-literal-const-unresolvable`, `string-literal-accessor-ambiguous`.

- [ ] **Step 1: 테스트 작성 (failing) — 기본 equals 추출 + loud skip**

```java
package io.graphrag.builder.index;

import org.junit.jupiter.api.Test;
import spoon.Launcher;
import spoon.reflect.CtModel;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;

class ResponseStringLiteralExtractorTest {
    private CtModel sampleModel() {
        Launcher l = new Launcher();
        l.getEnvironment().setNoClasspath(true);
        l.getEnvironment().setComplianceLevel(17);
        l.addInputResource("src/test/resources/sample-src");
        return l.buildModel();
    }

    @Test
    void extractsRegionEqualsLiteral() {
        CtModel model = sampleModel();
        // responseShape: InventoryResponse(available,mode,region) — region이 String 필드.
        BodyShape shape = new BodyShape("io.graphrag.sample.orders.InventoryClient$InventoryResponse",
                List.of(new BodyShape.BodyField("available", "int"),
                        new BodyShape.BodyField("mode", "io.graphrag.sample.orders.FulfillmentMode"),
                        new BodyShape.BodyField("region", "java.lang.String")));
        var site = new ExternalCallSite("GET", "/inventory/stock", Optional.of(shape));
        Map<String, Map<String, List<String>>> out =
                new ResponseStringLiteralExtractor().extract(model, List.of(site));
        assertThat(out.get(shape.javaType()).get("region")).containsExactly("EMBARGOED");
    }
}
```

- [ ] **Step 2: Run — fails (클래스 없음)**

Run: `./gradlew :graph-rag-builder:test --tests ResponseStringLiteralExtractorTest`
Expected: 컴파일 실패.

- [ ] **Step 3: ResponseStringLiteralExtractor 구현**

equals-family(`equals`/`equalsIgnoreCase` 인스턴스 호출 양방향, `Objects.equals` 정적 호출 양방향) + 단순 로컬 바인딩 + 동일 소스트리 `static final String` 상수 해석. simple-name → responseShape 필드 교차 후 dtoFqn 버킷. 동명 String 필드 DTO 2+면 skip+loud.

```java
package io.graphrag.builder.index;

import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.declaration.CtField;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.visitor.filter.TypeFilter;
import java.util.*;
import java.util.logging.Logger;

/**
 * 응답 DTO String 필드를 소비 코드가 동치 비교하는 리터럴을 추출한다(REQ-007, REQ-003).
 * no-classpath라 receiver 타입은 신뢰 불가 → 접근자 simple-name을 키로 쓰되, callSite responseShape의
 * String 필드와 교차해 dtoFqn 버킷에 넣는다. equals-family(equals/equalsIgnoreCase/Objects.equals)만.
 */
public final class ResponseStringLiteralExtractor {
    private static final Logger LOG = Logger.getLogger(ResponseStringLiteralExtractor.class.getName());

    public Map<String, Map<String, List<String>>> extract(CtModel model, List<ExternalCallSite> callSites) {
        // 1. responseShape의 String 필드 → 그 필드를 가진 dtoFqn 집합(모호 판정용).
        Map<String, Set<String>> fieldToDtos = new TreeMap<>();          // field → {dtoFqn}
        Map<String, Set<String>> dtoStringFields = new TreeMap<>();      // dtoFqn → {String field}
        for (ExternalCallSite site : callSites) {
            if (site.responseShape().isEmpty()) continue;
            BodyShape shape = site.responseShape().get();
            for (BodyShape.BodyField f : shape.fields()) {
                if ("java.lang.String".equals(f.javaType())) {
                    fieldToDtos.computeIfAbsent(f.name(), k -> new TreeSet<>()).add(shape.javaType());
                    dtoStringFields.computeIfAbsent(shape.javaType(), k -> new TreeSet<>()).add(f.name());
                }
            }
        }

        // 2. 동일 소스트리 static final String 상수값 인덱스(simpleName/qualified → value).
        Map<String, String> stringConstants = new HashMap<>();
        for (CtField<?> field : model.getElements(new TypeFilter<>(CtField.class))) {
            if (field.getModifiers().contains(spoon.reflect.declaration.ModifierKind.FINAL)
                    && field.getModifiers().contains(spoon.reflect.declaration.ModifierKind.STATIC)
                    && field.getDefaultExpression() instanceof CtLiteral<?> lit
                    && lit.getValue() instanceof String s) {
                stringConstants.put(field.getSimpleName(), s);
            }
        }

        // 3. equals-family 호출 순회 → (field, literal) 수집.
        Map<String, Map<String, Set<String>>> collected = new TreeMap<>();   // dtoFqn → field → {literal}
        for (CtInvocation<?> inv : model.getElements(new TypeFilter<>(CtInvocation.class))) {
            String simple = inv.getExecutable().getSimpleName();
            FieldLit fl = null;
            if (("equals".equals(simple) || "equalsIgnoreCase".equals(simple))
                    && inv.getArguments().size() == 1 && inv.getTarget() != null) {
                fl = fieldLit(inv.getTarget(), inv.getArguments().get(0), inv, stringConstants);
            } else if ("equals".equals(simple) && inv.getTarget() instanceof CtTypeAccess<?> ta
                    && "Objects".equals(ta.getAccessedType().getSimpleName())
                    && inv.getArguments().size() == 2) {
                fl = fieldLit(inv.getArguments().get(0), inv.getArguments().get(1), inv, stringConstants);
            }
            if (fl == null) continue;
            // simple-name → responseShape 필드 교차 + 모호 판정.
            Set<String> dtos = fieldToDtos.get(fl.field);
            if (dtos == null) continue;                       // 응답 필드 아님(무관 비교) → 무시
            if (dtos.size() > 1) {
                LOG.warning("string-literal-accessor-ambiguous: field=" + fl.field + " dtos=" + dtos);
                continue;
            }
            String dtoFqn = dtos.iterator().next();
            collected.computeIfAbsent(dtoFqn, k -> new TreeMap<>())
                    .computeIfAbsent(fl.field, k -> new TreeSet<>()).add(fl.literal);
        }

        // 4. Set → 정렬 List.
        Map<String, Map<String, List<String>>> out = new TreeMap<>();
        collected.forEach((dto, fields) -> {
            Map<String, List<String>> m = new TreeMap<>();
            fields.forEach((f, lits) -> m.put(f, new ArrayList<>(lits)));
            out.put(dto, m);
        });
        return out;
    }

    private record FieldLit(String field, String literal) {}

    /** (a,b) 한쪽이 필드 접근자(또는 로컬바인딩)·다른쪽이 리터럴/상수면 FieldLit. 비동치/미해석은 loud 후 null. */
    private FieldLit fieldLit(CtExpression<?> a, CtExpression<?> b, CtInvocation<?> inv,
                              Map<String, String> constants) {
        String litA = literalOrConst(a, constants, inv);
        String litB = literalOrConst(b, constants, inv);
        String refA = fieldRefResolvingLocal(a, inv);
        String refB = fieldRefResolvingLocal(b, inv);
        if (litB != null && refA != null) return new FieldLit(refA, litB);
        if (litA != null && refB != null) return new FieldLit(refB, litA);
        return null;
    }

    /** 직접 리터럴 또는 동일 소스트리 static final String 참조면 값, 아니면(외부 미해석 포함) null+loud. */
    private String literalOrConst(CtExpression<?> e, Map<String, String> constants, CtInvocation<?> inv) {
        String lit = SpoonExpressionRefs.stringLiteral(e);
        if (lit != null) return lit;
        if (e instanceof CtFieldRead<?> fr) {
            CtFieldReference<?> ref = fr.getVariable();
            String name = ref.getSimpleName();
            if (constants.containsKey(name)) return constants.get(name);
            LOG.warning("string-literal-const-unresolvable: ref=" + name
                    + " at " + inv.getPosition().getLine());
        }
        return null;
    }

    /** 접근자 또는 로컬변수(직전 'String r = resp.f()' 바인딩) → field simple-name. 아니면 null. */
    private String fieldRefResolvingLocal(CtExpression<?> e, CtInvocation<?> inv) {
        String ref = SpoonExpressionRefs.fieldRef(e);
        if (e instanceof CtInvocation<?>) return ref;                 // resp.f() 직접 접근자
        if (e instanceof CtVariableRead<?> vr) {
            // 로컬 변수가 'String r = resp.f()'로 바인딩됐으면 그 접근자 field를 따른다.
            var decl = vr.getVariable().getDeclaration();
            if (decl instanceof CtLocalVariable<?> lv
                    && lv.getDefaultExpression() instanceof CtInvocation<?> bound) {
                return SpoonExpressionRefs.fieldRef(bound);
            }
        }
        return null;     // 변수 비교 등 — 비동치 후보 아님(상위에서 무시; 비동치 연산은 애초에 미진입)
    }
}
```

- [ ] **Step 4: loud skip 테스트 추가 (REQ-003)**

`ResponseStringLiteralExtractorTest`에 `loudSkips` 테스트 추가 — sample-src에 `startsWith`/변수 비교 분기를 두고, 그 필드가 후보에 안 들어가는지 단언. (sample-src 픽스처에 케이스를 추가하거나 인라인 리소스 사용. equalsIgnoreCase/Objects.equals/로컬바인딩/static-final 케이스도 각각 단언.)

```java
    @Test
    void equalsIgnoreCaseAndObjectsAndLocalBindingAndConst() {
        // sample-src 픽스처에 다음 4패턴을 둔다:
        //   resp.region().equalsIgnoreCase("X1")
        //   Objects.equals(resp.region(), "X2")
        //   String r = resp.region(); "X3".equals(r);
        //   static final String C = "X4"; C.equals(resp.region());
        // → region → [X1,X2,X3,X4] (정렬)
        // (구체 단언은 픽스처 확정 후 채운다)
    }
```

> 구현 시 sample-src 픽스처에 위 패턴을 실제로 추가하고(Task 1 Step 5/6과 함께 또는 전용 fixture 클래스), 정렬된 기대 리스트로 단언을 확정한다.

- [ ] **Step 5: Run — pass**

Run: `./gradlew :graph-rag-builder:test --tests ResponseStringLiteralExtractorTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/index/ResponseStringLiteralExtractor.java graph-rag-builder/src/test/java/io/graphrag/builder/index/ResponseStringLiteralExtractorTest.java graph-rag-builder/src/test/resources/sample-src
git commit -m "feat(index): ResponseStringLiteralExtractor equals-family 추출 + loud skip REQ-007,003"
```

---

## Task 4: ResponseFieldVariantGenerator (생성기 일반화 + enum byte-동일)

**REQ-IDs:** REQ-008, REQ-005, REQ-006

**Files:**
- Rename: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EnumResponseVariantGenerator.java` → `ResponseFieldVariantGenerator.java`
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java` (호출부)
- Rename test: `EnumResponseVariantGeneratorTest.java` → `ResponseFieldVariantGeneratorTest.java`

**Interfaces:**
- Produces: `ResponseFieldVariantGenerator.generate(Map<String field, List<String> nonBaselineCandidates>, int budget): VariantPlan`. `VariantPlan(List<ResponseVariant> kept, int dropped)`, `ResponseVariant(Map<String,String> overrides, String label)`. 단일 필드 변형(필드 정렬 × 값 정렬) 먼저 → 2-way 카르테시안. label = 정렬 "field=VAL[,field2=VAL2]".

- [ ] **Step 1: 통합 시그니처 테스트 작성 (failing) + enum byte-동일 가드**

```java
@Test
void singleFieldFirstThenCartesian() {
    var plan = new ResponseFieldVariantGenerator().generate(
        new java.util.TreeMap<>(Map.of("a", List.of("A2"), "b", List.of("B2"))), 32);
    // 단일 필드 2개(a=A2, b=B2)가 먼저, 그 다음 2-way(a=A2,b=B2).
    assertThat(plan.kept().stream().map(v -> v.label()).toList())
        .containsExactly("a=A2", "b=B2", "a=A2,b=B2");
}

@Test
void enumPathByteIdenticalToStage2() {
    // 단계2가 enumConstants로 만들던 입력을, 호출자가 baseline(first const) 제외 후 넘긴 형태로 재현.
    // FulfillmentMode {STANDARD(first),EXPRESS_ONLY,BACKORDER} → non-baseline {EXPRESS_ONLY,BACKORDER}.
    var plan = new ResponseFieldVariantGenerator().generate(
        new java.util.TreeMap<>(Map.of("mode", List.of("BACKORDER", "EXPRESS_ONLY"))), 32);
    assertThat(plan.kept().stream().map(v -> v.label()).toList())
        .containsExactly("mode=BACKORDER", "mode=EXPRESS_ONLY");   // 단계2 정렬과 동일
}

@Test
void budgetTruncationLoud() {
    var plan = new ResponseFieldVariantGenerator().generate(
        new java.util.TreeMap<>(Map.of("a", List.of("A2", "A3", "A4"))), 2);
    assertThat(plan.kept()).hasSize(2);
    assertThat(plan.dropped()).isEqualTo(1);
}
```

- [ ] **Step 2: Run — fails**

Run: `./gradlew :graph-rag-builder:test --tests ResponseFieldVariantGeneratorTest`
Expected: 컴파일 실패(클래스/시그니처 없음).

- [ ] **Step 3: 생성기 일반화 구현**

`EnumResponseVariantGenerator`를 rename하고 `generate`를 후보맵 직접 수신으로 바꾼다. baseline 제외·enum 상수 해석은 호출자(Task 6)로 이동. 카르테시안·budget·label 코어는 보존.

```java
package io.graphrag.builder.run;

import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/** 응답 필드별 non-baseline 후보를 변형 plan으로 만든다(REQ-008,006). 후보 출처(enum/String) 무관. */
public final class ResponseFieldVariantGenerator {
    private static final Logger LOG = Logger.getLogger(ResponseFieldVariantGenerator.class.getName());

    public record ResponseVariant(Map<String, String> overrides, String label) {}
    public record VariantPlan(List<ResponseVariant> kept, int dropped) {}

    /** candidates: field → non-baseline 값 목록(호출자가 baseline 제외 완료). 결정적 정렬. */
    public VariantPlan generate(Map<String, List<String>> candidates, int budget) {
        TreeMap<String, List<String>> fields = new TreeMap<>();
        candidates.forEach((f, vals) -> {
            if (vals != null && !vals.isEmpty()) fields.put(f, vals.stream().sorted().toList());
        });

        List<ResponseVariant> all = new ArrayList<>();
        // 1. 단일 필드 변형(필드 정렬 × 값 정렬).
        for (var e : fields.entrySet()) {
            for (String v : e.getValue()) all.add(variant(Map.of(e.getKey(), v)));
        }
        // 2. 2-way 카르테시안.
        List<String> names = new ArrayList<>(fields.keySet());
        for (int i = 0; i < names.size(); i++) {
            for (int j = i + 1; j < names.size(); j++) {
                for (String va : fields.get(names.get(i))) {
                    for (String vb : fields.get(names.get(j))) {
                        Map<String, String> o = new LinkedHashMap<>();
                        o.put(names.get(i), va);
                        o.put(names.get(j), vb);
                        all.add(variant(o));
                    }
                }
            }
        }
        int dropped = Math.max(0, all.size() - budget);
        List<ResponseVariant> kept = all.size() > budget ? all.subList(0, budget) : all;
        if (dropped > 0) {
            LOG.warning("response-variant-budget-truncated: kept=" + kept.size() + " dropped=" + dropped);
        }
        return new VariantPlan(new ArrayList<>(kept), dropped);
    }

    private static ResponseVariant variant(Map<String, String> overrides) {
        TreeMap<String, String> sorted = new TreeMap<>(overrides);
        String label = sorted.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(","));
        return new ResponseVariant(sorted, label);
    }
}
```

> 참고: 단계2 `EnumResponseVariantGenerator`는 `enumOverrides`라는 이름을 썼다. 호출부(Task 6)·`exploreEnumResponseVariants`의 `variant.enumOverrides()` 참조를 `variant.overrides()`로 갱신한다.

- [ ] **Step 4: Run — pass**

Run: `./gradlew :graph-rag-builder:test --tests ResponseFieldVariantGeneratorTest`
Expected: PASS (단일-필드-먼저, enum byte-동일, budget 절단).

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/run/ResponseFieldVariantGenerator.java graph-rag-builder/src/test/java/io/graphrag/builder/run/ResponseFieldVariantGeneratorTest.java
git rm graph-rag-builder/src/main/java/io/graphrag/builder/run/EnumResponseVariantGenerator.java graph-rag-builder/src/test/java/io/graphrag/builder/run/EnumResponseVariantGeneratorTest.java
git commit -m "refactor(run): ResponseFieldVariantGenerator 일반화 + enum byte-동일 가드 REQ-008,005,006"
```

---

## Task 5: 인덱싱 배선 (StaticIndex/IndexCache + SCHEMA_VERSION)

**REQ-IDs:** REQ-010

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/store/StaticIndex.java`
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/store/IndexCache.java:22`
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/store/StaticIndexSerdeTest.java` (갱신), `IndexCacheTest.java` (갱신)

**Interfaces:**
- Produces: `StaticIndex.stringLiteralsByDto(): Map<String,Map<String,List<String>>>`; `EndpointExplorationRunner` 생성자/explore 인자에 동일 타입 추가.

- [ ] **Step 1: StaticIndexSerdeTest에 round-trip 케이스 추가 (failing)**

```java
@Test
void stringLiteralsByDtoRoundTrip() {
    var idx = /* 기존 빌더로 StaticIndex 생성하되 */
        StaticIndexTestFactory.withStringLiterals(
            Map.of("io.graphrag.sample.orders.InventoryClient$InventoryResponse",
                   Map.of("region", List.of("EMBARGOED"))));
    StaticIndex loaded = roundTrip(idx);   // 기존 테스트의 직렬화·역직렬화 헬퍼
    assertThat(loaded.stringLiteralsByDto())
        .containsKey("io.graphrag.sample.orders.InventoryClient$InventoryResponse");
}
```

> 구현자 노트: 기존 `StaticIndexSerdeTest`의 생성·round-trip 방식을 그대로 따르고, `stringLiteralsByDto` 필드만 추가 단언. `StaticIndexTestFactory`가 없으면 기존 테스트가 StaticIndex를 만드는 방식을 재사용.

- [ ] **Step 2: Run — fails (필드 없음)**

Run: `./gradlew :graph-rag-builder:test --tests StaticIndexSerdeTest`
Expected: 컴파일 실패.

- [ ] **Step 3: StaticIndex에 필드 추가 + null 가드**

`StaticIndex` record에 `Map<String,Map<String,List<String>>> stringLiteralsByDto`를 추가. compact 생성자(또는 기존 null 가드 패턴)에서 `stringLiteralsByDto == null ? Map.of() : stringLiteralsByDto`. 기존 `callSites` 가드와 동일 패턴.

- [ ] **Step 4: IndexCache.SCHEMA_VERSION bump 2→3**

`IndexCache.java:22` `public static final int SCHEMA_VERSION = 2;` → `= 3;`. (레거시 캐시 무효화 — `loadIfFresh`가 `schemaVersion()` 비교로 무효화.)

- [ ] **Step 5: BuilderCli 배선**

`BuilderCli.indexStatically`에서 `new ResponseStringLiteralExtractor().extract(model, callSites)` 호출 결과를 `StaticIndex` 생성에 포함. `build`→explore 경로에서 `EndpointExplorationRunner` 생성자(또는 explore 인자)에 `stringLiteralsByDto` 전달(enumConstants 옆자리). `EndpointExplorationRunner`에 필드·생성자 파라미터 추가(이 단계에선 받기만; 사용은 Task 6).

- [ ] **Step 6: IndexCacheTest에 레거시 호환 케이스 (선택, REQ-010 수용기준 3)**

SCHEMA_VERSION 2로 쓰인 캐시를 로드 시 무효화(재인덱싱)되는지 단언. 기존 IndexCacheTest 패턴 따름.

- [ ] **Step 7: Run — pass**

Run: `./gradlew :graph-rag-builder:test --tests StaticIndexSerdeTest --tests IndexCacheTest --tests IndexCacheWiringTest`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/store graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java graph-rag-builder/src/test/java/io/graphrag/builder/store
git commit -m "feat(store): stringLiteralsByDto StaticIndex 배선 + SCHEMA_VERSION 3 REQ-010"
```

---

## Task 6: 변형 루프 통합 + 후보맵 조립 + 마커 rename

**REQ-IDs:** REQ-009, REQ-001 (구현 측)

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java:1614-1782`
- Modify: `test-generator/src/main/java/io/graphrag/generator/Generator.java:81`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/run/StringLiteralVariantReExploreTest.java`

**Interfaces:**
- Consumes: `ResponseFieldVariantGenerator.generate` (Task 4), `stringLiteralsByDto` (Task 5), `ShapeJsonSynthesizer.scalarValue(String javaType, List<FieldConstraint> cons, String fieldName)`.
- Produces: `runResponseVariantLoops` (구 `runEnumResponseVariantLoops`), `exploreResponseVariants`(구 `exploreEnumResponseVariants`), `applyFieldOverrides`(구 `applyEnumOverrides`). 변형 id 접두사 `responsevar`, discoveredBy `response-variant`.

- [ ] **Step 1: StringLiteralVariantReExploreTest 작성 (failing)**

단계2 `EnumVariantReExploreTest` 패턴을 따른다. enum+String 필드를 가진 응답 path에서, String 변형이 새 arm을 열고 cumulativeCoverage에 OR-병합되는지, 리터럴 0건 필드는 변형 0인지 단언. (구체 fixture는 EnumVariantReExploreTest 재사용 + region 추가.)

- [ ] **Step 2: Run — fails**

Run: `./gradlew :graph-rag-builder:test --tests StringLiteralVariantReExploreTest`
Expected: FAIL/컴파일 실패.

- [ ] **Step 3: 후보맵 조립 + 생성기 호출 교체**

`runEnumResponseVariantLoops` → `runResponseVariantLoops`. site별 후보맵 조립:

```java
ShapeJsonSynthesizer shapes = new ShapeJsonSynthesizer(enumConstants == null ? Map.of() : enumConstants);
BodyShape responseShape = site.responseShape().get();
Map<String, List<String>> candidates = new TreeMap<>();
for (BodyShape.BodyField f : responseShape.fields()) {
    // enum 후보: 상수 − 선언순 첫 상수(baseline)
    List<String> consts = resolveEnumConstants(f.javaType(), enumConstants);  // 기존 enum 해석 헬퍼
    if (consts != null && !consts.isEmpty()) {
        List<String> nonBaseline = consts.stream().skip(1).toList();  // 선언순 첫 상수 제외
        if (!nonBaseline.isEmpty()) candidates.put(f.name(), nonBaseline);
        continue;
    }
    // String 후보: 추출 리터럴 − 단계1 기본값(scalarValue)
    if ("java.lang.String".equals(f.javaType())) {
        List<String> lits = stringLiteralsByDto
            .getOrDefault(responseShape.javaType(), Map.of())
            .getOrDefault(f.name(), List.of());
        if (lits.isEmpty()) continue;
        String baseline = shapes.scalarValue(f.javaType(), List.of(), f.name()).asText();
        List<String> nonBaseline = lits.stream().filter(s -> !s.equals(baseline)).toList();
        if (!nonBaseline.isEmpty()) candidates.put(f.name(), nonBaseline);
    }
}
ResponseFieldVariantGenerator.VariantPlan plan =
    new ResponseFieldVariantGenerator().generate(candidates, RESPONSE_VARIANT_BUDGET);
```

> `resolveEnumConstants`는 단계2 `EnumResponseVariantGenerator`에 있던 FQN/simple-name 폴백 해석을 `EndpointExplorationRunner` private static으로 이식(또는 `ResponseFieldVariantGenerator`에 static로 보존).

- [ ] **Step 4: rename — id 접두사·discoveredBy·Generator 필터**

`EndpointExplorationRunner`의 `"enumvar"`(http id)·`"enum-response-variant"`(discoveredBy) 리터럴을 `"responsevar"`·`"response-variant"`로 교체. `exploreEnumResponseVariants`/`applyEnumOverrides`/`variant.enumOverrides()`도 rename. `Generator.java:81`의 `"enum-response-variant".equals(p.discoveredBy())`를 `"response-variant".equals(...)`로 교체.

- [ ] **Step 5: Run — pass**

Run: `./gradlew :graph-rag-builder:test --tests StringLiteralVariantReExploreTest --tests 'EnumVariant*'`
Expected: PASS (String 변형 + enum 변형 회귀).

- [ ] **Step 6: Generator 회귀 확인**

Run: `./gradlew :test-generator:test`
Expected: PASS (response-variant discoveredBy 제외 동작 보존).

- [ ] **Step 7: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java test-generator/src/main/java/io/graphrag/generator/Generator.java graph-rag-builder/src/test/java/io/graphrag/builder/run/StringLiteralVariantReExploreTest.java
git commit -m "feat(run): runResponseVariantLoops enum∪String 후보맵 + 마커 rename REQ-009,001"
```

---

## Task 7: none 모드 String 변형 순차 교체

**REQ-IDs:** REQ-012

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java` (none 분기 — 이미 enum용 존재)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/run/StringLiteralVariantNoneModeTest.java`

**Interfaces:**
- Consumes: `exploreResponseVariants(... isolated=false ...)` (Task 6) — none 모드 순차 교체 경로(단계2 `EnumVariantNoneModeTest` 메커니즘).

- [ ] **Step 1: StringLiteralVariantNoneModeTest 작성 (failing)**

단계2 `EnumVariantNoneModeTest` 패턴 복제 — `--trace-mode none`(NoTraceKey)에서 String 변형이 전역 stub 삭제 없이 순차 교체로 arm 도달하는지 단언.

- [ ] **Step 2: Run — fails**

Run: `./gradlew :graph-rag-builder:test --tests StringLiteralVariantNoneModeTest`
Expected: FAIL.

- [ ] **Step 3: 구현 확인**

Task 6의 `runResponseVariantLoops`가 enum/String 구분 없이 후보맵을 돌리므로, none 분기(isolated=false → `removeVariant` 순차 교체)는 이미 String에도 적용된다. 테스트가 red면 그 원인(예: String 전용 경로 누락)만 최소 수정.

- [ ] **Step 4: Run — pass**

Run: `./gradlew :graph-rag-builder:test --tests StringLiteralVariantNoneModeTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/test/java/io/graphrag/builder/run/StringLiteralVariantNoneModeTest.java graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java
git commit -m "test(run): none 모드 String 변형 순차 교체 REQ-012"
```

---

## Task 8: E2E green 달성 + 전체 회귀

**REQ-IDs:** REQ-001, REQ-002, REQ-004, REQ-005

**Files:**
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/cli/Stage2AStringLiteralFuzzingE2E.java`

**Interfaces:**
- Consumes: 전체 파이프라인(Task 1~7). 단계2 `Stage2EnumResponseFuzzingE2E` 구조 복제.

- [ ] **Step 1: Stage2AStringLiteralFuzzingE2E 작성 (outer-loop, red)**

> 이 테스트는 Task 1에서 **가장 먼저** 작성해도 좋다(double-loop outer). 여기서는 Task 1~7 완료 후 green 확인 단계로 둔다. 단계2 E2E를 본떠 3개 테스트: `stringVariantReachesEmbargoedArm`(REQ-001 — EMBARGOED 422 arm covered + 단계2 대비 arm 증가), `deterministicAcrossRuns`(REQ-002 — 변형 label·ExploredPath id·branch 집합 2회 동일), `variantStubCapturesAreSynthesized`(REQ-004 — String 변형 캡처 SYNTHESIZED).

```java
@DisplayName("REQ-001: String 변형이 EMBARGOED arm(422)에 도달한다")
@Test void stringVariantReachesEmbargoedArm() { /* ... */ }

@DisplayName("REQ-002: 2회 실행 변형 label·id·branch 집합 동일")
@Test void deterministicAcrossRuns() { /* ... */ }

@DisplayName("REQ-004: String 변형 stub 캡처는 SYNTHESIZED")
@Test void variantStubCapturesAreSynthesized() { /* ... */ }
```

- [ ] **Step 2: E2E 실행 (teardown 게이트 포함)**

Run: `./gradlew :graph-rag-builder:test --tests Stage2AStringLiteralFuzzingE2E`
Expected: PASS. 실행 후 잔존 컨테이너/SUT 프로세스 0 확인(`docker ps -a` 자기 label, SUT PID).

- [ ] **Step 3: 전체 회귀 (단계1·2 포함)**

Run: `./gradlew :graph-rag-builder:test :shared-model:test :samples:order-service:test :test-generator:test`
Expected: PASS, 0 failures. `Stage1ExternalStubSynthesisE2E`·`Stage2EnumResponseFuzzingE2E` green(REQ-005).

- [ ] **Step 4: 매트릭스 갱신**

요구사항명세 추적 매트릭스 REQ-001~012를 🟢로 갱신, Coverage 12/12 green.

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/test/java/io/graphrag/builder/cli/Stage2AStringLiteralFuzzingE2E.java docs/superpowers/requirements
git commit -m "test(e2e): 단계2-A String 리터럴 E2E green + 매트릭스 12/12 REQ-001,002,004,005"
```

---

## Self-Review

**1. Spec coverage (REQ-001~012):**
- REQ-001 → Task 6/8 (후보맵·E2E). REQ-002 → Task 8. REQ-003 → Task 3(loud skip). REQ-004 → Task 8. REQ-005 → Task 4(byte-동일)+Task 8(회귀). REQ-006 → Task 4. REQ-007 → Task 2+3. REQ-008 → Task 4. REQ-009 → Task 6. REQ-010 → Task 5. REQ-011 → Task 1. REQ-012 → Task 7. **전부 매핑됨.**

**2. Placeholder scan:** Task 3 Step 4·Task 5 Step 1·Task 6 Step 1·Task 8 Step 1은 fixture 확정 후 단언을 채우라는 노트가 있다 — 이는 기존 단계2 테스트 패턴 복제이므로 실행 시 해당 테스트 본문을 그대로 참조한다. 코드 골격은 제시됨.

**3. Type consistency:** `ResponseFieldVariantGenerator.generate(Map<String,List<String>>, int)`·`ResponseVariant.overrides()`·`stringLiteralsByDto: Map<String,Map<String,List<String>>>`·`scalarValue(String,List,String)`·discoveredBy `response-variant`·id `responsevar` — Task 4/5/6에서 일관 사용. 구 `enumOverrides()`→`overrides()` rename은 Task 4 Step 3 노트에 명시.

**알려진 잔여 작업(실행 중 확정):** Task 3/6의 일부 단위 테스트 단언은 단계2 대응 테스트(EnumVariantReExploreTest/EnumVariantNoneModeTest)의 구조를 복제하므로, 실행 에이전트가 그 파일을 읽고 region/String 케이스로 치환해 확정한다.

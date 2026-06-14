# 제약 지향 입력 생성 (Constraint-Directed Input Generation) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bean Validation 제약과 handler 비교식 경계를 정적으로 추출해 `InputMutator`로 환류하여, 현재 generic boundary 변이가 못 닿는 검증·핸들러 분기를 결정적으로 커버한다.

**Architecture:** 심볼릭/콘콜릭/SMT 없이, 소스에 리터럴로 박힌 제약값을 그대로 변이로 만든다. 두 신규 값 소스(Bean Validation, handler 비교식)는 기존 `InputMutator` → `markTried`/`KnownCoverage` 파이프라인에 가산적으로 합류한다. **권고 2 반영**: handler 비교식은 정규식이 아니라 Spoon AST(`CtBinaryOperator`)에서 직접 추출한다. **권고 1 반영**: 변이가 닿지 못해 still_missing에 남은 "비교식 분기" 수를 엔드포인트별로 리포트에 기록해, 콘콜릭 복귀 트리거(docs/decisions/explorer-engines.md)의 실증 데이터로 삼는다.

**Tech Stack:** Java 17+, Spoon (정적 분석), Jackson `ObjectNode` (변이 적용), JUnit5 + AssertJ, Gradle.

---

## 베이스라인 주의

작업 디렉터리에 **미커밋 변경(WIP)**이 있다. `git status`에 `M`으로 표시된 4개 파일(app-aggregate 커버리지 `coveredAppBranches/totalAppBranches`, #4)이 이 계획의 베이스라인이다. 모든 라인 번호/코드는 **현재 디스크 상태** 기준이다. 첫 커밋 전 `git stash`/`reset` 하지 말 것.

확인된 사실 (계획 전제):
- `BodyShape` record는 `BodyShape(String javaType, List<BodyField> fields)` — 첫 컴포넌트 이름이 `javaType`이지만 `BodyShapeExtractor`가 여기에 **DTO FQN**을 넣는다. 따라서 DTO FQN은 `shape.javaType()`.
- `BodyShapeExtractor.findNested(CtType, String)`는 package-private static — 같은 패키지(`io.graphrag.builder.index`)에서 재사용 가능.
- `EndpointTarget` 생성자 호출처: 러너(8-arg로 교체) + `ExplorationOrchestratorTest`(6-arg `literalCandidates` overload, 유지 필요).
- `EndpointExploration` 생성자 호출처: 러너 `report()` + `JsonRoundTripTest`(2곳뿐).
- `runner.run(...)` 호출처: `BuilderCli.java:201` 1곳.
- `InputMutatorTest`는 **없음** → 신규 생성.
- 회귀 하네스: `.work/run-suites.sh <petclinic|auth-user|diary>` 존재. order-service는 e2e 모듈.

테스트 실행 모듈 경로: `:graph-rag-builder`, `:shared-model`.

## 검증 현황 (실행 전 점검 결과)

코드 대조로 확인 완료:
- Spoon **11.1.0**. 애너테이션 API(`getAnnotations()`, `getAnnotationType().getSimpleName()`, `getValues().get(key)`)는 `EndpointIndexer`/`WsEndpointIndexer`에서 이미 사용 중인 검증된 패턴. → 계획 코드를 `getValues().get(key)`로 정정함.
- `CtBinaryOperator`/`CtInvocation`/`CtLiteral`/`CtVariableRead` 등은 Spoon 표준 API.
- `BuilderE2eTest`는 `run()`/`EndpointTarget`/`EndpointExploration`/`ExplorationReport` 생성자를 직접 호출하지 않음 → 시그니처 변경 ripple 없음(확인).
- `BuilderCli`는 `java.util.Map`/`List` 이미 import. 추가 import는 `ValidationConstraintExtractor` 1개뿐.
- `EndpointTarget` 6-arg overload는 `ExplorationOrchestratorTest`만 사용 → 유지하면 회귀 없음.

**잔여 리스크 1개 (inspection으로 보증 불가, TDD로 조기 노출):** Spoon 11.1.0이 record component의 검증 애너테이션을 `component.getAnnotations()`에 노출하는지 vs backing field에 노출하는지는 버전·`@Target` 의존이라 코드 읽기로 단정 불가. 이 코드베이스엔 record component **애너테이션**을 읽는 선례가 없음(`extractBodyShape`는 이름·타입만 읽음). → **완화**: `ValidationConstraintExtractor`가 component와 backing field 애너테이션을 **합쳐서** 읽도록 설계(`mergedAnnotations`). 어느 노드에 붙든 잡힌다. 그래도 둘 다 비면 Task 1 Step 5에서 즉시 실패 → **가장 싼 지점(첫 태스크)에서 노출**되며 배선 전이라 손실이 없다. 만약 실패하면 fallback: `dto.getElements(new TypeFilter<>(CtAnnotation.class))`로 타입 전체 애너테이션을 훑어 부모 element 이름으로 필드 귀속.

→ **결론: "무조건 문제없음"은 보증 불가(정직하게).** 위 1개를 제외한 모든 컴파일·타입·ripple 리스크는 코드 대조로 제거했고, 그 1개는 설계로 완화 + TDD로 조기·저비용 노출되도록 배치했다.

---

## File Structure

**생성:**
- `graph-rag-builder/src/main/java/io/graphrag/builder/index/ValidationConstraintExtractor.java` — Bean Validation 제약 추출 + `FieldConstraint`/`Kind` 정의.
- `graph-rag-builder/src/main/java/io/graphrag/builder/explore/ConditionBoundarySolver.java` — `Comparison` 리스트 → 경계값 맵.
- `graph-rag-builder/src/test/java/io/graphrag/builder/index/ValidationConstraintExtractorTest.java`
- `graph-rag-builder/src/test/java/io/graphrag/builder/index/ConstraintExtractorComparisonsTest.java`
- `graph-rag-builder/src/test/java/io/graphrag/builder/explore/ConditionBoundarySolverTest.java`
- `graph-rag-builder/src/test/java/io/graphrag/builder/explore/InputMutatorTest.java`
- `graph-rag-builder/src/test/resources/sample-src/io/graphrag/sample/validation/ValidatedRequest.java` — 검증 애너테이션 픽스처.
- `graph-rag-builder/src/test/resources/sample-src/io/graphrag/sample/bounds/BoundsController.java` — 비교식 픽스처.

**수정:**
- `graph-rag-builder/src/main/java/io/graphrag/builder/index/ConstraintExtractor.java` — `Comparison` record + `extractComparisons(...)` (AST 기반, 권고 2).
- `graph-rag-builder/src/main/java/io/graphrag/builder/explore/InputMutator.java` — `constraintDirected(...)` + `forTarget(...)`.
- `graph-rag-builder/src/main/java/io/graphrag/builder/explore/EndpointTarget.java` — `fieldConstraints`, `conditionBounds` 필드.
- `graph-rag-builder/src/main/java/io/graphrag/builder/explore/HeuristicExplorer.java` — `forTarget` 사용.
- `graph-rag-builder/src/main/java/io/graphrag/builder/explore/CoverageGuidedFuzzer.java` — `forTarget` 사용.
- `shared-model/src/main/java/io/graphrag/model/ExplorationReport.java` — `EndpointExploration.solverRelevantMissed` (권고 1).
- `shared-model/src/test/java/io/graphrag/model/JsonRoundTripTest.java` — 신규 필드 반영.
- `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java` — `run(...)` 시그니처 확장, `EndpointTarget` 조립, `report()` 권고 1.
- `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java` — 신규 추출 배선 + total 로깅.

---

### Task 1: ValidationConstraintExtractor (소스 A)

**Files:**
- Create: `graph-rag-builder/src/test/resources/sample-src/io/graphrag/sample/validation/ValidatedRequest.java`
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/index/ValidationConstraintExtractor.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/index/ValidationConstraintExtractorTest.java`

- [ ] **Step 1: 픽스처 작성**

`graph-rag-builder/src/test/resources/sample-src/io/graphrag/sample/validation/ValidatedRequest.java`:

```java
package io.graphrag.sample.validation;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ValidatedRequest(
        @NotBlank @Size(min = 2, max = 10) String name,
        @Min(1) @Max(100) Integer quantity,
        @Positive Integer price,
        @Email String contact,
        @Pattern(regexp = "[A-Z]{3}") String code) {
}
```

- [ ] **Step 2: 실패 테스트 작성**

`graph-rag-builder/src/test/java/io/graphrag/builder/index/ValidationConstraintExtractorTest.java`:

```java
package io.graphrag.builder.index;

import io.graphrag.builder.index.ValidationConstraintExtractor.FieldConstraint;
import io.graphrag.builder.index.ValidationConstraintExtractor.Kind;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ValidationConstraintExtractorTest {

    private static final Path SAMPLE_SRC = Path.of("src/test/resources/sample-src");

    @Test
    void extract_readsJakartaConstraintsPerField() {
        Map<String, List<FieldConstraint>> result = new ValidationConstraintExtractor()
                .extract(SAMPLE_SRC, "io.graphrag.sample.validation.ValidatedRequest");

        assertThat(result.get("name")).extracting(FieldConstraint::kind)
                .containsExactly(Kind.NOT_BLANK, Kind.SIZE_MIN, Kind.SIZE_MAX);
        assertThat(result.get("name")).filteredOn(c -> c.kind() == Kind.SIZE_MIN)
                .singleElement().extracting(FieldConstraint::numArg).isEqualTo(2L);
        assertThat(result.get("name")).filteredOn(c -> c.kind() == Kind.SIZE_MAX)
                .singleElement().extracting(FieldConstraint::numArg).isEqualTo(10L);

        assertThat(result.get("quantity")).extracting(FieldConstraint::kind)
                .containsExactly(Kind.MIN, Kind.MAX);
        assertThat(result.get("quantity")).extracting(FieldConstraint::numArg)
                .containsExactly(1L, 100L);

        assertThat(result.get("price")).extracting(FieldConstraint::kind)
                .containsExactly(Kind.POSITIVE);
        assertThat(result.get("contact")).extracting(FieldConstraint::kind)
                .containsExactly(Kind.EMAIL);
        assertThat(result.get("code")).extracting(FieldConstraint::kind)
                .containsExactly(Kind.PATTERN);
        assertThat(result.get("code")).singleElement()
                .extracting(FieldConstraint::strArg).isEqualTo("[A-Z]{3}");
    }

    @Test
    void extract_unknownType_returnsEmpty() {
        assertThat(new ValidationConstraintExtractor()
                .extract(SAMPLE_SRC, "io.graphrag.sample.validation.Nope")).isEmpty();
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests "io.graphrag.builder.index.ValidationConstraintExtractorTest"`
Expected: COMPILE FAIL (`ValidationConstraintExtractor` 없음).

- [ ] **Step 4: 구현**

`graph-rag-builder/src/main/java/io/graphrag/builder/index/ValidationConstraintExtractor.java`:

```java
package io.graphrag.builder.index;

import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtRecord;
import spoon.reflect.declaration.CtRecordComponent;
import spoon.reflect.declaration.CtType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code @RequestBody} DTO의 jakarta.validation 제약을 정적으로 읽어 위반/경계 입력
 * 생성의 근거로 삼는다. 콘콜릭/SMT 없이 선언적 제약을 그대로 환류
 * (docs/decisions/explorer-engines.md). 제약값이 애너테이션에 리터럴로 있으므로 솔버 불필요.
 */
public class ValidationConstraintExtractor {

    public enum Kind {
        NOT_NULL, NOT_BLANK, SIZE_MIN, SIZE_MAX, MIN, MAX,
        POSITIVE, POSITIVE_OR_ZERO, NEGATIVE, NEGATIVE_OR_ZERO, EMAIL, PATTERN
    }

    /** numArg: MIN/MAX/SIZE_* 한정. strArg: PATTERN 한정(regexp). 그 외 0/null. */
    public record FieldConstraint(String field, Kind kind, long numArg, String strArg) {
    }

    public Map<String, List<FieldConstraint>> extract(Path srcDir, String dtoQualifiedName) {
        Launcher launcher = new Launcher();
        launcher.addInputResource(srcDir.toString());
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.getEnvironment().setComplianceLevel(17);
        CtModel model = launcher.buildModel();

        Map<String, List<FieldConstraint>> result = new LinkedHashMap<>();
        for (CtType<?> type : model.getAllTypes()) {
            CtType<?> dto = BodyShapeExtractor.findNested(type, dtoQualifiedName);
            if (dto == null) {
                continue;
            }
            if (dto instanceof CtRecord record) {
                for (CtRecordComponent comp : record.getRecordComponents()) {
                    collect(result, comp.getSimpleName(),
                            mergedAnnotations(comp, record.getField(comp.getSimpleName())));
                }
            } else {
                dto.getFields().forEach(f ->
                        collect(result, f.getSimpleName(), f.getAnnotations()));
            }
            return result;
        }
        return result;
    }

    /**
     * 검증 애너테이션이 record component 노드에 붙는지 backing field 노드에 붙는지는
     * Spoon 버전·@Target에 따라 갈린다. 양쪽을 합쳐(타입 simpleName 기준 dedupe) 어느
     * 쪽이든 읽히게 한다. backing field가 없으면(component만) component만 사용.
     */
    private static List<CtAnnotation<?>> mergedAnnotations(CtRecordComponent comp,
                                                           CtField<?> backingField) {
        LinkedHashMap<String, CtAnnotation<?>> byType = new LinkedHashMap<>();
        for (CtAnnotation<?> a : comp.getAnnotations()) {
            byType.putIfAbsent(a.getAnnotationType().getSimpleName(), a);
        }
        if (backingField != null) {
            for (CtAnnotation<?> a : backingField.getAnnotations()) {
                byType.putIfAbsent(a.getAnnotationType().getSimpleName(), a);
            }
        }
        return new ArrayList<>(byType.values());
    }

    private static void collect(Map<String, List<FieldConstraint>> result, String field,
                                List<CtAnnotation<?>> annotations) {
        List<FieldConstraint> constraints = new ArrayList<>();
        for (CtAnnotation<?> ann : annotations) {
            switch (ann.getAnnotationType().getSimpleName()) {
                case "NotNull" -> constraints.add(new FieldConstraint(field, Kind.NOT_NULL, 0, null));
                case "NotBlank", "NotEmpty" ->
                        constraints.add(new FieldConstraint(field, Kind.NOT_BLANK, 0, null));
                case "Min" -> longAttr(ann, "value").ifPresent(v ->
                        constraints.add(new FieldConstraint(field, Kind.MIN, v, null)));
                case "Max" -> longAttr(ann, "value").ifPresent(v ->
                        constraints.add(new FieldConstraint(field, Kind.MAX, v, null)));
                case "Size" -> {
                    longAttr(ann, "min").filter(m -> m > 0).ifPresent(m ->
                            constraints.add(new FieldConstraint(field, Kind.SIZE_MIN, m, null)));
                    longAttr(ann, "max").filter(m -> m < Integer.MAX_VALUE).ifPresent(m ->
                            constraints.add(new FieldConstraint(field, Kind.SIZE_MAX, m, null)));
                }
                case "Positive" -> constraints.add(new FieldConstraint(field, Kind.POSITIVE, 0, null));
                case "PositiveOrZero" ->
                        constraints.add(new FieldConstraint(field, Kind.POSITIVE_OR_ZERO, 0, null));
                case "Negative" -> constraints.add(new FieldConstraint(field, Kind.NEGATIVE, 0, null));
                case "NegativeOrZero" ->
                        constraints.add(new FieldConstraint(field, Kind.NEGATIVE_OR_ZERO, 0, null));
                case "Email" -> constraints.add(new FieldConstraint(field, Kind.EMAIL, 0, null));
                case "Pattern" -> strAttr(ann, "regexp").ifPresent(rx ->
                        constraints.add(new FieldConstraint(field, Kind.PATTERN, 0, rx)));
                default -> { }
            }
        }
        if (!constraints.isEmpty()) {
            result.put(field, constraints);
        }
    }

    // 검증된 패턴: 이 코드베이스는 annotation.getValues().get(key)로 속성을 읽는다
    // (EndpointIndexer/WsEndpointIndexer). getValue(key)가 아니라 getValues().get(key).
    private static Optional<Long> longAttr(CtAnnotation<?> ann, String key) {
        CtExpression<?> v = ann.getValues().get(key);
        if (v instanceof CtLiteral<?> lit && lit.getValue() instanceof Number n) {
            return Optional.of(n.longValue());
        }
        return Optional.empty();
    }

    private static Optional<String> strAttr(CtAnnotation<?> ann, String key) {
        CtExpression<?> v = ann.getValues().get(key);
        if (v instanceof CtLiteral<?> lit && lit.getValue() instanceof String s) {
            return Optional.of(s);
        }
        return Optional.empty();
    }
}
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew :graph-rag-builder:test --tests "io.graphrag.builder.index.ValidationConstraintExtractorTest"`
Expected: PASS (2 tests).

> NoClasspath 모드에서 미지정 애너테이션 속성(`@Size`의 누락 min/max)은 `getValue`가 null → 해당 SIZE_* 미생성이 정상이다. 만약 Spoon 버전이 애너테이션 순서를 source 순으로 보존하지 않아 `name` 단언이 깨지면, `containsExactly` → `containsExactlyInAnyOrder`로 바꾼다(determinism은 InputMutator 단에서 별도 보장).

- [ ] **Step 6: 커밋**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/index/ValidationConstraintExtractor.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/index/ValidationConstraintExtractorTest.java \
        graph-rag-builder/src/test/resources/sample-src/io/graphrag/sample/validation/ValidatedRequest.java
git commit -m "feat(builder): ValidationConstraintExtractor reads jakarta.validation constraints"
```

---

### Task 2: ConstraintExtractor.extractComparisons (소스 B, 권고 2 — AST 기반)

**Files:**
- Create: `graph-rag-builder/src/test/resources/sample-src/io/graphrag/sample/bounds/BoundsController.java`
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/index/ConstraintExtractor.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/index/ConstraintExtractorComparisonsTest.java`

- [ ] **Step 1: 비교식 픽스처 작성**

`graph-rag-builder/src/test/resources/sample-src/io/graphrag/sample/bounds/BoundsController.java`:

```java
package io.graphrag.sample.bounds;

public class BoundsController {

    public record Req(Integer amount, Integer score) {
    }

    public String handle(Req req) {
        if (req.amount() > 100) {
            return "big";
        }
        if (50 >= req.score()) {            // 리터럴 좌변 → flip되어 score <= 50
            return "low";
        }
        if (req.getAmount() == 7) {         // getter 형태 → amount로 정규화
            return "lucky";
        }
        if (req.amount() > req.score()) {   // 리터럴 없음 → 무시
            return "rel";
        }
        return "ok";
    }
}
```

- [ ] **Step 2: 실패 테스트 작성**

`graph-rag-builder/src/test/java/io/graphrag/builder/index/ConstraintExtractorComparisonsTest.java`:

```java
package io.graphrag.builder.index;

import io.graphrag.builder.index.ConstraintExtractor.Comparison;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class ConstraintExtractorComparisonsTest {

    private static final Path SAMPLE_SRC = Path.of("src/test/resources/sample-src");

    @Test
    void extractComparisons_resolvesFieldRefOpLiteral_andFlipsLiteralOnLeft() {
        List<Comparison> comparisons = new ConstraintExtractor().extractComparisons(
                SAMPLE_SRC, "io.graphrag.sample.bounds.BoundsController", "handle");

        assertThat(comparisons)
                .extracting(Comparison::fieldRef, Comparison::op, Comparison::literal)
                .containsExactlyInAnyOrder(
                        tuple("amount", ">", 100L),
                        tuple("score", "<=", 50L),
                        tuple("amount", "==", 7L));
        assertThat(comparisons).allMatch(c -> c.line() > 0);
    }

    @Test
    void extractComparisons_unknownMethod_returnsEmpty() {
        assertThat(new ConstraintExtractor().extractComparisons(
                SAMPLE_SRC, "io.graphrag.sample.bounds.BoundsController", "nope")).isEmpty();
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests "io.graphrag.builder.index.ConstraintExtractorComparisonsTest"`
Expected: COMPILE FAIL (`Comparison`/`extractComparisons` 없음).

- [ ] **Step 4: ConstraintExtractor에 import 추가**

`graph-rag-builder/src/main/java/io/graphrag/builder/index/ConstraintExtractor.java` 상단 import 블록(현재 3~9줄 `spoon.*` import들 사이)에 추가:

```java
import spoon.reflect.code.BinaryOperatorKind;
import spoon.reflect.code.CtBinaryOperator;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtVariableRead;
```

그리고 `java.util.*` import에 추가:

```java
import java.util.Comparator;
import java.util.Map;
import java.util.OptionalLong;
```

- [ ] **Step 5: Comparison record + extractComparisons 구현**

`ConstraintExtractor` 클래스 본문에서, 기존 `ConditionSpan` record(21~22줄) 바로 아래에 추가:

```java
    /** field op literal 형태의 정수 비교식. 리터럴이 좌변이면 op를 flip해 우변 정규화. */
    public record Comparison(String fieldRef, String op, long literal, int line) {
    }

    private static final Map<BinaryOperatorKind, String> REL_OPS = Map.of(
            BinaryOperatorKind.GT, ">", BinaryOperatorKind.GE, ">=",
            BinaryOperatorKind.LT, "<", BinaryOperatorKind.LE, "<=",
            BinaryOperatorKind.EQ, "==", BinaryOperatorKind.NE, "!=");

    private static final Map<String, String> FLIP = Map.of(
            ">", "<", ">=", "<=", "<", ">", "<=", ">=", "==", "==", "!=", "!=");
```

그리고 `extract(...)` 메서드(24~53줄) 바로 아래에 추가:

```java
    /**
     * handler 메서드의 비교식을 AST에서 직접 추출한다(권고 2: toString 정규식 회피).
     * field op literal / literal op field 형태만, 정수 리터럴만 1차 지원.
     */
    public List<Comparison> extractComparisons(Path srcDir, String classFqn, String methodName) {
        Launcher launcher = new Launcher();
        launcher.addInputResource(srcDir.toString());
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.getEnvironment().setComplianceLevel(17);
        CtModel model = launcher.buildModel();

        List<Comparison> comparisons = new ArrayList<>();
        for (CtType<?> type : model.getAllTypes()) {
            if (!type.getQualifiedName().replace('$', '.').equals(classFqn)) {
                continue;
            }
            for (CtMethod<?> method : type.getMethods()) {
                if (!method.getSimpleName().equals(methodName)) {
                    continue;
                }
                method.getElements(new TypeFilter<>(CtBinaryOperator.class)).forEach(op -> {
                    String opStr = REL_OPS.get(op.getKind());
                    if (opStr != null) {
                        addComparison(comparisons, op.getLeftHandOperand(),
                                op.getRightHandOperand(), opStr, op.getPosition().getLine());
                    }
                });
            }
        }
        comparisons.sort(Comparator.comparingInt(Comparison::line)
                .thenComparing(Comparison::fieldRef));
        return comparisons;
    }

    private static void addComparison(List<Comparison> out, CtExpression<?> left,
                                      CtExpression<?> right, String op, int line) {
        OptionalLong leftLit = literalLong(left);
        OptionalLong rightLit = literalLong(right);
        String leftRef = fieldRef(left);
        String rightRef = fieldRef(right);
        if (rightLit.isPresent() && leftRef != null) {
            out.add(new Comparison(leftRef, op, rightLit.getAsLong(), line));
        } else if (leftLit.isPresent() && rightRef != null) {
            out.add(new Comparison(rightRef, FLIP.get(op), leftLit.getAsLong(), line));
        }
    }

    private static OptionalLong literalLong(CtExpression<?> expr) {
        if (expr instanceof CtLiteral<?> lit && lit.getValue() instanceof Number n
                && !(lit.getValue() instanceof Double) && !(lit.getValue() instanceof Float)) {
            return OptionalLong.of(n.longValue());
        }
        return OptionalLong.empty();
    }

    private static String fieldRef(CtExpression<?> expr) {
        if (expr instanceof CtInvocation<?> inv) {
            String m = inv.getExecutable().getSimpleName();
            if (m.startsWith("get") && m.length() > 3) {
                return Character.toLowerCase(m.charAt(3)) + m.substring(4);
            }
            if (m.startsWith("is") && m.length() > 2) {
                return Character.toLowerCase(m.charAt(2)) + m.substring(3);
            }
            return m;   // record accessor: amount()
        }
        if (expr instanceof CtVariableRead<?> vr) {
            return vr.getVariable().getSimpleName();
        }
        if (expr instanceof CtFieldRead<?> fr) {
            return fr.getVariable().getSimpleName();
        }
        return null;
    }
```

- [ ] **Step 6: 통과 확인**

Run: `./gradlew :graph-rag-builder:test --tests "io.graphrag.builder.index.ConstraintExtractorComparisonsTest" --tests "io.graphrag.builder.index.ConstraintExtractorTest"`
Expected: PASS (기존 `ConstraintExtractorTest` 2개 + 신규 2개). 기존 `extract(...)` 미변경이므로 회귀 없음.

- [ ] **Step 7: 커밋**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/index/ConstraintExtractor.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/index/ConstraintExtractorComparisonsTest.java \
        graph-rag-builder/src/test/resources/sample-src/io/graphrag/sample/bounds/BoundsController.java
git commit -m "feat(builder): ConstraintExtractor.extractComparisons (AST-based field op literal)"
```

---

### Task 3: ConditionBoundarySolver

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/explore/ConditionBoundarySolver.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/explore/ConditionBoundarySolverTest.java`

- [ ] **Step 1: 실패 테스트 작성**

`graph-rag-builder/src/test/java/io/graphrag/builder/explore/ConditionBoundarySolverTest.java`:

```java
package io.graphrag.builder.explore;

import io.graphrag.builder.index.ConstraintExtractor.Comparison;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionBoundarySolverTest {

    @Test
    void solve_eachLiteralBecomesLMinus1_L_LPlus1_perField() {
        List<Comparison> comparisons = List.of(
                new Comparison("amount", ">", 100, 10),
                new Comparison("score", "<=", 50, 11),
                new Comparison("amount", "==", 7, 12));

        Map<String, Set<Long>> bounds = new ConditionBoundarySolver().solve(comparisons);

        assertThat(bounds.get("amount")).containsExactly(6L, 7L, 8L, 99L, 100L, 101L);
        assertThat(bounds.get("score")).containsExactly(49L, 50L, 51L);
    }

    @Test
    void solve_empty_returnsEmpty() {
        assertThat(new ConditionBoundarySolver().solve(List.of())).isEmpty();
    }
}
```

> `containsExactly`로 정렬 순서를 단언한다(TreeSet → 오름차순). 결정성 보장의 일부.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests "io.graphrag.builder.explore.ConditionBoundarySolverTest"`
Expected: COMPILE FAIL (`ConditionBoundarySolver` 없음).

- [ ] **Step 3: 구현**

`graph-rag-builder/src/main/java/io/graphrag/builder/explore/ConditionBoundarySolver.java`:

```java
package io.graphrag.builder.explore;

import io.graphrag.builder.index.ConstraintExtractor.Comparison;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * handler 비교식의 리터럴 경계를 입력값 후보로 변환한다. 각 리터럴 L → {L-1, L, L+1}.
 * 콘콜릭/SMT 대체가 아니라, 리터럴이 소스에 그대로 있는 경우의 경계값을 결정적으로 환류.
 * 정렬 컬렉션(TreeMap/TreeSet)으로 순서 고정 (docs/04 결정성).
 */
public final class ConditionBoundarySolver {

    public Map<String, Set<Long>> solve(List<Comparison> comparisons) {
        Map<String, Set<Long>> bounds = new TreeMap<>();
        for (Comparison c : comparisons) {
            Set<Long> values = bounds.computeIfAbsent(c.fieldRef(), k -> new TreeSet<>());
            values.add(c.literal() - 1);
            values.add(c.literal());
            values.add(c.literal() + 1);
        }
        return bounds;
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :graph-rag-builder:test --tests "io.graphrag.builder.explore.ConditionBoundarySolverTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: 커밋**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/explore/ConditionBoundarySolver.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/explore/ConditionBoundarySolverTest.java
git commit -m "feat(builder): ConditionBoundarySolver maps comparison literals to {L-1,L,L+1}"
```

---

### Task 4: InputMutator.constraintDirected + forTarget

> `forTarget`은 `EndpointTarget.fieldConstraints()`/`conditionBounds()`를 참조한다. 이 접근자는 Task 5에서 추가하므로, 본 태스크에서는 `constraintDirected`만 TDD로 구현하고 `forTarget`은 Task 5 직후 추가한다. (분리하면 각 단계가 컴파일된다.)

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/explore/InputMutator.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/explore/InputMutatorTest.java`

- [ ] **Step 1: 실패 테스트 작성**

`graph-rag-builder/src/test/java/io/graphrag/builder/explore/InputMutatorTest.java`:

```java
package io.graphrag.builder.explore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.ValidationConstraintExtractor.FieldConstraint;
import io.graphrag.builder.index.ValidationConstraintExtractor.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

class InputMutatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<BodyShape.BodyField> FIELDS = List.of(
            new BodyShape.BodyField("quantity", "java.lang.Integer"),
            new BodyShape.BodyField("name", "java.lang.String"),
            new BodyShape.BodyField("contact", "java.lang.String"));

    private static final Map<String, List<FieldConstraint>> CONSTRAINTS = Map.of(
            "quantity", List.of(
                    new FieldConstraint("quantity", Kind.MIN, 1, null),
                    new FieldConstraint("quantity", Kind.MAX, 100, null)),
            "name", List.of(
                    new FieldConstraint("name", Kind.SIZE_MIN, 2, null),
                    new FieldConstraint("name", Kind.SIZE_MAX, 10, null)),
            "contact", List.of(new FieldConstraint("contact", Kind.EMAIL, 0, null)));

    @Test
    void constraintDirected_producesViolationAndEdgeAndBoundMutations() {
        Map<String, Set<Long>> bounds = Map.of("quantity", new TreeSet<>(Set.of(0L, 5L)));

        List<InputMutator.Mutation> ms =
                InputMutator.constraintDirected(FIELDS, CONSTRAINTS, bounds);
        List<String> names = ms.stream().map(InputMutator.Mutation::name).toList();

        assertThat(names).contains(
                "min-violate-quantity", "min-edge-quantity",
                "max-violate-quantity", "max-edge-quantity",
                "size-min-violate-name", "size-min-edge-name",
                "size-max-violate-name", "size-max-edge-name",
                "email-violate-contact",
                "bound-quantity-0", "bound-quantity-5");
    }

    @Test
    void constraintDirected_appliesCorrectValues() {
        List<InputMutator.Mutation> ms =
                InputMutator.constraintDirected(FIELDS, CONSTRAINTS, Map.of());

        assertThat(applied(ms, "min-violate-quantity").get("quantity").asLong()).isEqualTo(0);
        assertThat(applied(ms, "max-violate-quantity").get("quantity").asLong()).isEqualTo(101);
        assertThat(applied(ms, "size-min-violate-name").get("name").asText()).isEqualTo("x");
        assertThat(applied(ms, "size-max-violate-name").get("name").asText())
                .isEqualTo("xxxxxxxxxxx");
        assertThat(applied(ms, "email-violate-contact").get("contact").asText())
                .isEqualTo("not-an-email");
    }

    @Test
    void constraintDirected_isDeterministic() {
        List<String> a = InputMutator.constraintDirected(FIELDS, CONSTRAINTS, Map.of())
                .stream().map(InputMutator.Mutation::name).toList();
        List<String> b = InputMutator.constraintDirected(FIELDS, CONSTRAINTS, Map.of())
                .stream().map(InputMutator.Mutation::name).toList();
        assertThat(a).isEqualTo(b);
    }

    private ObjectNode applied(List<InputMutator.Mutation> ms, String name) {
        InputMutator.Mutation m = ms.stream().filter(x -> x.name().equals(name))
                .findFirst().orElseThrow();
        return m.apply().apply(MAPPER.createObjectNode());
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests "io.graphrag.builder.explore.InputMutatorTest"`
Expected: COMPILE FAIL (`constraintDirected` 없음).

- [ ] **Step 3: InputMutator에 import 추가**

`graph-rag-builder/src/main/java/io/graphrag/builder/explore/InputMutator.java` import 블록(현재 3~10줄)에 추가:

```java
import io.graphrag.builder.index.ValidationConstraintExtractor;
import java.util.LinkedHashMap;
import java.util.Map;
```

- [ ] **Step 4: constraintDirected 구현**

`InputMutator` 클래스에서 `copy(...)`(63~65줄) 위에 추가:

```java
    /**
     * Bean Validation 제약 + handler 비교식 경계를 위반/경계 변이로 환류 (결정적).
     * 필드 선언 순서 → 제약 종류 고정 순서. 값 적용은 generic firstOrder와 별개이며,
     * 같은 (field,value)로 수렴하면 explorer의 markTried가 예산 낭비를 차단한다.
     */
    public static List<Mutation> constraintDirected(
            List<BodyShape.BodyField> fields,
            Map<String, List<ValidationConstraintExtractor.FieldConstraint>> fieldConstraints,
            Map<String, Set<Long>> conditionBounds) {
        List<Mutation> mutations = new ArrayList<>();
        for (BodyShape.BodyField field : fields) {
            String name = field.name();
            boolean numeric = NUMERIC_TYPES.contains(field.javaType());
            boolean string = field.javaType().equals("java.lang.String");
            for (ValidationConstraintExtractor.FieldConstraint c :
                    fieldConstraints.getOrDefault(name, List.of())) {
                switch (c.kind()) {
                    case NOT_NULL, NOT_BLANK, PATTERN -> {
                        // null/빈문자는 generic firstOrder가 덮고, @Pattern 값 생성은 보류(YAGNI).
                    }
                    case SIZE_MIN -> {
                        if (string && c.numArg() > 0) {
                            int m = (int) c.numArg();
                            putStr(mutations, "size-min-violate-" + name, name, "x".repeat(m - 1));
                            putStr(mutations, "size-min-edge-" + name, name, "x".repeat(m));
                        }
                    }
                    case SIZE_MAX -> {
                        if (string) {
                            int m = (int) c.numArg();
                            putStr(mutations, "size-max-violate-" + name, name, "x".repeat(m + 1));
                            putStr(mutations, "size-max-edge-" + name, name, "x".repeat(m));
                        }
                    }
                    case MIN -> {
                        if (numeric) {
                            putLong(mutations, "min-violate-" + name, name, c.numArg() - 1);
                            putLong(mutations, "min-edge-" + name, name, c.numArg());
                        }
                    }
                    case MAX -> {
                        if (numeric) {
                            putLong(mutations, "max-violate-" + name, name, c.numArg() + 1);
                            putLong(mutations, "max-edge-" + name, name, c.numArg());
                        }
                    }
                    case POSITIVE -> {
                        if (numeric) {
                            putLong(mutations, "pos-violate-zero-" + name, name, 0);
                            putLong(mutations, "pos-violate-neg-" + name, name, -1);
                        }
                    }
                    case POSITIVE_OR_ZERO -> {
                        if (numeric) {
                            putLong(mutations, "posz-violate-" + name, name, -1);
                        }
                    }
                    case NEGATIVE -> {
                        if (numeric) {
                            putLong(mutations, "neg-violate-zero-" + name, name, 0);
                            putLong(mutations, "neg-violate-pos-" + name, name, 1);
                        }
                    }
                    case NEGATIVE_OR_ZERO -> {
                        if (numeric) {
                            putLong(mutations, "negz-violate-" + name, name, 1);
                        }
                    }
                    case EMAIL -> {
                        if (string) {
                            putStr(mutations, "email-violate-" + name, name, "not-an-email");
                        }
                    }
                }
            }
            if (numeric) {
                for (Long v : conditionBounds.getOrDefault(name, Set.of())) {
                    putLong(mutations, "bound-" + name + "-" + v, name, v);
                }
            }
        }
        return dedupeByName(mutations);
    }

    private static void putStr(List<Mutation> out, String mName, String field, String value) {
        out.add(new Mutation(mName, body -> body.put(field, value)));
    }

    private static void putLong(List<Mutation> out, String mName, String field, long value) {
        out.add(new Mutation(mName, body -> body.put(field, value)));
    }

    private static List<Mutation> dedupeByName(List<Mutation> mutations) {
        LinkedHashMap<String, Mutation> byName = new LinkedHashMap<>();
        for (Mutation m : mutations) {
            byName.putIfAbsent(m.name(), m);
        }
        return new ArrayList<>(byName.values());
    }
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew :graph-rag-builder:test --tests "io.graphrag.builder.explore.InputMutatorTest"`
Expected: PASS (3 tests).

- [ ] **Step 6: 커밋**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/explore/InputMutator.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/explore/InputMutatorTest.java
git commit -m "feat(builder): InputMutator.constraintDirected (validation + boundary mutations)"
```

---

### Task 5: EndpointTarget 신규 필드 + InputMutator.forTarget

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/explore/EndpointTarget.java`
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/explore/InputMutator.java`

- [ ] **Step 1: EndpointTarget 확장**

`graph-rag-builder/src/main/java/io/graphrag/builder/explore/EndpointTarget.java` 전체를 교체:

```java
package io.graphrag.builder.explore;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.ValidationConstraintExtractor.FieldConstraint;
import io.graphrag.model.Endpoint;
import io.graphrag.model.TableSchema;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 탐색 대상 + 실행 핸들. docs/05 SPI의 EndpointTarget에 invoker를 포함시킨 형태. */
public record EndpointTarget(
        Endpoint endpoint,
        ObjectNode baseInput,
        List<BodyShape.BodyField> mutableFields,
        List<TableSchema> tables,
        EndpointInvoker invoker,
        List<String> literalCandidates,
        Map<String, List<FieldConstraint>> fieldConstraints,
        Map<String, Set<Long>> conditionBounds) {

    public EndpointTarget(Endpoint endpoint, ObjectNode baseInput,
                          List<BodyShape.BodyField> mutableFields,
                          List<TableSchema> tables, EndpointInvoker invoker) {
        this(endpoint, baseInput, mutableFields, tables, invoker,
                List.of(), Map.of(), Map.of());
    }

    public EndpointTarget(Endpoint endpoint, ObjectNode baseInput,
                          List<BodyShape.BodyField> mutableFields,
                          List<TableSchema> tables, EndpointInvoker invoker,
                          List<String> literalCandidates) {
        this(endpoint, baseInput, mutableFields, tables, invoker,
                literalCandidates, Map.of(), Map.of());
    }
}
```

- [ ] **Step 2: InputMutator.forTarget 추가**

`graph-rag-builder/src/main/java/io/graphrag/builder/explore/InputMutator.java`에서 `constraintDirected(...)` 바로 위에 추가:

```java
    /** generic firstOrder + 제약 지향 변이를 합쳐 이름 기준 dedupe. 두 explorer 공용. */
    public static List<Mutation> forTarget(EndpointTarget target) {
        List<Mutation> all = new ArrayList<>(
                firstOrder(target.mutableFields(), target.literalCandidates()));
        all.addAll(constraintDirected(target.mutableFields(),
                target.fieldConstraints(), target.conditionBounds()));
        return dedupeByName(all);
    }
```

- [ ] **Step 3: 컴파일 확인**

Run: `./gradlew :graph-rag-builder:compileJava :graph-rag-builder:compileTestJava`
Expected: BUILD SUCCESSFUL. (`ExplorationOrchestratorTest`의 6-arg `EndpointTarget` 호출은 유지된 overload로 컴파일됨.)

- [ ] **Step 4: 기존 explore 테스트 회귀 확인**

Run: `./gradlew :graph-rag-builder:test --tests "io.graphrag.builder.explore.*"`
Expected: PASS (기존 `ExplorationOrchestratorTest` + Task 3/4 신규).

- [ ] **Step 5: 커밋**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/explore/EndpointTarget.java \
        graph-rag-builder/src/main/java/io/graphrag/builder/explore/InputMutator.java
git commit -m "feat(builder): EndpointTarget carries fieldConstraints/conditionBounds; InputMutator.forTarget"
```

---

### Task 6: explorer 엔진을 forTarget로 전환

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/explore/HeuristicExplorer.java:23`
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/explore/CoverageGuidedFuzzer.java:35-36`

- [ ] **Step 1: HeuristicExplorer 교체**

`HeuristicExplorer.java` 23줄:

```java
        for (InputMutator.Mutation mutation : InputMutator.firstOrder(target.mutableFields(), target.literalCandidates())) {
```

을 다음으로 교체:

```java
        for (InputMutator.Mutation mutation : InputMutator.forTarget(target)) {
```

- [ ] **Step 2: CoverageGuidedFuzzer 교체**

`CoverageGuidedFuzzer.java` 35~36줄:

```java
        List<InputMutator.Mutation> mutations =
                InputMutator.firstOrder(target.mutableFields(), target.literalCandidates());
```

을 다음으로 교체:

```java
        List<InputMutator.Mutation> mutations = InputMutator.forTarget(target);
```

- [ ] **Step 3: 회귀 확인**

Run: `./gradlew :graph-rag-builder:test --tests "io.graphrag.builder.explore.*"`
Expected: PASS. `ExplorationOrchestratorTest`는 빈 fieldConstraints/conditionBounds(6-arg target)이므로 `forTarget` == `firstOrder`와 동일 동작 → 기존 path 발견 단언 유지.

- [ ] **Step 4: 커밋**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/explore/HeuristicExplorer.java \
        graph-rag-builder/src/main/java/io/graphrag/builder/explore/CoverageGuidedFuzzer.java
git commit -m "refactor(builder): explorers use InputMutator.forTarget (firstOrder + constraintDirected)"
```

---

### Task 7: ExplorationReport.solverRelevantMissed (권고 1, 모델 + 직렬화)

**Files:**
- Modify: `shared-model/src/main/java/io/graphrag/model/ExplorationReport.java:19-25`
- Modify: `shared-model/src/test/java/io/graphrag/model/JsonRoundTripTest.java:106`

- [ ] **Step 1: 실패 테스트 갱신**

`shared-model/src/test/java/io/graphrag/model/JsonRoundTripTest.java`의 현재(이미 WIP 적용됨) 105~108줄:

```java
        ExplorationReport report = new ExplorationReport(List.of(
                new ExplorationReport.EndpointExploration(
                        "post-api-orders", 10, 7,
                        List.of(new BranchRef("C", "m", 12, 0)),
                        java.util.Map.of("heuristic", 2, "fuzzer", 1))), 24, 58);
```

에서 `EndpointExploration` 생성자에 `solverRelevantMissed=3`을 추가 (pathsByEngine 인자 뒤):

```java
        ExplorationReport report = new ExplorationReport(List.of(
                new ExplorationReport.EndpointExploration(
                        "post-api-orders", 10, 7,
                        List.of(new BranchRef("C", "m", 12, 0)),
                        java.util.Map.of("heuristic", 2, "fuzzer", 1), 3)), 24, 58);
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :shared-model:test --tests "io.graphrag.model.JsonRoundTripTest"`
Expected: COMPILE FAIL (`EndpointExploration`이 6번째 인자를 안 받음).

- [ ] **Step 3: 모델에 필드 추가**

`shared-model/src/main/java/io/graphrag/model/ExplorationReport.java`의 `EndpointExploration` record(19~25줄)를 교체:

```java
    /**
     * solverRelevantMissed: 미커버 분기 중 handler 비교식(field op literal) 라인과
     * 겹치는 개수. 콘콜릭 복귀 트리거의 실증 데이터 (docs/decisions/explorer-engines.md).
     */
    public record EndpointExploration(
            String endpointId,
            int totalBranches,
            int coveredBranches,
            List<BranchRef> missedBranches,
            Map<String, Integer> pathsByEngine,
            int solverRelevantMissed) {
    }
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :shared-model:test`
Expected: PASS (전체 shared-model 테스트).

- [ ] **Step 5: 커밋**

```bash
git add shared-model/src/main/java/io/graphrag/model/ExplorationReport.java \
        shared-model/src/test/java/io/graphrag/model/JsonRoundTripTest.java
git commit -m "feat(model): EndpointExploration.solverRelevantMissed (concolic-return trigger metric)"
```

---

### Task 8: 러너 + BuilderCli 배선 (한 단위) — run() 확장, EndpointTarget 조립, report() 권고 1, total 로깅

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java`
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java`

> 러너 시그니처 변경(`run`/`report`)은 BuilderCli 호출부를 맞추기 전까지 컴파일이 깨진다. 따라서 러너 수정(Step 1~5)과 BuilderCli 수정(Step 6~9)을 **한 태스크로 묶어** 마지막에 한 번 빌드·커밋한다 — 중간 깨진 상태를 노출하지 않는다.

- [ ] **Step 1: import 추가**

`EndpointExplorationRunner.java` import 블록에 추가:

```java
import io.graphrag.builder.explore.ConditionBoundarySolver;
import io.graphrag.builder.index.ValidationConstraintExtractor.FieldConstraint;
import java.util.Map;
import java.util.stream.Collectors;
```

(`io.graphrag.builder.index.ConstraintExtractor`는 이미 import됨.)

- [ ] **Step 2: run() 시그니처 확장 (104~105줄)**

```java
    public EndpointResult run(Endpoint endpoint, BodyShape shape, List<TableSchema> tables,
                              List<ConstraintExtractor.ConditionSpan> conditions) throws Exception {
```

을 다음으로 교체:

```java
    public EndpointResult run(Endpoint endpoint, BodyShape shape, List<TableSchema> tables,
                              List<ConstraintExtractor.ConditionSpan> conditions,
                              List<ConstraintExtractor.Comparison> comparisons,
                              Map<String, List<FieldConstraint>> fieldConstraints) throws Exception {
```

- [ ] **Step 3: conditionBounds 산출 + EndpointTarget 조립 (137~142줄)**

현재:

```java
        ExplorationOrchestrator orchestrator = new ExplorationOrchestrator(
                List.of(new HeuristicExplorer(), new CoverageGuidedFuzzer(FUZZER_SATURATION)),
                budgetRequests);
        ExplorationOutcome outcome = orchestrator.explore(
                new EndpointTarget(endpoint, baseInput, mutableFields, tables,
                        httpInvoker(endpoint), literalCandidates));
```

을 다음으로 교체:

```java
        Map<String, Set<Long>> conditionBounds =
                new ConditionBoundarySolver().solve(comparisons);
        ExplorationOrchestrator orchestrator = new ExplorationOrchestrator(
                List.of(new HeuristicExplorer(), new CoverageGuidedFuzzer(FUZZER_SATURATION)),
                budgetRequests);
        ExplorationOutcome outcome = orchestrator.explore(
                new EndpointTarget(endpoint, baseInput, mutableFields, tables,
                        httpInvoker(endpoint), literalCandidates,
                        fieldConstraints, conditionBounds));
```

> `Set`은 이미 import됨(파일 상단 `java.util.Set`).

- [ ] **Step 4: report() 호출에 comparisons 전달 (192~193줄)**

```java
        return new EndpointResult(paths, allSql, allHttpCalls, requiredSeeds,
                report(endpoint, outcome), outcome.coveredBranches());
```

을:

```java
        return new EndpointResult(paths, allSql, allHttpCalls, requiredSeeds,
                report(endpoint, outcome, comparisons), outcome.coveredBranches());
```

- [ ] **Step 5: report() 시그니처 + solverRelevantMissed 산출 (417~433줄)**

현재 `report` 메서드를 다음으로 교체:

```java
    private ExplorationReport.EndpointExploration report(Endpoint endpoint,
                                                         ExplorationOutcome outcome,
                                                         List<ConstraintExtractor.Comparison> comparisons) {
        // 리포트 범위는 handler 메서드의 분기 (형제 메서드 분기 희석 방지).
        // SUT 전체 도달 분기는 BuilderCli가 app 집계로 별도 산출한다.
        BranchCoverage all = analyzer.analyze(new org.jacoco.core.data.ExecutionDataStore());
        List<BranchRef> handlerAll = all.missed().stream()
                .filter(b -> b.classFqn().equals(endpoint.handlerClass())
                        && b.method().equals(endpoint.handlerMethod()))
                .toList();
        Set<BranchRef> covered = outcome.coveredBranches();
        List<BranchRef> missed = handlerAll.stream()
                .filter(b -> !covered.contains(b))
                .toList();
        int coveredCount = (int) handlerAll.stream().filter(covered::contains).count();
        // 권고 1: 미커버 분기 중 비교식(field op literal) 라인과 겹치는 것 = 솔버가 필요할 잔여.
        Set<Integer> comparisonLines = comparisons.stream()
                .map(ConstraintExtractor.Comparison::line).collect(Collectors.toSet());
        int solverRelevantMissed = (int) missed.stream()
                .filter(b -> comparisonLines.contains(b.line())).count();
        return new ExplorationReport.EndpointExploration(
                endpoint.id(), handlerAll.size(), coveredCount, missed,
                outcome.pathsByEngine(), solverRelevantMissed);
    }
```

> Step 1~5는 러너만 수정한다. 여기서 빌드하지 말고 곧장 Step 6(BuilderCli)으로 진행 — 컴파일 검증은 Step 10에서 한 번에.

- [ ] **Step 6: BuilderCli import 추가**

`BuilderCli.java` import 블록에 추가:

```java
import io.graphrag.builder.index.ValidationConstraintExtractor;
```

(`List`, `Map`, `ExplorationReport`는 이미 import됨 — 확인 완료.)

- [ ] **Step 7: 엔드포인트별 추출 추가 (191~193줄 뒤)**

현재:

```java
                    var conditions = constraintExtractor.extract(
                            config.sutSrc(), endpoint.handlerClass(), endpoint.handlerMethod());
                    var literals = literalExtractor.extract(config.sutSrc(), endpoint.handlerClass());
```

바로 아래에 추가:

```java
                    var comparisons = constraintExtractor.extractComparisons(
                            config.sutSrc(), endpoint.handlerClass(), endpoint.handlerMethod());
                    Map<String, List<ValidationConstraintExtractor.FieldConstraint>> fieldConstraints =
                            shape == null ? Map.of()
                                    : new ValidationConstraintExtractor()
                                            .extract(config.sutSrc(), shape.javaType());
```

> `shape.javaType()`이 DTO FQN이다(BodyShape 첫 컴포넌트가 FQN 보유). GET(shape==null)이면 빈 맵.

- [ ] **Step 8: runner.run 호출 갱신 (201줄)**

```java
                    EndpointExplorationRunner.EndpointResult result =
                            runner.run(endpoint, shape, tables, conditions);
```

을:

```java
                    EndpointExplorationRunner.EndpointResult result =
                            runner.run(endpoint, shape, tables, conditions,
                                    comparisons, fieldConstraints);
```

- [ ] **Step 9: total 로깅 (238줄 exploration-report.json 쓰기 직전)**

현재 238~241줄:

```java
        Files.writeString(config.out().resolve("exploration-report.json"),
                Json.mapper().writerWithDefaultPrettyPrinter()
                        .writeValueAsString(new ExplorationReport(
                                reportEntries, coveredAppBranches.size(), totalAppBranches)));
```

바로 위에 추가:

```java
        int solverRelevantMissedTotal = reportEntries.stream()
                .mapToInt(ExplorationReport.EndpointExploration::solverRelevantMissed).sum();
        log.info("solver-relevant still-missing branches (concolic-return trigger): {}",
                solverRelevantMissedTotal);
```

- [ ] **Step 10: 전체 빌드 + 단위 테스트 (러너+BuilderCli 함께)**

Run: `./gradlew :shared-model:test :graph-rag-builder:test`
Expected: BUILD SUCCESSFUL. Task 1~4의 신규 테스트 + 기존 builder 테스트(`ConstraintExtractorTest`, `ExplorationOrchestratorTest`, `BuilderE2eTest` 등) 모두 PASS.

> `BuilderE2eTest`가 `runner.run`/`EndpointTarget`/`EndpointExploration`을 직접 호출하지 않음(확인 완료, 호출부는 BuilderCli 경유). 만약 컴파일 에러가 나면 해당 호출부도 동일 패턴으로 갱신한다.

- [ ] **Step 11: 커밋**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java \
        graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java
git commit -m "feat(builder): wire constraint-directed inputs + record solver-relevant residual"
```

---

### Task 9: e2e + 격리 하네스 회귀 + 커버리지 델타 검증

**Files:** (코드 변경 없음 — 검증 전용)

- [ ] **Step 1: order-service e2e (parallel)**

Run: `./gradlew :e2e:test`
Expected: 기존 GREEN 유지 (생성 테스트 전부 PASS).

> e2e가 builder를 먼저 돌려 테스트를 생성하는 구조가 아니라면(사전 생성물 사용), order-service 회귀는 `.work` 경로 또는 기존 e2e 절차를 따른다. 변경은 입력 변이를 **추가**만 하므로 기존 happy/2xx path는 보존된다.

- [ ] **Step 2: 외부 SUT 3종 격리 회귀**

```bash
.work/run-suites.sh petclinic
.work/run-suites.sh auth-user
.work/run-suites.sh diary
```

Expected: 각 스위트 GREEN (스펙 성공 기준 1: 4개 SUT 49/49 유지).

- [ ] **Step 3: 커버리지 증가 확인 (성공 기준 2)**

각 SUT의 `exploration-report.json`에서 `coveredAppBranches`를 변경 전(베이스라인) 대비 비교. 최소 한 SUT(특히 petclinic — Bean Validation `@NotBlank`/`@Size` 다수)에서 **증가** 확인.

```bash
# 예: 생성 산출물 위치의 리포트에서 확인
python3 -c "import json,sys; r=json.load(open(sys.argv[1])); \
print('coveredAppBranches', r['coveredAppBranches'], '/', r['totalAppBranches']); \
print('solverRelevantMissedTotal', sum(e['solverRelevantMissed'] for e in r['endpoints']))" \
  <petclinic exploration-report.json 경로>
```

Expected: `coveredAppBranches` 증가. `solverRelevantMissedTotal`은 권고 1 지표 — 0이 아니면 콘콜릭 복귀 후보 분기가 남아있다는 실증(스펙 성공 기준 2의 정성 근거).

- [ ] **Step 4: 결과 기록**

회귀 결과(각 SUT pass 수, coveredAppBranches 전/후, solverRelevantMissedTotal)를 PR 본문 또는 `.remember`에 기록. 커버리지가 한 SUT도 증가하지 않으면 — diary처럼 핸들러 우회로 4xx만 늘 수 있음(스펙 위험 4) — 어떤 SUT가 닿았는지/못 닿았는지 명시한다.

---

## Self-Review

**1. Spec coverage:**
- 소스 A (Bean Validation) → Task 1. ✅ (@Pattern은 인식만, 값 생성 보류 — spec 비목표 준수)
- 소스 B (handler 비교식) → Task 2 (AST, 권고 2) + Task 3 (경계값). ✅
- `InputMutator` 확장 (`constraintDirected`, dedupe, firstOrder와 합류) → Task 4 + 5(`forTarget`). ✅
- 배선 (EndpointTarget 필드, BuilderCli, runner) → Task 5/8. ✅ BodyShape 미변경(WS 무영향) 준수.
- read-path(GET) body 제약 없음 / conditionBounds는 PATH·QUERY 숫자 파라미터에도 적용 → Task 8에서 `mutableFields`(GET은 path/query 포함)에 자동 적용, `fieldConstraints`는 빈 맵. ✅
- 결정성 (선언/리터럴 순서, 정렬, Random 금지) → TreeMap/TreeSet/LinkedHashMap + InputMutatorTest determinism. ✅
- 테스트 4종 (Validation/ConditionBoundary/InputMutator/회귀) → Task 1/2/3/4/9. ✅
- 권고 1 (still_missing 비교식 분기 기록) → Task 7 + 8(report+로깅) + 9(검증). ✅
- 권고 2 (AST 기반) → Task 2 (`CtBinaryOperator` 직접, 정규식 없음). ✅
- 성공 기준 1/2/3 → Task 9 / Task 9 / Task 1~4 단위 테스트. ✅

**2. Placeholder scan:** 모든 코드 단계에 완전한 코드/명령/기대 출력 포함. TODO/TBD 없음.

**3. Type consistency:**
- `ValidationConstraintExtractor.FieldConstraint(String,Kind,long,String)` — Task 1 정의, Task 4/5/8에서 동일 시그니처 사용. ✅
- `ConstraintExtractor.Comparison(String fieldRef,String op,long literal,int line)` — Task 2 정의, Task 3(`fieldRef`,`literal`)/Task 8(`line`)에서 동일. ✅
- `EndpointTarget` 8-arg canonical + 5/6-arg overload — Task 5 정의, 러너 8-arg(Task 8)/`ExplorationOrchestratorTest` 6-arg(유지). ✅
- `InputMutator.forTarget(EndpointTarget)` / `constraintDirected(List,Map,Map)` — Task 4/5 정의, Task 6 호출. ✅
- `EndpointExploration(...,int solverRelevantMissed)` — Task 7 정의, Task 8 생성 / JsonRoundTripTest 사용. ✅
- `run(...,List<Comparison>,Map<String,List<FieldConstraint>>)` — Task 8 정의·호출(러너+BuilderCli 한 단위). ✅
- `shape.javaType()` = DTO FQN (BodyShape 첫 컴포넌트) — Task 8. ✅

---

## 실행 순서 주의 (의존성)

```
Task1 ─┐
Task2 ─┼─→ Task3 (Comparison 필요) ─┐
       └─→ Task4 (FieldConstraint 필요)─┤
                                        ├─→ Task5 ─→ Task6
Task7 (독립)                            │
                                        └─→ Task8 (러너+BuilderCli 한 단위) ─→ Task9
```

- Task 1·2·7은 상호 독립 → 병렬 가능.
- Task 8은 러너+BuilderCli를 **한 단위**로(중간 컴파일 깨짐 미노출) Step 1~11에서 처리 후 1회 빌드·커밋.
- Task 9(회귀)는 모든 코드 태스크 완료 후.

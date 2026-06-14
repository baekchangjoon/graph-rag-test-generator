# Stage 1+2 — conjunction 추출 + joint 입력 합성 Implementation Plan

> REQUIRED SUB-SKILL: superpowers:executing-plans. 체크박스 단위 추적.

**Goal:** 메서드 내 `&&` conjunction을 추출(Stage 1)하고, enum-상수 변이 + joint 변이(Stage 2)로
환류해 `priceTier==VIP && loyaltyPoints<500`(petclinic L64) 같은 다필드 가드의 true-arm에 도달한다.
spec: `docs/superpowers/specs/2026-06-14-stage12-conjunction-joint-synthesis-design.md`.

**Architecture:** `ConstraintExtractor.extractConjunctions`(Spoon)가 `Conjunction(atoms)`를 산출 →
`EndpointTarget`에 `enumConstants`(Stage0 재사용) + `conjunctions` 추가 → `InputMutator.forTarget`이
`enumValues`(enum 필드 각 상수) + `joint`(원자 동시 세팅) 변이를 생성. ASM/Z3 불변.

**측정:** service 분기는 `ExplorationReport.coveredAppBranches`(whole-app)로만 보임.

---

## File Structure
- 수정: `graph-rag-builder/src/main/java/io/graphrag/builder/index/ConstraintExtractor.java`
  (신규 레코드 `Conjunction`/`Atom`, 신규 메서드 `extractConjunctions` + private helpers)
- 수정: `graph-rag-builder/src/main/java/io/graphrag/builder/explore/EndpointTarget.java` (필드 2개)
- 수정: `graph-rag-builder/src/main/java/io/graphrag/builder/explore/InputMutator.java`
  (`enumValues`, `joint`, `satisfy`, `constantsFor`, `forTarget` 갱신)
- 수정: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java`
  (run() 시그니처 + EndpointTarget 생성)
- 수정: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java` (추출 + 호출부)
- 수정(테스트): `.../index/ConstraintExtractorTest.java`(보강/생성), `.../explore/InputMutatorTest.java`(보강)
- 생성(픽스처): `.../resources/sample-src/io/graphrag/sample/guards/Guards.java`

---

### Task 1: ConstraintExtractor.extractConjunctions (+ Conjunction/Atom)

**Files:** Modify `index/ConstraintExtractor.java`, test `index/ConstraintExtractorTest.java`,
fixture `src/test/resources/sample-src/io/graphrag/sample/guards/Guards.java`

- [ ] **Step 1: 픽스처** — enum 동치 + 숫자 + 단일필드 || + 중첩 && 케이스를 한 파일에.
```java
// src/test/resources/sample-src/io/graphrag/sample/guards/Guards.java
package io.graphrag.sample.guards;

public class Guards {
    enum Tier { BASIC, VIP }
    record Req(Tier tier, int loyalty, int nights, String code) { }

    String check(Req req) {
        // 다필드 && (enum + numeric) — 추출 대상
        if (req.tier() == Tier.VIP && req.loyalty() < 500) {
            return "vip-low";
        }
        // 단일필드 || (범위) — conjunction 아님(제외)
        if (req.nights() < 1 || req.nights() > 30) {
            return "nights";
        }
        // 중첩 && 3원자(2필드) + 문자열 동치 — 평탄화 대상
        if (req.tier() == Tier.BASIC && req.code().equals("X") && req.loyalty() > 10) {
            return "combo";
        }
        return "ok";
    }
}
```

- [ ] **Step 2: 실패 테스트** — `ConstraintExtractorTest`에 추가(없으면 생성). import:
  `io.graphrag.builder.index.ConstraintExtractor.*` 또는 정규.
```java
@Test
void extractConjunctions_multiFieldAndOnly_withEnumNumericString() {
    java.util.List<ConstraintExtractor.Conjunction> cs =
            new ConstraintExtractor().extractConjunctions(
                    java.nio.file.Path.of("src/test/resources/sample-src"));
    // 단일필드 || (nights)는 제외 → check()에서 conjunction 2개
    java.util.List<ConstraintExtractor.Conjunction> inCheck = cs.stream()
            .filter(c -> c.method().equals("check")).toList();
    assertThat(inCheck).hasSize(2);

    ConstraintExtractor.Conjunction vip = inCheck.get(0);   // line 정렬 → 첫 if
    assertThat(vip.atoms()).hasSize(2);
    ConstraintExtractor.Atom a0 = vip.atoms().get(0);
    assertThat(a0.kind()).isEqualTo(ConstraintExtractor.Atom.Kind.ENUM_EQ);
    assertThat(a0.fieldRef()).isEqualTo("tier");
    assertThat(a0.value()).isEqualTo("VIP");
    ConstraintExtractor.Atom a1 = vip.atoms().get(1);
    assertThat(a1.kind()).isEqualTo(ConstraintExtractor.Atom.Kind.NUMERIC);
    assertThat(a1.fieldRef()).isEqualTo("loyalty");
    assertThat(a1.op()).isEqualTo("<");
    assertThat(a1.numLiteral()).isEqualTo(500);

    ConstraintExtractor.Conjunction combo = inCheck.get(1);  // 중첩 && 평탄화 → 3원자
    assertThat(combo.atoms()).hasSize(3);
    assertThat(combo.atoms().stream().map(ConstraintExtractor.Atom::kind))
            .containsExactlyInAnyOrder(
                    ConstraintExtractor.Atom.Kind.ENUM_EQ,
                    ConstraintExtractor.Atom.Kind.STRING_EQ,
                    ConstraintExtractor.Atom.Kind.NUMERIC);
}
```

- [ ] **Step 3: 실패 확인** — `./gradlew :graph-rag-builder:test --tests "io.graphrag.builder.index.ConstraintExtractorTest"` → COMPILE FAIL (레코드/메서드 없음).

- [ ] **Step 4: 구현** — `ConstraintExtractor.java`에 추가. import:
  `spoon.reflect.code.CtTypeAccess;` (나머지 CtIf/CtConditional/CtBinaryOperator/CtFieldRead/
  CtInvocation/CtExpression/BinaryOperatorKind/OptionalLong/Comparator는 이미 있음). `java.util.Set` 불요.
```java
public record Conjunction(String classFqn, String method, int line, List<Atom> atoms) { }

public record Atom(Kind kind, String fieldRef, String op, long numLiteral, String value) {
    public enum Kind { NUMERIC, ENUM_EQ, STRING_EQ }
}

/**
 * 메서드 내 {@code &&} 조건을 conjunction 단위로 추출(원자 동시성 보존). 전 계층 1회 빌드.
 * 조건 루트(CtIf/CtConditional의 getCondition)가 AND인 것만 대상 — getElements(CtBinaryOperator)로
 * 전역 AND를 훑으면 중첩 &&가 중복 수집되므로 쓰지 않는다. 서로 다른 fieldRef 2개+만 보존.
 */
public List<Conjunction> extractConjunctions(Path srcDir) {
    Launcher launcher = new Launcher();
    launcher.addInputResource(srcDir.toString());
    launcher.getEnvironment().setNoClasspath(true);
    launcher.getEnvironment().setCommentEnabled(false);
    launcher.getEnvironment().setComplianceLevel(17);
    CtModel model = launcher.buildModel();

    List<CtExpression<?>> conditions = new ArrayList<>();
    for (CtIf ctIf : model.getElements(new TypeFilter<>(CtIf.class))) {
        conditions.add(ctIf.getCondition());
    }
    for (CtConditional<?> tern : model.getElements(new TypeFilter<>(CtConditional.class))) {
        conditions.add(tern.getCondition());
    }

    List<Conjunction> out = new ArrayList<>();
    for (CtExpression<?> cond : conditions) {
        if (!(cond instanceof CtBinaryOperator<?> bin)
                || bin.getKind() != BinaryOperatorKind.AND) {
            continue;
        }
        List<CtExpression<?>> leaves = new ArrayList<>();
        flattenAnd(bin, leaves);
        List<Atom> atoms = new ArrayList<>();
        for (CtExpression<?> leaf : leaves) {
            Atom a = toAtom(leaf);
            if (a != null) {
                atoms.add(a);
            }
        }
        if (atoms.stream().map(Atom::fieldRef).distinct().count() < 2) {
            continue;
        }
        CtMethod<?> method = bin.getParent(CtMethod.class);
        CtType<?> type = bin.getParent(CtType.class);
        if (method == null || type == null) {
            continue;
        }
        out.add(new Conjunction(type.getQualifiedName().replace('$', '.'),
                method.getSimpleName(), bin.getPosition().getLine(), atoms));
    }
    out.sort(Comparator.comparing(Conjunction::classFqn)
            .thenComparing(Conjunction::method)
            .thenComparingInt(Conjunction::line));
    return out;
}

private static void flattenAnd(CtExpression<?> expr, List<CtExpression<?>> leaves) {
    if (expr instanceof CtBinaryOperator<?> bin && bin.getKind() == BinaryOperatorKind.AND) {
        flattenAnd(bin.getLeftHandOperand(), leaves);
        flattenAnd(bin.getRightHandOperand(), leaves);
    } else {
        leaves.add(expr);
    }
}

private static Atom toAtom(CtExpression<?> leaf) {
    if (leaf instanceof CtBinaryOperator<?> bin) {
        String op = REL_OPS.get(bin.getKind());
        if (op == null) {
            return null;
        }
        CtExpression<?> left = bin.getLeftHandOperand();
        CtExpression<?> right = bin.getRightHandOperand();
        OptionalLong leftLit = literalLong(left);
        OptionalLong rightLit = literalLong(right);
        String leftRef = fieldRef(left);
        String rightRef = fieldRef(right);
        if (rightLit.isPresent() && leftRef != null) {
            return new Atom(Atom.Kind.NUMERIC, leftRef, op, rightLit.getAsLong(), null);
        }
        if (leftLit.isPresent() && rightRef != null) {
            return new Atom(Atom.Kind.NUMERIC, rightRef, FLIP.get(op), leftLit.getAsLong(), null);
        }
        if (bin.getKind() == BinaryOperatorKind.EQ) {
            String enumConst = enumConstant(right);
            if (enumConst != null && fieldRef(left) != null) {
                return new Atom(Atom.Kind.ENUM_EQ, fieldRef(left), "==", 0, enumConst);
            }
            enumConst = enumConstant(left);
            if (enumConst != null && fieldRef(right) != null) {
                return new Atom(Atom.Kind.ENUM_EQ, fieldRef(right), "==", 0, enumConst);
            }
        }
        return null;
    }
    if (leaf instanceof CtInvocation<?> inv
            && "equals".equals(inv.getExecutable().getSimpleName())
            && inv.getArguments().size() == 1 && inv.getTarget() != null) {
        CtExpression<?> target = inv.getTarget();
        CtExpression<?> arg = inv.getArguments().get(0);
        String argLit = stringLiteral(arg);
        String targetLit = stringLiteral(target);
        if (argLit != null && fieldRef(target) != null) {
            return new Atom(Atom.Kind.STRING_EQ, fieldRef(target), "==", 0, argLit);
        }
        if (targetLit != null && fieldRef(arg) != null) {
            return new Atom(Atom.Kind.STRING_EQ, fieldRef(arg), "==", 0, targetLit);
        }
    }
    return null;
}

/** {@code Type.CONST} 정적 enum 상수 읽기면 상수 simpleName, 아니면 null. */
private static String enumConstant(CtExpression<?> expr) {
    if (expr instanceof CtFieldRead<?> fr && fr.getTarget() instanceof CtTypeAccess) {
        return fr.getVariable().getSimpleName();
    }
    return null;
}
```

- [ ] **Step 5: 통과 확인** — 위 테스트 PASS(working dir = `graph-rag-builder/`, Path는 모듈 상대경로).
  **noClasspath에서 ENUM_EQ가 안 잡히면**(테스트의 `a0.kind()==ENUM_EQ`/`value=="VIP"` 실패) 실제 AST를
  찍어 `enumConstant` 판별식을 조정한다. **구체 fallback**: `fr.getTarget() instanceof CtTypeAccess`가
  안 되면, `expr`가 `CtFieldRead`/`CtVariableRead`이고 (a) 그 simpleName이 모두 대문자/언더스코어이며
  (b) 반대 변이 `fieldRef(...)`가 추출되는 경우를 enum 상수로 간주(보수적 — false-positive는 joint의
  "모든 원자 필드가 body에 존재" 필터가 흡수). 두 경로 중 통과하는 것을 채택.
- [ ] **Step 6: 커밋** `feat(builder): extractConjunctions (&& groups: numeric/enum/string atoms)`

---

### Task 2: InputMutator enum/joint 변이 + EndpointTarget 확장

**Files:** Modify `explore/EndpointTarget.java`, `explore/InputMutator.java`, test `explore/InputMutatorTest.java`

- [ ] **Step 1: 실패 테스트** — `InputMutatorTest`에 추가(파일 없으면 생성). 기존 테스트 보존.
```java
@Test
void enumValues_emitsMutationPerConstant() {
    java.util.List<BodyShape.BodyField> fields = java.util.List.of(
            new BodyShape.BodyField("tier", "io.x.Tier"));
    java.util.Map<String, java.util.List<String>> enums =
            java.util.Map.of("io.x.Tier", java.util.List.of("BASIC", "VIP"));
    java.util.List<InputMutator.Mutation> ms = InputMutator.enumValues(fields, enums);
    com.fasterxml.jackson.databind.node.ObjectNode b =
            io.graphrag.model.Json.mapper().createObjectNode();
    java.util.Map<String, String> applied = new java.util.TreeMap<>();
    for (InputMutator.Mutation m : ms) {
        applied.put(m.name(), m.apply().apply(b.deepCopy()).get("tier").asText());
    }
    assertThat(applied).containsEntry("enum-tier-BASIC", "BASIC")
            .containsEntry("enum-tier-VIP", "VIP");
}

@Test
void joint_setsAllAtomFieldsSimultaneously() {
    java.util.List<BodyShape.BodyField> fields = java.util.List.of(
            new BodyShape.BodyField("tier", "io.x.Tier"),
            new BodyShape.BodyField("loyalty", "int"));
    ConstraintExtractor.Conjunction c = new ConstraintExtractor.Conjunction(
            "io.x.Svc", "check", 64, java.util.List.of(
                    new ConstraintExtractor.Atom(
                            ConstraintExtractor.Atom.Kind.ENUM_EQ, "tier", "==", 0, "VIP"),
                    new ConstraintExtractor.Atom(
                            ConstraintExtractor.Atom.Kind.NUMERIC, "loyalty", "<", 500, null)));
    java.util.List<InputMutator.Mutation> ms = InputMutator.joint(fields, java.util.List.of(c));
    assertThat(ms).hasSize(1);
    com.fasterxml.jackson.databind.node.ObjectNode out =
            ms.get(0).apply().apply(io.graphrag.model.Json.mapper().createObjectNode());
    assertThat(out.get("tier").asText()).isEqualTo("VIP");
    assertThat(out.get("loyalty").asInt()).isEqualTo(499);   // < 500 → L-1
    assertThat(ms.get(0).name()).contains("loyalty").contains("tier");
}

@Test
void joint_skippedWhenAnyAtomFieldAbsentFromBody() {
    java.util.List<BodyShape.BodyField> fields = java.util.List.of(
            new BodyShape.BodyField("tier", "io.x.Tier"));   // loyalty 없음
    ConstraintExtractor.Conjunction c = new ConstraintExtractor.Conjunction(
            "io.x.Svc", "check", 64, java.util.List.of(
                    new ConstraintExtractor.Atom(
                            ConstraintExtractor.Atom.Kind.ENUM_EQ, "tier", "==", 0, "VIP"),
                    new ConstraintExtractor.Atom(
                            ConstraintExtractor.Atom.Kind.NUMERIC, "loyalty", "<", 500, null)));
    assertThat(InputMutator.joint(fields, java.util.List.of(c))).isEmpty();
}
```

- [ ] **Step 2: 실패 확인** — COMPILE FAIL(메서드 없음).

- [ ] **Step 3: EndpointTarget 확장** — record에 필드 2개 추가, **보조 생성자 2개 위임도 갱신**.
```java
// import 추가
import io.graphrag.builder.index.ConstraintExtractor;
// record 헤더 끝에 2개 추가:
        Map<String, Set<Long>> conditionBounds,
        Map<String, Set<String>> stringCandidates,
        Map<String, List<String>> enumConstants,
        List<ConstraintExtractor.Conjunction> conjunctions) {

    public EndpointTarget(Endpoint endpoint, ObjectNode baseInput,
                          List<BodyShape.BodyField> mutableFields,
                          List<TableSchema> tables, EndpointInvoker invoker) {
        this(endpoint, baseInput, mutableFields, tables, invoker,
                List.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of());
    }

    public EndpointTarget(Endpoint endpoint, ObjectNode baseInput,
                          List<BodyShape.BodyField> mutableFields,
                          List<TableSchema> tables, EndpointInvoker invoker,
                          List<String> literalCandidates) {
        this(endpoint, baseInput, mutableFields, tables, invoker,
                literalCandidates, Map.of(), Map.of(), Map.of(), Map.of(), List.of());
    }
```

- [ ] **Step 4: InputMutator 구현** — import 추가 `io.graphrag.builder.index.ConstraintExtractor;`,
  `java.util.HashSet;`. `forTarget` 갱신 + 새 메서드.
```java
public static List<Mutation> forTarget(EndpointTarget target) {
    List<Mutation> all = new ArrayList<>(
            firstOrder(target.mutableFields(), target.literalCandidates()));
    all.addAll(constraintDirected(target.mutableFields(),
            target.fieldConstraints(), target.conditionBounds(), target.stringCandidates()));
    all.addAll(enumValues(target.mutableFields(), target.enumConstants()));
    all.addAll(joint(target.mutableFields(), target.conjunctions()));
    return dedupeByName(all);
}

/** enum 필드 → 선언된 각 상수 세팅 변이(VIP 등). enumConstants 키 미스 시 simple-name 폴백. */
public static List<Mutation> enumValues(List<BodyShape.BodyField> fields,
                                        Map<String, List<String>> enumConstants) {
    List<Mutation> out = new ArrayList<>();
    for (BodyShape.BodyField field : fields) {
        List<String> consts = constantsFor(field.javaType(), enumConstants);
        if (consts == null) {
            continue;
        }
        String name = field.name();
        for (String c : consts) {
            out.add(new Mutation("enum-" + name + "-" + c, body -> body.put(name, c)));
        }
    }
    return out;
}

/** conjunction의 모든 원자 필드가 body에 있으면, 원자들을 동시에 만족값으로 세팅하는 단일 변이. */
public static List<Mutation> joint(List<BodyShape.BodyField> fields,
                                   List<ConstraintExtractor.Conjunction> conjunctions) {
    HashSet<String> fieldNames = new HashSet<>();
    for (BodyShape.BodyField f : fields) {
        fieldNames.add(f.name());
    }
    List<Mutation> out = new ArrayList<>();
    for (ConstraintExtractor.Conjunction c : conjunctions) {
        if (c.atoms().isEmpty()
                || !c.atoms().stream().allMatch(a -> fieldNames.contains(a.fieldRef()))) {
            continue;
        }
        String refs = c.atoms().stream().map(ConstraintExtractor.Atom::fieldRef)
                .distinct().sorted().reduce((a, b) -> a + "_" + b).orElse("");
        String simpleClass = c.classFqn().substring(c.classFqn().lastIndexOf('.') + 1);
        String name = "joint-" + simpleClass + "-" + c.line() + "-" + refs;
        List<ConstraintExtractor.Atom> atoms = c.atoms();
        out.add(new Mutation(name, body -> {
            for (ConstraintExtractor.Atom a : atoms) {
                switch (a.kind()) {
                    case NUMERIC -> body.put(a.fieldRef(), satisfy(a.op(), a.numLiteral()));
                    case ENUM_EQ, STRING_EQ -> body.put(a.fieldRef(), a.value());
                }
            }
            return body;
        }));
    }
    return out;
}

private static long satisfy(String op, long literal) {
    return switch (op) {
        case "<" -> literal - 1;
        case ">", "!=" -> literal + 1;
        default -> literal;   // <=, >=, ==
    };
}

private static List<String> constantsFor(String javaType, Map<String, List<String>> enumConstants) {
    List<String> consts = enumConstants.get(javaType);
    if (consts != null) {
        return consts;
    }
    String simple = javaType.substring(javaType.lastIndexOf('.') + 1);
    return enumConstants.entrySet().stream()
            .filter(e -> e.getKey().substring(e.getKey().lastIndexOf('.') + 1).equals(simple))
            .map(Map.Entry::getValue).findFirst().orElse(null);
}
```
  주: 위 코드 박스에 **private 메서드 3개**(`enumValues` public, `joint` public, `satisfy`/`constantsFor`
  private)를 모두 추가한다. Java는 같은 클래스 내 메서드 선언 순서 무관(호출이 정의 위에 와도 OK).

- [ ] **Step 5: 통과 확인** — `./gradlew :graph-rag-builder:test --tests "io.graphrag.builder.explore.InputMutatorTest"` PASS.
- [ ] **Step 6: 커밋** `feat(builder): InputMutator enum-constant + joint(conjunction) mutations`

---

### Task 3: 배선 (run() → EndpointTarget → BuilderCli)

**Files:** Modify `run/EndpointExplorationRunner.java`, `cli/BuilderCli.java`

- [ ] **Step 1:** `EndpointExplorationRunner.run(...)` 시그니처에 마지막 인자 추가
  (`EndpointExplorationRunner.java:115-119`):
```java
public EndpointResult run(Endpoint endpoint, BodyShape shape, List<TableSchema> tables,
                          List<ConstraintExtractor.ConditionSpan> conditions,
                          List<ConstraintExtractor.Comparison> comparisons,
                          InputCandidates candidates,
                          Map<String, List<FieldConstraint>> fieldConstraints,
                          List<ConstraintExtractor.Conjunction> conjunctions) throws Exception {
```
- [ ] **Step 2:** `run()` 내부 EndpointTarget 생성(`EndpointExplorationRunner.java:161-163`)에 2개 추가:
```java
EndpointTarget target = new EndpointTarget(endpoint, baseInput, mutableFields, tables,
        httpInvoker(endpoint), literalCandidates,
        fieldConstraints, conditionBounds, stringCandidates, enumConstants, conjunctions);
```
  (`enumConstants`는 이미 runner 필드 — Stage 0.)
- [ ] **Step 3:** `BuilderCli`에서 1회 추출(allComparisons 근처, `BuilderCli.java:182-183` 뒤):
```java
List<io.graphrag.builder.index.ConstraintExtractor.Conjunction> allConjunctions =
        constraintExtractor.extractConjunctions(config.sutSrc());
```
  그리고 유일 호출부 `runner.run(...)`(`BuilderCli.java:221-223`)의 마지막 인자로 `allConjunctions` 추가.
- [ ] **Step 4:** 기존 EndpointTarget 생성자 사용처 확인:
  `grep -rn "new EndpointTarget(" graph-rag-builder` → canonical(run() 내부, 9→11로 갱신함) +
  보조 생성자(테스트 등, 위임 유지로 호환). 줄번호는 참고용 — **시그니처로 대상을 찾는다**.
- [ ] **Step 5: 컴파일 + 전체 단위** — `./gradlew :graph-rag-builder:compileJava` 먼저(빠른 컴파일 확인)
  → `./gradlew :shared-model:test :graph-rag-builder:test :test-generator:test` GREEN.
- [ ] **Step 6: 커밋** `feat(builder): wire conjunctions into EndpointTarget/InputMutator`

---

### Task 4: petclinic A/B + 회귀

- [ ] **Step 1:** order-service e2e: `./e2e/run-e2e.sh` → 22/22 GREEN.
- [ ] **Step 2:** petclinic 빌더 A/B(현 main = before, 이 브랜치 = after, 동일 jar). `GRB_ORACLE`
  static/both 각각 `coveredAppBranches` 비교. `post-api-reservations` 응답에
  `VIP requires at least 500 loyalty points`(422) 등장 = L64 true-arm 도달 확인.
  **도달 전제(spec §reachability)**: enum/joint 변이는 `bound-roomNumber-{100,499}`가 만든 "L49–61 통과"
  seed 위에서 L64에 닿는다(fuzzer가 모든 변이를 seed에 적용). 미도달 시 graph.json의 post-reservations
  path별 sampleResponse를 조사해 어느 가드에서 멈췄는지 진단.
- [ ] **Step 3:** 기존 spec 파일의 "## 성공 기준" 뒤에 "## 실측 결과" 섹션을 추가해 전/후
  `coveredAppBranches`(static/both)와 L64 도달 증거를 기록 + 커밋.

---

## Self-Review
- spec 커버: extractConjunctions(T1)/enum·joint 변이(T2)/배선(T3)/측정(T4). ✅
- 비목표 준수: interprocedural/SMT/double-결합/DB-state/`||` 미처리. ✅
- 타입 일관: `Atom(Kind,fieldRef,op,numLiteral,value)`, `Conjunction(classFqn,method,line,atoms)`,
  `EndpointTarget`(+enumConstants,+conjunctions), `forTarget`→enumValues+joint 전 구간 동일. ✅
- 빌드 안전: EndpointTarget 보조 생성자 2개 위임 갱신 명시(빌드 깨짐 방지). ✅
- 측정: app-aggregate(handler 아님) 명시. ✅

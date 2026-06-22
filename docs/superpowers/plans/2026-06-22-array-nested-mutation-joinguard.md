# 배열/중첩 바디 변이 + 다중 필드 조인 가드 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** InputMutator가 최상위 `List<DTO>` 배열·중첩 객체 바디의 원소 필드를 변이하고, 두 입력 필드를 서로 비교하는 field-to-field 조인 가드의 양 arm을 여는 입력을 합성하게 한다.

**Architecture:** (1) `InputMutator`에 dot-path 인지 put/remove + 컨테이너-무관 `applyToBody`를 추가하고 두 explorer를 이를 경유하도록 바꾼다. (2) `BodyShapeExtractor`가 중첩 DTO를 dot-path 스칼라 리프로 재귀 평탄화한다. (3) `ConstraintExtractor`가 기존 비교식 순회에서 field-to-field `JoinGuard`를 추가 수집하고, `InputMutator.joinGuards`가 양 arm 동시세팅 변이를 합성한다. 변이 시그니처(`UnaryOperator<ObjectNode>`)는 불변.

**Tech Stack:** Java 17(컴파일 레벨)/실행 23, Gradle, Jackson 2.x, Spoon(AST), JUnit5 + AssertJ, REST Assured/docker-compose e2e.

## Global Constraints
- 산문 문서는 한국어. 코드/식별자/커밋 메시지/REQ-ID는 영어.
- 커밋 author/committer는 env vars로: `baekchangjoon <changjoon.baek@icloud.com>`.
- 결정성: 시간·랜덤 사용 금지(동일 입력 → 동일 출력).
- 변이 빌더 시그니처·이름·순서 보존(dedupe/markTried/예산 회계 유지).
- 출처: design `docs/superpowers/specs/2026-06-22-array-nested-mutation-joinguard-design.md`,
  요구사항 `docs/superpowers/requirements/2026-06-22-array-nested-mutation-joinguard-requirements.md`.
- 모듈 경로 접두: `graph-rag-builder/src/{main,test}/java/io/graphrag/builder/...`.

## File Structure
- Modify `explore/InputMutator.java` — putPath/removePath/putNullPath, `applyToBody`, `copy()`→JsonNode, `joinGuards`, `forTarget`.
- Modify `index/BodyShapeExtractor.java` — 재귀 dot-path 평탄화.
- Modify `index/ConstraintExtractor.java` — `JoinGuard` 레코드 + 숫자/문자열 추출.
- Modify `explore/EndpointTarget.java` — `joinGuards` 필드 + 생성자.
- Modify `run/EndpointExplorationRunner.java` — joinGuards 추출·배선.
- Modify `explore/HeuristicExplorer.java`, `explore/CoverageGuidedFuzzer.java` — applyToBody·empty-array.
- Modify `run/SampleInputSynthesizer.java` — dot-path putPath + FK carve-out.
- Create fixtures: `samples/order-service/.../orders/ShipController.java`(+`ShipRequest`),
  `graph-rag-builder/src/test/resources/sample-src/io/graphrag/sample/bounds/StringJoinController.java`.
- Create tests: `explore/InputMutatorPathTest.java`, `index/BodyShapeExtractorNestedTest.java`,
  `index/ConstraintExtractorJoinGuardTest.java`, `run/SampleInputSynthesizerNestedTest.java`,
  additions to `explore/InputMutatorTest.java`, `explore/HeuristicExplorerTest.java`,
  `explore/ArrayBodyMutationIntegrationTest.java`; e2e `request-orders-ship.json` + loop 추가.

---

### Task 1: JsonPaths 유틸(path-aware put) + copy()→JsonNode (REQ-009)

**REQ-IDs:** REQ-009

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/index/JsonPaths.java`
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/explore/InputMutator.java` (`copy` 반환형)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/index/JsonPathsTest.java`

**Interfaces:**
- Produces: `io.graphrag.builder.index.JsonPaths` — `public static void putPath(ObjectNode root, String path, int|long|double|String value)`,
  `putNullPath(ObjectNode, String)`, `removePath(ObjectNode, String)`; `InputMutator.copy(JsonNode):JsonNode` (반환형 변경).
- 근거: `InputMutator`(explore)와 `SampleInputSynthesizer`(run) 양쪽이 공유 → `index` 패키지의 public 유틸로 둔다.

- [ ] **Step 1: 실패 테스트 작성** — `JsonPathsTest.java`

```java
package io.graphrag.builder.index;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.model.Json;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JsonPathsTest {

    private ObjectNode obj() { return Json.mapper().createObjectNode(); }

    @Test
    void putPathMaterializesNonObject() {
        ObjectNode root = obj();
        root.putNull("address");                       // 중간이 NullNode
        JsonPaths.putPath(root, "address.city", "x");
        assertThat(root.get("address").get("city").asText()).isEqualTo("x");
    }

    @Test
    void removePathLeafOnly() {
        ObjectNode root = obj();
        JsonPaths.putPath(root, "a.b", 1L);
        JsonPaths.putPath(root, "a.c", 2L);
        JsonPaths.removePath(root, "a.b");
        assertThat(root.get("a").has("b")).isFalse();
        assertThat(root.get("a").get("c").asLong()).isEqualTo(2L);
    }

    @Test
    void flatNameUnchanged() {
        ObjectNode root = obj();
        JsonPaths.putPath(root, "userId", "u1");
        JsonPaths.putNullPath(root, "score");
        assertThat(root.get("userId").asText()).isEqualTo("u1");
        assertThat(root.get("score").isNull()).isTrue();
    }
}
```

- [ ] **Step 2: 실패 확인**
Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.index.JsonPathsTest' -q`
Expected: FAIL (`JsonPaths` not defined).

- [ ] **Step 3: 구현** — `JsonPaths.java` 신규 + `InputMutator.copy` 반환형 변경.

```java
package io.graphrag.builder.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** dot-path("a.b.c") 인지 JSON put/remove. 중간이 없거나 non-Object면 새 ObjectNode로 교체(.with() 금지). */
public final class JsonPaths {
    private JsonPaths() {}

    private static ObjectNode descend(ObjectNode root, String path) {
        String[] seg = path.split("\\.");
        ObjectNode node = root;
        for (int i = 0; i < seg.length - 1; i++) {
            JsonNode child = node.get(seg[i]);
            if (!(child instanceof ObjectNode)) {
                ObjectNode created = node.objectNode();
                node.set(seg[i], created);
                child = created;
            }
            node = (ObjectNode) child;
        }
        return node;
    }
    private static String leaf(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }

    public static void putPath(ObjectNode root, String path, long value)   { descend(root, path).put(leaf(path), value); }
    public static void putPath(ObjectNode root, String path, int value)    { descend(root, path).put(leaf(path), value); }
    public static void putPath(ObjectNode root, String path, double value) { descend(root, path).put(leaf(path), value); }
    public static void putPath(ObjectNode root, String path, String value) { descend(root, path).put(leaf(path), value); }
    public static void putNullPath(ObjectNode root, String path) { descend(root, path).putNull(leaf(path)); }
    public static void removePath(ObjectNode root, String path) {
        String[] seg = path.split("\\.");
        ObjectNode node = root;
        for (int i = 0; i < seg.length - 1; i++) {
            JsonNode child = node.get(seg[i]);
            if (!(child instanceof ObjectNode)) { return; }
            node = (ObjectNode) child;
        }
        node.remove(leaf(path));
    }
}
```

`InputMutator.copy` 반환형 변경(+ `import com.fasterxml.jackson.databind.JsonNode;`):
```java
public static JsonNode copy(JsonNode body) {
    return body.deepCopy();
}
```

- [ ] **Step 4: 통과 확인**
Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.index.JsonPathsTest' -q`
Expected: PASS. (`copy()` 호출부 타입은 Task 2에서 정리.)

- [ ] **Step 5: 커밋**
```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/index/JsonPaths.java \
        graph-rag-builder/src/main/java/io/graphrag/builder/explore/InputMutator.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/index/JsonPathsTest.java
GIT_AUTHOR_NAME=baekchangjoon GIT_AUTHOR_EMAIL=changjoon.baek@icloud.com \
GIT_COMMITTER_NAME=baekchangjoon GIT_COMMITTER_EMAIL=changjoon.baek@icloud.com \
git commit -m "feat(index): JsonPaths dot-path put/remove + copy()->JsonNode (REQ-009)"
```

---

### Task 2: 변이 빌더를 path-put으로 + applyToBody + explorer 배선 (REQ-001 단위, REQ-002)

**REQ-IDs:** REQ-001(단위 측), REQ-002

**Files:**
- Modify: `explore/InputMutator.java` (put/remove/putNull 호출을 path 버전으로; `applyToBody` 추가)
- Modify: `explore/HeuristicExplorer.java`, `explore/CoverageGuidedFuzzer.java`
- Test: `explore/HeuristicExplorerTest.java`(신규), `explore/ArrayBodyMutationIntegrationTest.java`(신규)

**Interfaces:**
- Consumes: Task 1의 `putPath`/`copy`.
- Produces: `static JsonNode applyToBody(JsonNode body, Mutation m)`.

- [ ] **Step 1: 실패 테스트 작성** — `ArrayBodyMutationIntegrationTest.java`

```java
package io.graphrag.builder.explore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.model.Endpoint;
import io.graphrag.model.Json;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArrayBodyMutationIntegrationTest {

    @Test
    void elementFieldMutationsAppliedToArrayBody() {
        ArrayNode base = Json.mapper().createArrayNode();
        base.add(Json.mapper().createObjectNode().put("userId", "u1").put("amount", 5));
        List<BodyShape.BodyField> fields = List.of(
                new BodyShape.BodyField("userId", "java.lang.String"),
                new BodyShape.BodyField("amount", "java.lang.Integer"));

        List<JsonNode> seen = new ArrayList<>();
        EndpointInvoker invoker = body -> { seen.add(body.deepCopy());
            return new InvocationOutcome(200, "", List.of()); };
        EndpointTarget target = new EndpointTarget(
                new Endpoint("e", "POST", "/api/orders/batch", null, null, false),
                base, fields, List.of(), invoker);

        new HeuristicExplorer().explore(target, new ExplorationBudget(100), new KnownCoverage());

        // happy(원본) 외에, element[0].amount를 0으로 만든 변이가 실제로 호출됐는지
        boolean zeroAmount = seen.stream().anyMatch(b ->
                b.isArray() && b.size() == 1 && b.get(0).path("amount").asInt(-1) == 0);
        boolean emptyArray = seen.stream().anyMatch(b -> b.isArray() && b.isEmpty());
        assertThat(zeroAmount).as("element[0] amount 변이").isTrue();
        assertThat(emptyArray).as("empty-array 변이").isTrue();
    }
}
```
(주: `Endpoint`/`InvocationOutcome`/`ExplorationBudget` 실제 시그니처는 기존 코드 기준으로 맞춘다 —
구현 전 `codegraph_node Endpoint`/`InvocationOutcome`로 생성자 확인.)

- [ ] **Step 2: 실패 확인**
Run: `./gradlew :graph-rag-builder:test --tests '*ArrayBodyMutationIntegrationTest' -q`
Expected: FAIL (zeroAmount/emptyArray false — 배열 변이 미적용).

- [ ] **Step 3: 구현**
(a) `InputMutator`의 모든 `body.put(name,…)`→`JsonPaths.putPath(body,name,…)`,
`body.putNull(name)`→`JsonPaths.putNullPath`, `body.remove(name)`→`JsonPaths.removePath`.
`interField`/`joint`/`realBounds` 등 동시세팅 람다도 동일 치환.
(b) `applyToBody` 추가:
```java
public static JsonNode applyToBody(JsonNode body, Mutation m) {
    if (body instanceof ObjectNode obj) { return m.apply().apply(obj); }
    if (body instanceof ArrayNode arr && !arr.isEmpty() && arr.get(0) instanceof ObjectNode el) {
        m.apply().apply(el);   // element[0] 대표 변이 (arr는 호출부에서 깊은 복사된 본문)
    }
    return body;
}
```
(c) `HeuristicExplorer.explore`:
```java
JsonNode base = target.baseInput().deepCopy();
tryInput(base, target, budget, known, inputs);
for (InputMutator.Mutation mutation : InputMutator.forTarget(target)) {
    tryInput(InputMutator.applyToBody(InputMutator.copy(base), mutation), target, budget, known, inputs);
}
if (base instanceof ArrayNode) {                       // empty-array 구조 변이
    tryInput(Json.mapper().createArrayNode(), target, budget, known, inputs);
}
```
(d) `CoverageGuidedFuzzer`: line 33 early-return 제거. line 46-47를
`JsonNode body = InputMutator.applyToBody(InputMutator.copy(queue.get(seedIndex).body()), mutation);`로,
이후 `markTried(body)`/`invoke(body)`는 `JsonNode body` 기준(변수 타입 ObjectNode→JsonNode).

- [ ] **Step 4: 통과 + 회귀 확인**
Run: `./gradlew :graph-rag-builder:test --tests '*ArrayBodyMutationIntegrationTest' --tests '*InputMutatorTest' --tests '*HeuristicExplorerTest' --tests '*CoverageGuidedFuzzerTest' -q`
Expected: PASS (기존 InputMutatorTest 회귀 포함).

- [ ] **Step 5: 커밋**
```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/explore/InputMutator.java \
        graph-rag-builder/src/main/java/io/graphrag/builder/explore/HeuristicExplorer.java \
        graph-rag-builder/src/main/java/io/graphrag/builder/explore/CoverageGuidedFuzzer.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/explore/ArrayBodyMutationIntegrationTest.java
GIT_AUTHOR_NAME=baekchangjoon GIT_AUTHOR_EMAIL=changjoon.baek@icloud.com \
GIT_COMMITTER_NAME=baekchangjoon GIT_COMMITTER_EMAIL=changjoon.baek@icloud.com \
git commit -m "feat(builder): array element[0] mutation via applyToBody + empty-array variant (REQ-001,REQ-002)"
```

---

### Task 3: BodyShapeExtractor 재귀 dot-path 평탄화 (REQ-005)

**REQ-IDs:** REQ-005

**Files:**
- Modify: `index/BodyShapeExtractor.java`
- Test: `index/BodyShapeExtractorNestedTest.java`

**Interfaces:**
- Produces: `extract`가 중첩 컴포넌트를 dot-path 리프(`BodyField(name="a.b", javaType=<scalar FQN>)`)로 전개.

- [ ] **Step 1: 실패 테스트 작성** — 네 메서드: `nestedField_flattensToDotPath`,
`nestedDepth_cappedAtMax`, `cyclicNested_perPathGuard`, `siblingSameType_bothExpanded`.
각 테스트는 인라인 소스를 임시 디렉터리에 쓰고 `BodyShapeExtractor.extract(model, fqn)`로 검증.
(기존 `BodyShapeExtractorTest`의 모델 빌드 패턴 재사용 — 구현 전 그 파일 참조.)

```java
// 예: nestedField_flattensToDotPath
// record Order(Address address) {}  record Address(String city) {}
// extract(model,"...Order").fields() 에 BodyField("address.city","java.lang.String") 포함
```

- [ ] **Step 2: 실패 확인**
Run: `./gradlew :graph-rag-builder:test --tests '*BodyShapeExtractorNestedTest' -q`
Expected: FAIL (현재 평탄화 없음 → `address.city` 없음, `address` 타입명만).

- [ ] **Step 3: 구현** — `extract`에 재귀 헬퍼 도입.

```java
private static final int MAX_NESTING_DEPTH = 3;

// 컴포넌트(name,type)를 dot-path 리프들로 전개. visited는 경로별(스택-로컬) cycle guard.
private static void flatten(CtModel model, String prefix, String compName,
        spoon.reflect.reference.CtTypeReference<?> type, int depth,
        java.util.Set<String> visited, List<BodyShape.BodyField> out) {
    String path = prefix.isEmpty() ? compName : prefix + "." + compName;
    String qn = type.getQualifiedName();
    boolean scalarOrLeaf = SCALAR_TYPES.contains(qn) || depth >= MAX_NESTING_DEPTH
            || elementType(type) != null;     // collection 리프 처리
    CtType<?> decl = type.getTypeDeclaration();
    if (decl != null && decl.isShadow()) { decl = null; }
    if (decl == null) { decl = findInModel(model, qn); }
    boolean nestedDto = !scalarOrLeaf && decl != null && !decl.isEnum() && !visited.contains(qn);
    if (!nestedDto) {
        out.add(new BodyShape.BodyField(path, qn));    // 리프(스칼라/enum/미해결/깊이초과/cycle/collection)
        return;
    }
    java.util.Set<String> next = new java.util.HashSet<>(visited);
    next.add(qn);
    List<BodyShape.BodyField> children = new ArrayList<>();
    collectComponents(decl, (cn, ct) -> flatten(model, path, cn, ct, depth + 1, next, children));
    if (children.isEmpty()) { out.add(new BodyShape.BodyField(path, qn)); }  // 빈 nested 폴백
    else { out.addAll(children); }
}
```
`extract`의 record/class 필드 수집부를 `flatten(model, "", compName, compType, 0, new HashSet<>(qn?), fields)`
호출로 교체(루트 타입을 visited 초기값에 넣어 self-cycle 차단). `collectComponents`는 record면
`getRecordComponents`, class면 `getFields`를 (name,typeRef) 콜백으로 통일하는 작은 헬퍼.

- [ ] **Step 4: 통과 확인**
Run: `./gradlew :graph-rag-builder:test --tests '*BodyShapeExtractorNestedTest' --tests '*BodyShapeExtractorTest' -q`
Expected: PASS (기존 BodyShapeExtractorTest 회귀 포함).

- [ ] **Step 5: 커밋**
```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/index/BodyShapeExtractor.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/index/BodyShapeExtractorNestedTest.java
GIT_AUTHOR_NAME=baekchangjoon GIT_AUTHOR_EMAIL=changjoon.baek@icloud.com \
GIT_COMMITTER_NAME=baekchangjoon GIT_COMMITTER_EMAIL=changjoon.baek@icloud.com \
git commit -m "feat(index): BodyShapeExtractor recursive dot-path flatten w/ depth+per-path cycle guard (REQ-005)"
```

---

### Task 4: SampleInputSynthesizer dot-path happy 합성 + FK carve-out (REQ-011)

**REQ-IDs:** REQ-011

**Files:**
- Modify: `run/SampleInputSynthesizer.java`
- Test: `run/SampleInputSynthesizerNestedTest.java`

**Interfaces:**
- Consumes: Task 1 `putPath`(같은 패키지가 아니므로 `InputMutator`의 가시성 고려 — 아래 주).

- [ ] **Step 1: 실패 테스트 작성** — `nestedHappyAndFkCarveOut`

```java
// shape: fields = [BodyField("address.city","java.lang.String"),
//                  BodyField("shipTo.userId","java.lang.String")]
// synthesize(shape, List.of()).body() 가
//   {"address":{"city":...}, "shipTo":{"userId":...}} 중첩 구조이고
//   최상위에 "address.city"/"shipTo.userId" 평면 키나 FK probe row가 없어야 한다.
```

- [ ] **Step 2: 실패 확인**
Run: `./gradlew :graph-rag-builder:test --tests '*SampleInputSynthesizerNestedTest' -q`
Expected: FAIL (`{"address.city":...}` 평면 키).

- [ ] **Step 3: 구현** — `synthesizeObject`에서 `field.name()`에 `.`가 있으면:
  - 값 세팅을 `body.put/set(name,…)` 대신 Task 1의 `index.JsonPaths.putPath`로 nested materialize.
  - FK 휴리스틱(`field.name().endsWith("Id")` 분기)은 `!name.contains(".")` 가드 추가.

- [ ] **Step 4: 통과 확인**
Run: `./gradlew :graph-rag-builder:test --tests '*SampleInputSynthesizerNestedTest' --tests '*SampleInputSynthesizer*Test' --tests '*InputMutatorPathTest' -q`
Expected: PASS.

- [ ] **Step 5: 커밋**
```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/run/SampleInputSynthesizer.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/run/SampleInputSynthesizerNestedTest.java
GIT_AUTHOR_NAME=baekchangjoon GIT_AUTHOR_EMAIL=changjoon.baek@icloud.com \
GIT_COMMITTER_NAME=baekchangjoon GIT_COMMITTER_EMAIL=changjoon.baek@icloud.com \
git commit -m "feat(run): nested dot-path happy synthesis via JsonPaths + FK heuristic carve-out (REQ-011)"
```

---

### Task 5: ConstraintExtractor JoinGuard 추출 + 문자열 fixture (REQ-006, REQ-008a)

**REQ-IDs:** REQ-006, REQ-008a

**Files:**
- Modify: `index/ConstraintExtractor.java`
- Create: `graph-rag-builder/src/test/resources/sample-src/io/graphrag/sample/bounds/StringJoinController.java`
- Test: `index/ConstraintExtractorJoinGuardTest.java`; Modify `index/ConstraintExtractorComparisonsTest.java`

**Interfaces:**
- Produces: `record JoinGuard(String classFqn,String method,int line,String leftRef,String op,String rightRef,JoinKind kind)`,
  `enum JoinKind{NUMERIC,STRING}`, `List<JoinGuard> extractJoinGuards(Path srcDir)`.

- [ ] **Step 1: 문자열 fixture 작성** — `StringJoinController.java`
```java
package io.graphrag.sample.bounds;
public class StringJoinController {
    public record Req(String a, String b) {}
    public String handle(Req req) {
        if (req.a().equals(req.b())) { return "same"; }   // field-to-field equals
        return "diff";
    }
}
```

- [ ] **Step 2: 실패 테스트 작성** — `ConstraintExtractorJoinGuardTest`:
`fieldToFieldNumericExtracted`(BoundsController → `JoinGuard(NUMERIC,"amount",">","score")` 포함),
`equalsFieldToFieldExtracted`(StringJoinController → `JoinGuard(STRING,"a","equals","b")` 포함).

- [ ] **Step 3: 실패 확인**
Run: `./gradlew :graph-rag-builder:test --tests '*ConstraintExtractorJoinGuardTest' -q`
Expected: FAIL (`extractJoinGuards` 미정의).

- [ ] **Step 4: 구현** — `extractJoinGuards`를 추가하되 **추가 모델 빌드 없이** 기존 패턴을 따른다.
숫자: `extractComparisons`의 `CtBinaryOperator` 순회와 동형으로, 양변이 모두 `fieldRef!=null`이고
`literalLong` 둘 다 없으면 `JoinGuard(NUMERIC,leftRef,opStr,rightRef)`. 문자열: `extractStringEqualities`
순회와 동형으로, target·arg 모두 `fieldRef!=null`이고 `stringLiteral` 둘 다 null이면
`JoinGuard(STRING,targetRef,"equals",argRef)`. 정렬·dedupe는 기존 패턴.
`ConstraintExtractorComparisonsTest`의 "리터럴 없음 → 무시(rel)" 단언을 JoinGuard 추출 단언으로 갱신
(주석도 정정).

- [ ] **Step 5: 통과 확인**
Run: `./gradlew :graph-rag-builder:test --tests '*ConstraintExtractorJoinGuardTest' --tests '*ConstraintExtractorComparisonsTest' -q`
Expected: PASS.

- [ ] **Step 6: 커밋**
```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/index/ConstraintExtractor.java \
        graph-rag-builder/src/test/resources/sample-src/io/graphrag/sample/bounds/StringJoinController.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/index/ConstraintExtractorJoinGuardTest.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/index/ConstraintExtractorComparisonsTest.java
GIT_AUTHOR_NAME=baekchangjoon GIT_AUTHOR_EMAIL=changjoon.baek@icloud.com \
GIT_COMMITTER_NAME=baekchangjoon GIT_COMMITTER_EMAIL=changjoon.baek@icloud.com \
git commit -m "feat(index): extract field-to-field JoinGuard (numeric+string equals) (REQ-006,REQ-008a)"
```

---

### Task 6: InputMutator.joinGuards 변이 + EndpointTarget·runner 배선 (REQ-007, REQ-008b)

**REQ-IDs:** REQ-007, REQ-008b

**Files:**
- Modify: `explore/InputMutator.java`, `explore/EndpointTarget.java`, `run/EndpointExplorationRunner.java`
- Test: additions to `explore/InputMutatorTest.java`

**Interfaces:**
- Consumes: Task 5 `JoinGuard`/`JoinKind`, Task 1 `JsonPaths.putPath`.
- Produces: `static List<Mutation> joinGuards(List<BodyShape.BodyField> fields, List<ConstraintExtractor.JoinGuard> guards)`;
  `EndpointTarget`에 `List<JoinGuard> joinGuards()` 접근자.

- [ ] **Step 1: 실패 테스트 작성** — `InputMutatorTest`에
`joinGuards_numericEmitsThreeArms`(NUMERIC amount>score, fields⊇{amount,score} → 3개 변이, 각 변이가
두 필드 동시세팅: `amount<score`/`==`/`>` 검증), `joinGuards_stringEmitsTwoArms`(STRING a,b → 2개,
`a==b`/`a≠b`), `joinGuards_skipWhenFieldMissing`(부분 → 빈 리스트).

- [ ] **Step 2: 실패 확인**
Run: `./gradlew :graph-rag-builder:test --tests '*InputMutatorTest' -q`
Expected: FAIL (`joinGuards` 미정의).

- [ ] **Step 3: 구현**
(a) `EndpointTarget`에 `List<JoinGuard> joinGuards` 필드 추가, **canonical + 5-arg + 6-arg 생성자 모두**
`List.of()` 디폴트 전달.
(b) `InputMutator.joinGuards`:
```java
public static List<Mutation> joinGuards(List<BodyShape.BodyField> fields,
                                        List<ConstraintExtractor.JoinGuard> guards) {
    HashSet<String> names = new HashSet<>();
    for (BodyShape.BodyField f : fields) { names.add(f.name()); }
    List<Mutation> out = new ArrayList<>();
    for (ConstraintExtractor.JoinGuard g : guards) {
        if (!names.contains(g.leftRef()) || !names.contains(g.rightRef())) { continue; }
        String base = "joinguard-" + g.leftRef() + "-" + g.op() + "-" + g.rightRef() + "-";
        if (g.kind() == ConstraintExtractor.JoinKind.NUMERIC) {
            out.add(num(base + "lt", g.leftRef(), 0, g.rightRef(), 1));
            out.add(num(base + "eq", g.leftRef(), 0, g.rightRef(), 0));
            out.add(num(base + "gt", g.leftRef(), 1, g.rightRef(), 0));
        } else {
            out.add(str(base + "eq", g.leftRef(), "x", g.rightRef(), "x"));
            out.add(str(base + "ne", g.leftRef(), "x", g.rightRef(), "y"));
        }
    }
    return out;
}
private static Mutation num(String n, String l, long lv, String r, long rv) {
    return new Mutation(n, body -> { JsonPaths.putPath(body, l, lv); JsonPaths.putPath(body, r, rv); return body; });
}
private static Mutation str(String n, String l, String lv, String r, String rv) {
    return new Mutation(n, body -> { JsonPaths.putPath(body, l, lv); JsonPaths.putPath(body, r, rv); return body; });
}
```
(c) `forTarget`에 `all.addAll(joinGuards(target.mutableFields(), target.joinGuards()));`를 `joint` 다음 줄에.
(d) `EndpointExplorationRunner`: 기존 `extractComparisons` 호출 지점에서 `extractJoinGuards`도 받아
`EndpointTarget` 생성 인자에 전달(두 생성 지점 line 244-247, 284-285).

- [ ] **Step 4: 통과 + 회귀 확인**
Run: `./gradlew :graph-rag-builder:test --tests '*InputMutatorTest' --tests '*EndpointExplorationRunnerTest' -q`
Expected: PASS.

- [ ] **Step 5: 커밋**
```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/explore/InputMutator.java \
        graph-rag-builder/src/main/java/io/graphrag/builder/explore/EndpointTarget.java \
        graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/explore/InputMutatorTest.java
GIT_AUTHOR_NAME=baekchangjoon GIT_AUTHOR_EMAIL=changjoon.baek@icloud.com \
GIT_COMMITTER_NAME=baekchangjoon GIT_COMMITTER_EMAIL=changjoon.baek@icloud.com \
git commit -m "feat(builder): joinGuards mutations (3-arm numeric / 2-arm string) + wiring (REQ-007,REQ-008b)"
```

---

### Task 7: 중첩 JSON fixture + E2E (REQ-003, REQ-004, REQ-001 black-box)

**REQ-IDs:** REQ-001, REQ-003, REQ-004

**Files:**
- Create: `samples/order-service/src/main/java/io/graphrag/sample/orders/ShipController.java`
- Create: `e2e/request-orders-ship.json`
- Modify: `e2e/run-e2e.sh` (generate 루프에 `request-orders-ship` 추가)

**Interfaces:**
- Consumes: 전 Task의 빌더 동작.

- [ ] **Step 1: fixture 작성** — `ShipController.java`
```java
package io.graphrag.sample.orders;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class ShipController {
    public record ShipRequest(String userId, Address address) {}

    @PostMapping("/ship")
    public String ship(@RequestBody ShipRequest req) {
        if (req.address() == null || req.address().getCity() == null
                || req.address().getCity().isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "city required");
        }
        return "shipped:" + req.address().getCity();
    }
}
```
`e2e/request-orders-ship.json`:
```json
{ "endpointId": "post-api-orders-ship", "testClassName": "OrdersShipPostTest",
  "packageName": "io.graphrag.generated", "authMode": "REAL" }
```
`run-e2e.sh`의 `for req in ...` 목록에 `request-orders-ship` 추가.

- [ ] **Step 2: E2E 실행(외부 루프, 초기 red 가능)**
Run: `bash e2e/run-e2e.sh`
Expected(완성 시): 빌더 탐색이 `/api/orders/ship` happy(2xx, 중첩 JSON)와 `null-address.city` 변이의 400을
모두 만들고, `/api/orders/batch` 원소 변이 path가 생성되며, 생성 테스트(`OrdersShipPostTest`,
`OrdersBatchPostTest` 등) 전부 GREEN.

- [ ] **Step 3: 진단·수정 루프** — red면 `$OUT/graph`의 ship endpoint paths / happy body JSON 형태
(`{"address":{"city":…}}`인지)와 batch 원소 변이 path 유무를 확인하고 Task 2–4 회귀 점검. (docker 미가용
환경이면 그 사실을 명시하고 빌더-탐색 통합 레벨 산출물로 대체 검증.)

- [ ] **Step 4: 커밋**
```bash
git add samples/order-service/src/main/java/io/graphrag/sample/orders/ShipController.java \
        e2e/request-orders-ship.json e2e/run-e2e.sh
GIT_AUTHOR_NAME=baekchangjoon GIT_AUTHOR_EMAIL=changjoon.baek@icloud.com \
GIT_COMMITTER_NAME=baekchangjoon GIT_COMMITTER_EMAIL=changjoon.baek@icloud.com \
git commit -m "test(e2e): nested ShipRequest handler + array/nested mutation E2E (REQ-001,REQ-003,REQ-004)"
```

---

### Task 8: 회귀·매트릭스·문서 게이트 (REQ-010 + 전 REQ green 확인)

**REQ-IDs:** REQ-010 (+ 전 REQ 매트릭스 검증)

**Files:**
- Modify: 요구사항 매트릭스 상태 🟡→🟢, design/spec 문서 동기화.

- [ ] **Step 1: 전 모듈 단위/통합 회귀**
Run: `./gradlew :graph-rag-builder:test -q`
Expected: PASS (전체).

- [ ] **Step 2: 평면 바디 회귀(REQ-010)** — 기존 e2e 전체 GREEN + 생성 테스트 수 비축소.
Run: `bash e2e/run-e2e.sh` 로그의 "생성된 테스트 클래스: N" 값이 변경 전(기준값은 Task 1 착수 전 1회
측정해 기록) 이상인지 확인.

- [ ] **Step 3: 매트릭스 갱신** — `docs/superpowers/requirements/...md`의 Status를 실제 통과 테스트 기준
🟢로, Coverage 줄을 `12/12 green (100%)`로. 각 green REQ가 실제 테스트명과 대응하는지 대조.

- [ ] **Step 4: 문서 동기화** — design spec/요구사항에 구현 중 드러난 차이가 있으면 반영(역전파 규칙).

- [ ] **Step 5: 커밋**
```bash
git add docs/superpowers/
GIT_AUTHOR_NAME=baekchangjoon GIT_AUTHOR_EMAIL=changjoon.baek@icloud.com \
GIT_COMMITTER_NAME=baekchangjoon GIT_COMMITTER_EMAIL=changjoon.baek@icloud.com \
git commit -m "docs: traceability matrix 12/12 green + doc sync (REQ-010)"
```

---

## Self-Review
1. **Spec coverage:** REQ-001(T2,T7) REQ-002(T2) REQ-003(T7) REQ-004(T7) REQ-005(T3) REQ-006(T5)
   REQ-007(T6) REQ-008a(T5) REQ-008b(T6) REQ-009(T1) REQ-010(T8) REQ-011(T4) — 전 REQ 매핑됨.
2. **Placeholder scan:** 코드 스텝에 실제 코드 포함. 일부 테스트 본문은 "기존 패턴 참조"로 위임했으나
   시그니처·검증 대상은 명시 — 실행 에이전트가 구현 전 해당 기존 테스트/심볼을 codegraph로 확인.
3. **Type consistency:** `JsonPaths.putPath/removePath/putNullPath`(T1 정의, `index` public 유틸 →
   T2/T4/T6 소비), `InputMutator.copy():JsonNode`(T1), `applyToBody(JsonNode,Mutation):JsonNode`(T2),
   `JoinGuard`/`JoinKind`(T5 정의→T6 소비), `joinGuards(fields,guards)`(T6) 일관. `putPath`는 Task 1부터
   `index.JsonPaths`로 단일 정의 — 이동/재명명 없음.

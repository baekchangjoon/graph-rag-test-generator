# Stage 0 — 유효 입력값 합성 Implementation Plan

> REQUIRED SUB-SKILL: superpowers:subagent-driven-development 또는 executing-plans. 체크박스 단위 추적.

**Goal:** 합성 입력(POST/PUT body + GET query)의 enum/날짜/이메일 필드에 유효 값을 넣어 SUT 역직렬화/
바인딩이 성공, service 검증 분기에 진입하게 한다. spec: `docs/superpowers/specs/2026-06-14-stage0-valid-body-synthesis-design.md`.

**Architecture:** Spoon으로 enum 상수 맵 추출 → 두 합성기(`SampleInputSynthesizer`,
`ReadInputSynthesizer`)에 주입 → `putScalar`/`scalarFor`가 enum=첫상수·날짜=ISO·이메일=유효값 생성.
InputMutator/오라클은 불변(enum 대체값 변이는 Stage 0 비목표).

**측정:** service 분기는 `ExplorationReport.coveredAppBranches`(whole-app)로만 보임(handler 리포트 아님).

---

## File Structure
- 생성: `graph-rag-builder/src/main/java/io/graphrag/builder/index/EnumConstantExtractor.java`
- 생성: `graph-rag-builder/src/test/java/io/graphrag/builder/index/EnumConstantExtractorTest.java`
- 생성(픽스처): `graph-rag-builder/src/test/resources/sample-src/io/graphrag/sample/enums/Palette.java`
- 수정: `.../run/SampleInputSynthesizer.java`, `.../run/ReadInputSynthesizer.java`,
  `.../run/EndpointExplorationRunner.java`, `.../cli/BuilderCli.java`
- 수정(테스트): `.../run/SampleInputSynthesizerTest.java`(있으면 보강, 없으면 생성),
  `.../run/ReadInputSynthesizerTest.java`(보강)

---

### Task 1: EnumConstantExtractor

**Files:** Create `index/EnumConstantExtractor.java`, fixture `sample-src/.../enums/Palette.java`, `index/EnumConstantExtractorTest.java`

- [ ] **Step 1: 픽스처**
```java
// sample-src/io/graphrag/sample/enums/Palette.java
package io.graphrag.sample.enums;
public enum Palette {
    RED, GREEN, BLUE;
    public enum Shade { LIGHT, DARK }   // 중첩 enum
}
```

- [ ] **Step 2: 실패 테스트**
```java
package io.graphrag.builder.index;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class EnumConstantExtractorTest {
    private static final Path SAMPLE_SRC = Path.of("src/test/resources/sample-src");

    @Test
    void extract_topLevelAndNestedEnums_inDeclarationOrder() {
        Map<String, List<String>> m = new EnumConstantExtractor().extract(SAMPLE_SRC);
        assertThat(m.get("io.graphrag.sample.enums.Palette")).containsExactly("RED", "GREEN", "BLUE");
        // 중첩 enum: BodyShapeExtractor가 BodyField.javaType에 raw getQualifiedName()($ 구분)을 쓰므로
        // 추출기 키도 $ 유지(정합). top-level PriceTier는 $ 없어 무관.
        assertThat(m.get("io.graphrag.sample.enums.Palette$Shade")).containsExactly("LIGHT", "DARK");
    }
}
```

- [ ] **Step 3: 실패 확인** — `./gradlew :graph-rag-builder:test --tests "io.graphrag.builder.index.EnumConstantExtractorTest"` → COMPILE FAIL.

- [ ] **Step 4: 구현**
```java
package io.graphrag.builder.index;

import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtEnum;
import spoon.reflect.visitor.filter.TypeFilter;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** SUT 소스의 enum FQN → 선언 순서 상수명 목록. happy 입력의 enum 필드를 유효 상수로 합성하는 근거. */
public class EnumConstantExtractor {

    public Map<String, List<String>> extract(Path srcDir) {
        Launcher launcher = new Launcher();
        launcher.addInputResource(srcDir.toString());
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.getEnvironment().setComplianceLevel(17);
        CtModel model = launcher.buildModel();

        Map<String, List<String>> result = new TreeMap<>();
        for (CtEnum<?> e : model.getElements(new TypeFilter<>(CtEnum.class))) {
            // raw getQualifiedName() 사용($ 그대로) — BodyShapeExtractor의 BodyField.javaType과 동일 포맷.
            result.put(e.getQualifiedName(),
                    e.getEnumValues().stream().map(v -> v.getSimpleName()).toList());
        }
        return result;
    }
}
```

- [ ] **Step 5: 통과 확인** — 위 테스트 PASS.
- [ ] **Step 6: 커밋** `feat(builder): EnumConstantExtractor (FQN -> declared constants)`

---

### Task 2: SampleInputSynthesizer — enum/날짜/이메일 + DATE 시드 수정

**Files:** Modify `run/SampleInputSynthesizer.java`, test `run/SampleInputSynthesizerTest.java`

- [ ] **Step 1: 실패 테스트** — `SampleInputSynthesizerTest.java`는 **이미 존재**(synthesize_fkField_*,
  synthesize_scalarFields_*, synthesize_isDeterministic). 기존 테스트는 **유지하고 아래 2개를 추가**.
  필요 import 추가: `java.util.Map`.
```java
package io.graphrag.builder.run;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.builder.index.BodyShape;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class SampleInputSynthesizerTest {
    private static BodyShape shape(BodyShape.BodyField... f) { return new BodyShape("T", List.of(f)); }

    @Test
    void synthesize_enum_date_email_validValues() {
        Map<String, List<String>> enums = Map.of("io.x.PriceTier", List.of("BASIC", "VIP"));
        ObjectNode body = new SampleInputSynthesizer(enums).synthesize(shape(
                new BodyShape.BodyField("priceTier", "io.x.PriceTier"),
                new BodyShape.BodyField("checkInDate", "java.time.LocalDate"),
                new BodyShape.BodyField("ownerEmail", "java.lang.String"),
                new BodyShape.BodyField("nights", "int"),
                new BodyShape.BodyField("note", "java.lang.String")
        ), List.of()).body();
        assertThat(body.get("priceTier").asText()).isEqualTo("BASIC");
        assertThat(body.get("checkInDate").asText()).isEqualTo("2999-01-01");
        assertThat(body.get("ownerEmail").asText()).isEqualTo("probe@example.com");
        assertThat(body.get("nights").asInt()).isEqualTo(1);              // 정수 우선
        assertThat(body.get("note").asText()).isEqualTo("sample-note");   // 일반 String default
    }

    @Test
    void synthesize_noArgCtor_defaultsForEnum() {   // 빈 맵 호환
        ObjectNode body = new SampleInputSynthesizer().synthesize(shape(
                new BodyShape.BodyField("priceTier", "io.x.PriceTier")), List.of()).body();
        assertThat(body.get("priceTier").asText()).isEqualTo("sample-priceTier");
    }
}
```

- [ ] **Step 2: 실패 확인** — COMPILE FAIL(생성자 없음).

- [ ] **Step 3: 구현** — 필드/생성자 추가, `putScalar`에서 `static` 제거(인스턴스화; 호출부 line 33
  `putScalar(body, field)`는 암묵 this로 **그대로 컴파일**), `defaultFor`는 **static 유지**하며 시간타입만
  보강(기존 `synthesize_fkField`의 VARCHAR→"probe"는 toUpperCase 후에도 통과). `import java.util.Map` 추가.
```java
// class 상단
private final java.util.Map<String, java.util.List<String>> enumConstants;
public SampleInputSynthesizer() { this(java.util.Map.of()); }
public SampleInputSynthesizer(java.util.Map<String, java.util.List<String>> enumConstants) {
    this.enumConstants = enumConstants;
}
```
`putScalar`를 static→인스턴스 메서드로 바꾸고(enumConstants 접근), 호출부(line 33 `putScalar(body, field)`)는 그대로:
```java
private void putScalar(ObjectNode body, BodyShape.BodyField field) {
    String t = field.javaType();
    switch (t) {
        case "java.lang.Integer", "int", "java.lang.Long", "long",
             "java.lang.Short", "short" -> { body.put(field.name(), 1); return; }
        case "java.lang.Double", "double", "java.lang.Float", "float",
             "java.math.BigDecimal" -> { body.put(field.name(), 1.0); return; }
        case "java.lang.Boolean", "boolean" -> { body.put(field.name(), true); return; }
        default -> { }
    }
    switch (t) {   // 시간 타입
        case "java.time.LocalDate" -> { body.put(field.name(), "2999-01-01"); return; }
        case "java.time.LocalDateTime" -> { body.put(field.name(), "2999-01-01T00:00:00"); return; }
        case "java.time.LocalTime" -> { body.put(field.name(), "00:00:00"); return; }
        case "java.time.Instant", "java.time.OffsetDateTime", "java.time.ZonedDateTime" ->
                { body.put(field.name(), "2999-01-01T00:00:00Z"); return; }
        default -> { }
    }
    List<String> consts = enumConstants.get(t);
    if (consts == null) {   // simple-name 폴백
        String simple = t.substring(t.lastIndexOf('.') + 1);
        consts = enumConstants.entrySet().stream()
                .filter(e -> e.getKey().substring(e.getKey().lastIndexOf('.') + 1).equals(simple))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }
    if (consts != null && !consts.isEmpty()) { body.put(field.name(), consts.get(0)); return; }
    if (field.name().toLowerCase().endsWith("email")) { body.put(field.name(), "probe@example.com"); return; }
    body.put(field.name(), "sample-" + field.name());
}
```
그리고 `defaultFor`(FK 부모 시드)도 시간타입 보강(같은 DATE 크래시 방지):
```java
private static Object defaultFor(ColumnSchema column) {
    String type = column.jdbcType().toUpperCase();
    if (type.contains("CHAR") || type.contains("TEXT") || type.contains("CLOB")) return "probe";
    if (type.contains("BOOL")) return true;
    if (type.contains("TIMESTAMP") || type.contains("DATETIME")) return java.time.LocalDateTime.of(2999,1,1,0,0);
    if (type.contains("DATE")) return java.time.LocalDate.of(2999,1,1);
    if (type.contains("TIME")) return java.time.LocalTime.of(0,0);
    if (type.contains("UUID")) return java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
    return 1;
}
```
필요한 import: `java.util.Map`(이미 List 있음 — Map 추가).

- [ ] **Step 4: 통과 확인** — SampleInputSynthesizerTest PASS.
- [ ] **Step 5: 커밋** `feat(builder): SampleInputSynthesizer synthesizes valid enum/date/email + temporal FK-seed`

---

### Task 3: ReadInputSynthesizer — query/path enum·날짜

**Files:** Modify `run/ReadInputSynthesizer.java`, test `run/ReadInputSynthesizerTest.java`

- [ ] **Step 1: 실패 테스트(보강)** — `ReadInputSynthesizerTest`에 추가. **`ReadInputSynthesizer.defaultFor`는
  이미 시간타입 처리됨 → 수정 금지.** scalarFor만 바꾼다.
```java
@Test
void synthesize_enumQueryParam_returnsFirstConstant() {
    java.util.Map<String, java.util.List<String>> enums =
            java.util.Map.of("io.x.Palette", java.util.List.of("RED", "GREEN"));
    io.graphrag.model.Endpoint endpoint = new io.graphrag.model.Endpoint(
            "get-api-items", "GET", "/api/items", "x.C", "get",
            java.util.List.of(new io.graphrag.model.EndpointParam(
                    "palette", "io.x.Palette", io.graphrag.model.ParamKind.QUERY)),
            false);
    SynthesizedInput out = new ReadInputSynthesizer(enums).synthesize(endpoint, java.util.List.of());
    assertThat(out.body().get("palette").asText()).isEqualTo("RED");
}
```
(빈 `tables` → `resolveTargetTable` null → seed 없음, QUERY param은 input body에 put되므로 동작.)

- [ ] **Step 2: 구현** — 필드/생성자 2개 추가(빈 맵 기본), `scalarFor`에 enum/날짜 분기 추가:
```java
private final java.util.Map<String, java.util.List<String>> enumConstants;
public ReadInputSynthesizer() { this(java.util.Map.of()); }
public ReadInputSynthesizer(java.util.Map<String, java.util.List<String>> enumConstants) {
    this.enumConstants = enumConstants;
}
```
`scalarFor`(현재 static)를 인스턴스로 바꾸고:
```java
private String scalarFor(EndpointParam param, int probeId) {
    String t = param.javaType();
    switch (t) {
        case "java.lang.Integer", "int", "java.lang.Long", "long",
             "java.lang.Short", "short" -> { return String.valueOf(probeId); }
        case "java.time.LocalDate" -> { return "2999-01-01"; }
        case "java.time.LocalDateTime" -> { return "2999-01-01T00:00:00"; }
        default -> { }
    }
    java.util.List<String> consts = enumConstants.get(t);
    if (consts == null) {
        String simple = t.substring(t.lastIndexOf('.') + 1);
        consts = enumConstants.entrySet().stream()
                .filter(e -> e.getKey().substring(e.getKey().lastIndexOf('.') + 1).equals(simple))
                .map(java.util.Map.Entry::getValue).findFirst().orElse(null);
    }
    if (consts != null && !consts.isEmpty()) { return consts.get(0); }
    return "probe-" + param.name();
}
```
(현재 `scalarFor`가 static이면 호출부의 정적 참조도 인스턴스로.)

- [ ] **Step 3: 통과 확인** — ReadInputSynthesizerTest PASS.
- [ ] **Step 4: 커밋** `feat(builder): ReadInputSynthesizer synthesizes valid enum/date query params`

---

### Task 4: 배선 (BuilderCli → EndpointExplorationRunner → 두 합성기)

**Files:** Modify `cli/BuilderCli.java`, `run/EndpointExplorationRunner.java`

- [ ] **Step 1:** `EnumConstantExtractor`는 순수 파일 파싱(SUT/Docker 불요) → **AnalysisEnvironment try
  블록 밖**, `JacocoAgent.prepare(workDir)` 전에 1회:
```java
Map<String, List<String>> enumConstants =
        new io.graphrag.builder.index.EnumConstantExtractor().extract(config.sutSrc());
```
  (`Map`/`List`는 BuilderCli에 이미 import됨.)
- [ ] **Step 2:** `EndpointExplorationRunner` 생성자에 `Map<String,List<String>> enumConstants`를
  **마지막 파라미터**로 추가 + 필드 저장. `run()`의 line 126-127을:
```java
SynthesizedInput happy = readPath
        ? new ReadInputSynthesizer(enumConstants).synthesize(endpoint, tables)
        : new SampleInputSynthesizer(enumConstants).synthesize(shape, tables);
```
- [ ] **Step 3:** `EndpointExplorationRunner`를 `new` 하는 곳은 **`BuilderCli`(line 215) 1곳뿐**
  (grep `new EndpointExplorationRunner` 확인). 그 호출의 **마지막 인자로** `enumConstants` 추가.
- [ ] **Step 4: 컴파일 + 전체 단위 테스트**
  `./gradlew :shared-model:test :graph-rag-builder:test :test-generator:test` → GREEN (BuilderE2eTest 포함).
- [ ] **Step 5: 커밋** `feat(builder): wire EnumConstantExtractor into input synthesizers`

---

### Task 5: petclinic 검증 (A/B) + 회귀

- [ ] **Step 1:** order-service e2e: `./e2e/run-e2e.sh` → 22/22 GREEN(무회귀).
- [ ] **Step 2:** petclinic 빌더 재실행(static/both), `coveredAppBranches` Stage0 전(이전 33/253) 대비
  증가 확인. reservations 400이 "역직렬화 실패"→"검증 통과/실패"로 바뀌어 service 단변수 가드 진입했는지
  exploration-report로 확인.
- [ ] **Step 3:** 결과 기록(전/후 coveredAppBranches, static vs both).

---

## Self-Review
- spec 커버: enum 추출(T1)/synthesize enum·date·email(T2)/read-path(T3)/배선(T4)/측정·검증(T5). ✅
- 비목표 준수: InputMutator 불변(enum 대체값 변이 없음), 다변수 가드 미목표. ✅
- 타입 일관: `Map<String,List<String>> enumConstants` 전 구간 동일. `putScalar`/`scalarFor` static→instance 전환 시 호출부 정합. ✅
- 측정: app-aggregate 사용(handler 아님) 명시. ✅

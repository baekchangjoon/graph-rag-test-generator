# 레거시 @Controller 폼 바인딩 도달성 — 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development 또는 executing-plans로 task별 구현.
> 기반 spec: `docs/superpowers/plans/2026-06-17-legacy-form-binding-reachability.md`(§10 triage 반영본). 본 계획은 그 spec의
> Phase 0–4를 bite-sized TDD task로 분해한다. 모든 단언/비목표/위험은 spec이 source of truth.

**Goal:** `@Controller` 폼 커맨드의 다중-커맨드 선택·참조 엔티티(name/id)·중첩·PropertyEditor 바인딩을 합성해 폼 happy arm 도달(커버리지 전용, 회귀 0).

**Architecture:** 정적 분류(`ConverterRegistryIndexer` + 커맨드 필드 bindingKind) → `FormFieldBinding` 메타로 surface → 러너가 참조 백업행 DB read 후 reference-aware happy base 합성 + name/id backtrack trial. 미확정은 스칼라/skip 폴백.

**Tech Stack:** Java 17, Spoon(noClasspath) 인덱싱, Gradle, JUnit5/AssertJ, Postgres(testcontainers/외부), 기존 graph-rag-builder 인프라.

---

## 파일 구조
- `graph-rag-builder/.../index/FormFieldBinding.java` (신규, **builder index 패키지** — shared-model 아님; BodyShape가
  builder에 있어 순환의존 회피): **static 메타만** record `{field, javaType, kind(SCALAR|REFERENCE|NESTED), refEntityFqn,
  joinColumn, nestedTypeFqn}`. `referencedTable`/refPk/refName 컬럼은 런타임(러너)에서 `TableSchema`로 해석(인덱서는 DB
  미기동 시점이라 스키마 모름 — Gemini I2).
- `graph-rag-builder/.../index/ConverterRegistryIndexer.java` (신규): Formatter/Converter(전역) + @InitBinder
  registerCustomEditor(컨트롤러-local 맵) 수집.
- `graph-rag-builder/.../index/IndexResult.java` (수정): `Map<String endpointId, List<FormFieldBinding>> formBindingIndex` 추가(spec 명과 일치).
- `graph-rag-builder/.../index/EndpointIndexer.java` (수정): `selectFormCommand`(@Valid/@Validated 우선) + 커맨드 필드 bindingKind 분류(static).
- `graph-rag-builder/.../run/FormBodySynthesizer.java` (신규): bindingKind별 happy base(스칼라/참조-후보값/중첩 평면 점-경로). 시그니처: `ObjectNode synthesize(BodyShape shape, List<FormFieldBinding> bindings, java.util.Map<String,String> refValues)` (refValues=필드→런타임 후보값; 비면 스칼라 폴백).
- `graph-rag-builder/.../run/EndpointExplorationRunner.java` (수정): 참조 백업테이블 런타임 해석 + 행 read/seed → refValues 산출 → reference-aware base + backtrack trial(budget 내, path discoveredBy="form-ref-trial" 귀속).
- `graph-rag-builder/.../cli/BuilderCli.java` (수정): `formBindingIndex.getOrDefault(endpoint.id(),List.of())`를 run()에 새 파라미터로 전달(생성자 아님 — I11).
- `samples/order-service/.../orders/*` (신규 픽스처, 기존 OrderWebController와 같은 패키지 — I9): 5종 폼 컨트롤러. 참조 행은 **러너 런타임 seed-if-empty로 생성**(전역 data.sql 미사용 — ddl-auto 순서·E2E 부작용 회피, Gemini I3/GPT I5).
- `graph-rag-builder/.../cli/BuilderIntegrationTest.java` (수정): 5종 수용 단언 + `containsExactly` 신규 ID 갱신.

> **참고(E2E 실행 환경)**: `BuilderIntegrationTest`는 `@EnabledIfSystemProperty(sut.jar)` + Docker 필요 → 환경 없으면 SKIP(FAIL 아님).
> 본 환경엔 Docker+sut.jar 배선됨(`:graph-rag-builder:test`가 bootJar 후 실행). petclinic `.work/run-suites.sh`는
> 메인 체크아웃의 **로컬 미커밋 하니스 → 선택적 수동 회귀**. 필수 게이트 = in-repo E2E(5종) + `./gradlew test`.

---

## Phase 0 — E2E 픽스처 + 수용 단언 (RED)

### Task 0.1: 다중-커맨드 픽스처
**Files:** Create `samples/order-service/src/main/java/io/graphrag/sample/orders/MultiCommandWebController.java`
- [ ] Step 1: `@Controller @RequestMapping("/web/multi")` + `@PostMapping` `submit(@ModelAttribute("helper") HelperForm helper, @Valid @ModelAttribute("cmd") CmdForm cmd, BindingResult br)` → CmdForm `@Min` 가드 양 arm(redirect:/ok|/error). HelperForm·CmdForm은 nested static class(scalar 필드).
- [ ] Step 2: `./gradlew :samples:order-service:compileJava`. Expected: PASS. commit.

### Task 0.2: 참조-name/id + 중첩 + PropertyEditor 픽스처
**Files:** Create `.../orders/RefFormController.java`(name-Formatter), `.../orders/IdRefFormController.java`(id-Converter), `.../orders/NestedFormController.java`, `.../orders/EditorFormController.java`, 관련 엔티티/Formatter/Converter/PropertyEditor.
- [ ] Step 1: spec §5 1–5에 맞춰 작성. name-Formatter=`Formatter<Color>`(이름 조회), id-Converter=`Converter<String,Brand>`(PK 조회), 중첩=`Address` POJO 필드, PropertyEditor=`@InitBinder registerCustomEditor(Sku.class,…)`(**Color/Brand와 다른 타입 Sku** — 전역 레지스트리 테스트와 충돌 회피, Gemini I4). 참조 행은 전역 data.sql 미사용 — 러너 런타임 seed-if-empty가 생성(Phase 3).
- [ ] Step 2: `./gradlew :samples:order-service:compileJava`. Expected: PASS. commit.

### Task 0.3: E2E 수용 단언 (RED)
**Files:** Modify `graph-rag-builder/src/test/java/io/graphrag/builder/cli/BuilderIntegrationTest.java`
- [ ] Step 1: `containsExactly` endpoint 목록에 신규 ID **알파벳 순 병합**(`post-web-multi`, `post-web-ref`, `post-web-idref`, `post-web-nested`, `post-web-editor` 등 — 픽스처 @RequestMapping 기준 실제 ID로) + spec §5 1–5 단언(커맨드=Cmd 타입, 참조/중첩/editor happy arm 존재) 작성.
- [ ] Step 2: 실행 `./gradlew :graph-rag-builder:test --tests "*BuilderIntegrationTest"`(Docker+sut.jar 환경). Expected: **FAIL**(미구현). 환경 없으면 단위 RED로 대체.
- [ ] Step 3: commit (RED 기록).

### Task 0.4 (선택): petclinic 베이스라인
- [ ] `.work/run-suites.sh`가 있으면 `petclinic` 실행 → 현재 coveredAppBranches·`post-owners-ownerid-pets-new` 302 도달 여부 기록(Phase 4 비교 기준). 없으면 skip 명시.

---

## Phase 1 — @Valid/@Validated 폼 커맨드 선택

### Task 1.1: selectFormCommand 단위 테스트
**Files:** Modify `graph-rag-builder/src/test/java/io/graphrag/builder/index/EndpointIndexerTest.java`
- [ ] Step 1: `@TempDir` 인라인 — `submit(HelperForm helper, @Valid CmdForm cmd)` → 커맨드 param=CmdForm(FORM, javaType=…CmdForm) 단언. `@Validated` 케이스도 추가(GPT I3). @Valid/@Validated 없으면 첫 후보 폴백 단언.
- [ ] Step 2: 실행. FAIL.
- [ ] Step 3: `EndpointIndexer`에 `selectFormCommand`(FORM 후보 중 @Valid **또는 @Validated** 우선, 없으면 첫 후보) 구현. 기존 `hasValidRequestBody`(JSON gate) 불변.
- [ ] Step 4: PASS + 기존 `indexesControllerFormWithCommandObjectAsFormParam` 회귀 PASS. commit.

---

## Phase 2 — 중첩 객체 평면 점-경로 합성

### Task 2.0: FormFieldBinding record/enum 정의 (선참조 해소 — Sonnet I2)
**Files:** Create `graph-rag-builder/src/main/java/io/graphrag/builder/index/FormFieldBinding.java`
- [ ] Step 1: record `FormFieldBinding(String field, String javaType, Kind kind, String refEntityFqn, String joinColumn, String nestedTypeFqn)` + `enum Kind { SCALAR, REFERENCE, NESTED }`. (런타임 해석 컬럼은 미포함 — static만.)
- [ ] Step 2: 컴파일. commit.

### Task 2.1: FormBodySynthesizer 중첩 재귀 단위 테스트
**Files:** Create `graph-rag-builder/src/main/java/io/graphrag/builder/run/FormBodySynthesizer.java`, Test `.../run/FormBodySynthesizerTest.java`
- [ ] Step 1: 테스트 — `synthesize(shape, bindings, refValues)`에서 NESTED 필드(Address{city,street})는 happy base에 `address.city`,`address.street` 평면 스칼라 키. 빈 POJO/순환 → 스칼라 폴백. 깊이 상한. bindings 비면 전부 스칼라(무회귀).
- [ ] Step 2: 실행. FAIL.
- [ ] Step 3: `FormBodySynthesizer.synthesize` 구현(kind별; NESTED는 nestedTypeFqn의 BodyShape를 prefix 재귀 평면화 — BodyShapeExtractor 재사용; SCALAR/REFERENCE 처리).
- [ ] Step 4: 실행. PASS.
- [ ] Step 5: `EndpointExplorationRunner.happyInput` 배선 — endpoint에 `ParamKind.FORM` 있으면 bodyPart를 `FormBodySynthesizer`로 합성. **PATH+FORM**(`post-web-users-userid-submit`)는 hasPath 분기에서 path/query는 기존 `ReadInputSynthesizer`, FORM bodyPart만 FormBodySynthesizer로 합성 후 merge(GPT I4). formEncode 점-경로 키 통과 단위 테스트.
- [ ] Step 6a: 단위 GREEN `--tests "*FormBodySynthesizerTest"`. commit.
- [ ] Step 6b (통합 환경): NestedFormController E2E arm GREEN 확인(참조/editor arm은 Phase 3/4 후 GREEN).

---

## Phase 3 — 컨버터 레지스트리 + 참조 엔티티 런타임 trial (핵심)

### Task 3.1: ConverterRegistryIndexer 단위 테스트
**Files:** Create `.../index/ConverterRegistryIndexer.java`, Test `.../index/ConverterRegistryIndexerTest.java`
- [ ] Step 1: `@TempDir` 인라인 — `class C implements Formatter<Color>`, `class D implements Converter<String,Brand>` → 전역 convertedTypes={Color,Brand FQN}. 와일드카드 import 픽스처로 제네릭 simple-name 폴백 단언. @InitBinder registerCustomEditor는 **컨트롤러 FQN별 맵**.
- [ ] Step 2: FAIL.
- [ ] Step 3: 구현(Spoon getSuperInterfaces actualTypeArguments→getQualifiedName, bare면 모델 전체 simple-name 교차참조; @InitBinder body의 registerCustomEditor 첫 `T.class` 인자 → controllerFqn별).
- [ ] Step 4: PASS. commit.

### Task 3.2: bindingKind 분류(static) + formBindingIndex surface
**Files:** Modify `IndexResult.java`, `EndpointIndexer.java`, Test `EndpointIndexerTest.java`
- [ ] Step 1: 테스트 — 커맨드 필드 type ∈ convertedTypes(또는 컨트롤러-local editor) 또는 @Entity → kind=REFERENCE(refEntityFqn·joinColumn(@ManyToOne @JoinColumn) static 보유); 컨버터없는 bindable POJO → NESTED(nestedTypeFqn); 스칼라 → SCALAR. **referencedTable/컬럼은 여기서 미해석**(런타임).
- [ ] Step 2: FAIL.
- [ ] Step 3: 구현(IndexResult.formBindingIndex + 분류 — static 메타만).
- [ ] Step 4: PASS. commit.

### Task 3.3: 참조 백업테이블 런타임 해석 + 후보 + reference-aware base + backtrack
**Files:** Modify `EndpointExplorationRunner.java`(run()에 `List<FormFieldBinding> formBindings` 파라미터 추가 — I11), `BuilderCli.java`, Test `.../run/...`
- [ ] Step 1: 단위 — 백업테이블 해석 우선순위(FK[joinColumn→부모]/@Table/camelToSnake 3 분리 케이스, Sonnet I3) → 행 [cat,dog] → 후보 {pk,"cat"}; FormBodySynthesizer가 refValues로 base 참조 키 설정(form body `type=cat`, non-String도 — GPT I2).
- [ ] Step 2: FAIL.
- [ ] Step 3: 러너에 참조 백업테이블 런타임 해석(TableSchema) → SELECT(dialect별 `SELECT pk,name FROM t LIMIT 1`, 빈 테이블 seed 후 재조회) → refValues(name 1순위) → base; 실패 시 PK backtrack(필드당 ≤2, 참조 trial 합 ≤ `min(budgetRequests/2,10)` — Sonnet I7/GPT I7). trial 발행은 doSend 재사용·`discoveredBy="form-ref-trial"` path 귀속(생성 제외). BuilderCli가 formBindingIndex 전달.
- [ ] Step 4: PASS. name-Formatter·id-Converter E2E arm GREEN. commit.

---

## Phase 4 — PropertyEditor 합류 + 마감

### Task 4.1: PropertyEditor(컨트롤러-local) → 참조 trial 재사용
**Files:** Modify `EndpointIndexer.java`(분류 시 컨트롤러-local editor types를 그 컨트롤러 핸들러 필드에만 REFERENCE 적용), Test `EndpointIndexerTest.java`
- [ ] Step 1: 단위 — @InitBinder registerCustomEditor(Sku.class)가 **그 컨트롤러** 핸들러의 Sku 필드만 REFERENCE로, 다른 컨트롤러의 Sku 필드는 NESTED/SCALAR(컨트롤러-local 회귀 가드, Gemini I3/I4).
- [ ] Step 2: FAIL → 구현 → PASS.
- [ ] Step 3: PropertyEditor E2E arm GREEN. 컬렉션 필드 = 스칼라/skip 폴백 단언(비목표 확정). commit.

### Task 4.2: 회귀 + petclinic + 문서 + 리뷰
- [ ] Step 1: 전체 회귀 `./gradlew test` GREEN(기존 단언 전부 유지) — **필수 게이트**.
- [ ] Step 2 (선택): `.work/run-suites.sh petclinic` 있으면 실행 — Pet 커맨드 + type 바인딩 도달 증가(Task 0.4 베이스라인 대비). 없으면 skip 명시.
- [ ] Step 3: `docs/03-graph-rag-builder.md`·`docs/24-…`·spec §9 갱신.
- [ ] Step 4: spec-compliance + code-quality 리뷰 → triage → commit.

---

## Self-review (spec coverage)
- 다중-커맨드(Phase 1) ✓ · 중첩(Phase 2) ✓ · 참조 name/id(Phase 3) ✓ · PropertyEditor(Phase 4) ✓ · 컬렉션 비목표 폴백(Phase 4) ✓ · 안전 폴백(각 분류 default) ✓ · E2E 5종(Phase 0) ✓ · petclinic 회귀(Phase 4) ✓ · 회귀/문서(Phase 4) ✓.
- 타입 일관성: `FormFieldBinding`(builder index, static)·`Kind` enum·`IndexResult.formBindingIndex`·`FormBodySynthesizer.synthesize(shape,bindings,refValues)`·run() `formBindings` 파라미터 전 task 동일.

## 3-모델 계획 리뷰 triage (Sonnet/Gemini/GPT, 전부 needs_revision/approved_with_conditions)
수렴·근거 기반 지적 전부 반영: FormFieldBinding을 builder index 패키지로(순환의존 — 3사 공통 critical) + nestedTypeFqn; 인덱서 static/런타임 분리(Gemini I2); Phase 2.0에 record 선정의(Sonnet I2); containsExactly 갱신(Sonnet I3); formBindingIndex 네이밍(Sonnet I4/GPT I2); @Validated 포함(GPT I3); PATH+FORM happyInput 배선(GPT I4); 전역 data.sql 대신 런타임 seed(Gemini I3/GPT I5); run-suites 선택적·in-repo+gradle 필수(Sonnet I6/I10·GPT I6); backtrack budget/귀속(Sonnet I7/GPT I7); 픽스처 패키지 통일(Sonnet I9); editor 테스트는 Sku 별도 타입(Gemini I4). 거부: 없음(전부 타당).

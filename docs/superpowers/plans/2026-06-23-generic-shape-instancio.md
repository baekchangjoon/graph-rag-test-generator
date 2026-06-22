# 임의 형상 입력 생성 (generic 빌더 + Instancio) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. 체크박스(`- [ ]`) 추적.

**Goal:** 새 바디 형상(깊이3+ 중첩·중첩배열·Map·List<scalar>·Spoon미해결 타입)이 조용히 스킵/오처리되지 않고
generic하게 처리되며, Spoon이 못 푸는 타입은 Instancio 폴백으로 인스턴스화된다.

**Architecture:** generic 재귀 BodyShape/happy 빌더(Spoon) + 배열원소 포함 nestDottedKeys + concolic leaf→dot-path
lifter + Instancio 폴백(sut-jar TCCL classloader) + loud-failure(UnsupportedShape). 변이 시그니처 불변.

**Tech Stack:** Java17/run23, Gradle, Spoon, Jackson, ASM, Instancio(신규), JUnit5/AssertJ, REST-Assured e2e.

## Global Constraints
- 산문 문서 한국어, 코드/식별자/REQ-ID 영어. 커밋 author env vars `baekchangjoon <changjoon.baek@icloud.com>`.
- 결정성(시간·랜덤 금지; Instancio는 seed). 변이 빌더 이름/순서 보존.
- generic 빌더는 **JSON @RequestBody 경로 한정**; form(FormBodySynthesizer)·평면/배열 기존 동작 불변.
- 출처: design `docs/superpowers/specs/2026-06-23-generic-shape-instancio-design.md`, 요구사항 동명 requirements.
- 모듈: `graph-rag-builder/...`, shared-model `shared-model/...`, 샘플 `samples/order-service/...`.

## File Structure
- Modify `shared-model/.../ExplorationReport.java` — `UnsupportedShape` record + `unsupportedShapes` 필드 + `EndpointExploration.exploredPathCount`.
- Modify `index/BodyShapeExtractor.java` — MAX_DEPTH 2→4, generic 재귀(Map/scalar-list/깊이/다형).
- Modify `run/SampleInputSynthesizer.java` — `scalarValue` 추출(package-private static), Map/scalar 합성.
- Modify `index/JsonPaths.java` — (필요시) 배열-원소 nest 헬퍼.
- Modify `run/EndpointExplorationRunner.java` — 배열원소 nestDottedKeys + concolic lifter.
- Create `oracle/ReflectiveBodyInstantiator.java` — Instancio 폴백.
- Modify `cli/BuilderCli.java` — 폴백 seam + UnsupportedShape 기록.
- Create fixtures `samples/order-service/.../{DeepNestedController,CollectionsController,ShadowBodyController}.java` + shadow jar.
- Create e2e `e2e/request-deep.json` 등 + run-e2e 루프; 테스트 다수.

---

### Task 1: loud-failure infra — UnsupportedShape (REQ-008)
**REQ-IDs:** REQ-008
**Files:** Modify `shared-model/src/main/java/io/graphrag/model/ExplorationReport.java`; Test `shared-model/.../ExplorationReportTest.java`(있으면 확장).
- [ ] Step1: 실패 테스트 — `ExplorationReport`에 `record UnsupportedShape(String endpointId, String typeFqn, String reason)` + `List<UnsupportedShape> unsupportedShapes()` 접근자 단언.
- [ ] Step2: 실패 확인 `./gradlew :shared-model:test -q`
- [ ] Step3: 구현 — record 추가(불변·널-가드 List.of() 디폴트), 기존 생성자 호환(편의 생성자 빈 리스트).
- [ ] Step4: `./gradlew :shared-model:test :graph-rag-builder:compileJava -q` PASS.
- [ ] Step5: commit `feat(model): ExplorationReport.UnsupportedShape for loud-failure (REQ-008)`

### Task 2: 깊이 상한 + scalarValue 추출 + 깊이3 재귀 (REQ-001, REQ-010)
**REQ-IDs:** REQ-001(구조부), REQ-010
**Files:** Modify `index/BodyShapeExtractor.java`, `run/SampleInputSynthesizer.java`; Test `index/BodyShapeExtractorGenericTest.java`, 갱신 `BodyShapeExtractorNestedTest.java`.
- [ ] Step1: 실패 테스트 — `BodyShapeExtractorGenericTest#deepNested`: `record Root(Level1 l1)`/`Level1(Level2 l2)`/`Level2(String value,int count)` → `extractFromTypeFlattened` 결과에 `l1.l2.value`,`l1.l2.count` 포함.
- [ ] Step2: 실패 확인.
- [ ] Step3: 구현 — `BodyShapeExtractor` `MAX_NESTING_DEPTH` 2→4. 재귀가 깊이4까지 dot-path. `SampleInputSynthesizer.scalarValue/putScalar`를 **package-private static**으로 추출(기존 호출부 보존). `BodyShapeExtractorNestedTest.nestedDepth_cappedAtMax`를 새 cap에 맞게 갱신(GREEN).
- [ ] Step4: `./gradlew :graph-rag-builder:test --tests '*BodyShapeExtractor*Test' --tests '*SampleInputSynthesizer*Test' -q` PASS.
- [ ] Step5: commit `feat(index): generic deep nesting MAX_DEPTH=4 + scalarValue util (REQ-001,REQ-010)`

### Task 3: Map + List<scalar> 구조·합성 (REQ-003, REQ-004)
**REQ-IDs:** REQ-003, REQ-004
**Files:** Modify `index/BodyShapeExtractor.java`, `run/SampleInputSynthesizer.java`; Test `BodyShapeExtractorGenericTest`.
- [ ] Step1: 실패 테스트 — `mapBody`(`Map<String,String>` → collection-유사 happy 1-entry; non-String key → unsupported 신호), `scalarListElement`(`List<String>` 원소 변이 가능).
- [ ] Step2: 실패 확인.
- [ ] Step3: 구현 — `elementType`/분류기에 Map 탐지(`java.util.Map` + 2 type args; key=get(0),value=get(1)); happy `{"<sampleKey>":<sampleV>}`. non-String key → unsupported. List<scalar> 원소는 기존 collection 경로 + 스칼라 리프.
- [ ] Step4: PASS + 기존 collection 테스트 회귀 GREEN.
- [ ] Step5: commit `feat(index): Map<String,V> + List<scalar> body shapes (REQ-003,REQ-004)`

### Task 4: 배열-원소 nestDottedKeys (REQ-002)
**REQ-IDs:** REQ-002
**Files:** Modify `run/EndpointExplorationRunner.java`(+필요시 `JsonPaths.java`); Test `JsonPathsTest`/integration.
- [ ] Step1: 실패 테스트 — 중첩 DTO 원소를 가진 ArrayNode 바디에서 happy 원소가 `{"nested":{"field":…}}`인지(현재 평면 `{"nested.field":…}`).
- [ ] Step2: 실패 확인.
- [ ] Step3: 구현 — runner의 `nestDottedKeys` 적용을 `baseInput`이 ArrayNode면 각 ObjectNode 원소에도. (헬퍼로 추출 가능.)
- [ ] Step4: PASS + 기존 배열/객체 회귀 GREEN.
- [ ] Step5: commit `fix(builder): nestDottedKeys on array elements for nested-in-collection (REQ-002)`

### Task 5: concolic leaf→dot-path lifter (REQ-005)
**REQ-IDs:** REQ-005
**Files:** Create `oracle/CandidateLifter.java`(순수); Modify `run/EndpointExplorationRunner.java`; Test `CandidateLifterTest`.
- [ ] Step1: 실패 테스트 — `CandidateLifterTest#liftsLeafToDotPath`: candidates `{min→{1}}` + mutableFields `[range.min]` → 결과 `{range.min→{1}}`. 다중 매칭=전 경로. 동일-leaf 한 JoinGuard 두 ref → 분리/폴백.
- [ ] Step2: 실패 확인.
- [ ] Step3: 구현 — `CandidateLifter.lift(InputCandidates, List<BodyField> mutableFields, conjunctions, joinGuards)` 순수 함수. runner의 EndpointTarget 생성 직전 한 곳에서 numeric/strings/reals/Conjunction.atoms/JoinGuard ref 일괄 적용.
- [ ] Step4: PASS + 기존 InputMutator/joinGuard 회귀 GREEN.
- [ ] Step5: commit `feat(builder): concolic candidate leaf->dot-path lifter (REQ-005)`

### Task 6: Instancio 폴백 ReflectiveBodyInstantiator (REQ-006, REQ-007)
**REQ-IDs:** REQ-006, REQ-007
**Files:** Create `oracle/ReflectiveBodyInstantiator.java`; Modify `cli/BuilderCli.java`, `gradle/libs.versions.toml`, `graph-rag-builder/build.gradle.kts`; Test `ReflectiveBodyInstantiatorTest`(+ shadow 테스트 jar/class).
- [ ] Step1: dep — `libs.versions.toml`에 `instancio-core`(`org.instancio:instancio-core` 최신 안정), build.gradle.kts에 추가.
- [ ] Step2: 실패 테스트 — `ReflectiveBodyInstantiatorTest#resolvesShadowType`(테스트 리소스의 precompiled class/jar를 URLClassLoader로 로드→non-null JSON), `#deterministicSeed`(동일 FQN→동일 JSON), `#customJacksonFallsBackUnsupported`(@JsonSerialize 타입→Optional.empty). jar 레이아웃은 plain-jar 케이스로 단위화(fat-jar 추출은 통합).
- [ ] Step3: 실패 확인.
- [ ] Step4: 구현 — `resolve(String fqn, Path sutJar): Optional<ReflectiveBody>`: jar 레이아웃 감지(BOOT-INF?→추출 `<tmp>/grb-instancio/<sha256>/`+`.done` 마커+shutdownHook+size guard; plain→단일 URL) → child `URLClassLoader(parent=app)` → **TCCL set/restore** 동안 `Instancio.of(loadClass(fqn)).withSeed((long)fqn.hashCode()).create()` → 빌더 Jackson 직렬화 → dot-path BodyShape 도출. **ASM 스캔**으로 타깃/필드 `@JsonSerialize/@JsonDeserialize/@JsonTypeInfo` 있으면 `Optional.empty()`(unsupported). 실패 전부 `Optional.empty()`(폭주 금지).
- [ ] Step5: seam — `BuilderCli`에서 `extractFromTypeFlattened` empty(비-JDK)면 `resolve(fqn, config.sutJar())` 호출; empty면 endpoint skip + `unsupportedShapes` 기록(`--no-reflect-instantiate`면 즉시 unsupported).
- [ ] Step6: `./gradlew :graph-rag-builder:test --tests '*ReflectiveBodyInstantiatorTest' --tests '*BuilderCli*' -q` PASS + 전체 compile.
- [ ] Step7: commit `feat(oracle): Instancio reflective body fallback w/ TCCL classloader + custom-Jackson guard (REQ-006,REQ-007)`

### Task 7: fixtures + E2E + exploredPathCount 불변식 (REQ-001/002/003/004 E2E, REQ-009, REQ-011)
**REQ-IDs:** REQ-001,002,003,004,009,011
**Files:** Create fixtures + e2e requests; Modify `run-e2e.sh`, `ExplorationReport.EndpointExploration`(exploredPathCount).
- [ ] Step1: fixtures — `DeepNestedController`(/api/deep 깊이3), `CollectionsController`(/api/prefs Map, /api/tags List<String>), `ShadowBodyController`(/api/shadow, --sut-src 밖 타입; 별도 컴파일 class를 BOOT-INF/lib jar로). (REQ-011 다형 fixture는 단위로 충분하면 생략 가능 — 단위 `polymorphicFirstSubtype`로 검증.)
- [ ] Step2: `exploredPathCount` — `EndpointExploration`에 `int exploredPathCount`(=pathsByEngine 합) 추가.
- [ ] Step3: e2e requests + run-e2e 루프에 deep/prefs/tags(+가능 시 shadow) 추가.
- [ ] Step4: E2E 작성/실행(외부 루프) — happy 중첩/Map/scalar-list 2xx + 변이 path, 명시 엔드포인트 exploredPathCount>1.
- [ ] Step5: commit `test(e2e): generic-shape fixtures + exploredPathCount invariant (REQ-001..004,009)`

### Task 8: 회귀·매트릭스·문서 게이트 (REQ-009 + 전 REQ)
- [ ] Step1: `./gradlew :graph-rag-builder:test :shared-model:test -q` 전체 GREEN.
- [ ] Step2: `bash e2e/run-e2e.sh` GREEN + 생성 테스트 수 비축소 + 명시 엔드포인트 path>1.
- [ ] Step3: 매트릭스 11/11 green, 테스트명 대조.
- [ ] Step4: 문서 동기화(spec/요구사항 차이 역전파).
- [ ] Step5: commit `docs: traceability matrix green + doc sync`

## Self-Review
1. Spec coverage: REQ-001(T2,T7) 002(T4,T7) 003(T3,T7) 004(T3,T7) 005(T5) 006(T6) 007(T6) 008(T1) 009(T7,T8) 010(T2) 011(T7) — 전 매핑.
2. Placeholder: 코드 스텝 구체(일부 테스트 본문은 기존 패턴 참조 — 구현 전 codegraph 확인 명시).
3. Type consistency: `UnsupportedShape`/`exploredPathCount`(T1,T7) ↔ 소비(T6,T8); `scalarValue` 추출(T2)→T3 사용; `CandidateLifter`(T5); `ReflectiveBodyInstantiator.resolve→Optional<ReflectiveBody>`(T6) 일관.
4. 리스크: Instancio classloading(T6)이 최대 — plain-jar 단위 + fat-jar 통합으로 분리, 실패는 unsupported(폭주 금지).

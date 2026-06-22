# 임의 형상 입력 생성 (generic 빌더 + Instancio) 요구사항명세

> 출처(design spec): docs/superpowers/specs/2026-06-23-generic-shape-instancio-design.md
> 완료 정의(DoD): 커버리지 대상(Must + 미연기 Should) 매트릭스 전부 green + 기존 전 모듈·e2e 회귀 GREEN.

## 신규 픽스처 (구현 선행)
- `DeepNestedController`(order-service): `POST /api/deep`, `record Root(Level1 l1)`/`Level1(Level2 l2)`/
  `Level2(String value, int count)` — 깊이 3.
- `CollectionsController`: `POST /api/prefs`(`@RequestBody Map<String,String>`), `POST /api/tags`
  (`@RequestBody List<String>`).
- `ShadowBodyController`: `POST /api/shadow`, `@RequestBody`가 **--sut-src에 없는** 타입(별도 컴파일 jar의
  클래스를 `BOOT-INF/lib`에) — Spoon `Optional.empty()`.

## 요구사항 목록

### REQ-001 — generic 재귀 깊이 중첩(≥3) 구조 + happy
- 유형: Functional · Must
- 설명: 깊이 3+ 중첩 DTO 바디를 dot-path BodyShape로 전개하고 중첩 JSON happy를 합성한다(`MAX_DEPTH=4`).
- 수용기준: Given `/api/deep`(깊이3)를, When 탐색, Then happy `{"l1":{"l2":{"value":…,"count":…}}}` 2xx
  AND 깊은 리프(`l1.l2.value`) 변이가 분기/응답차를 만든다.
- 레벨: E2E

### REQ-002 — 중첩-배열 원소 nestDottedKeys
- 유형: Functional · Must
- 설명: `List<NestedDTO>` 바디의 각 ObjectNode 원소에 `nestDottedKeys` 적용 → 원소가 중첩 JSON.
- 수용기준: Given 중첩 DTO 원소를 가진 컬렉션 바디를, When happy/변이 합성, Then 원소가
  `{"nested":{"field":…}}` 구조(평면 `{"nested.field":…}` 아님)이고 역직렬화 2xx.
- 레벨: integration/E2E

### REQ-003 — Map<String,V> 바디
- 유형: Functional · Must
- 설명: `Map<String,V>` 바디를 1-entry happy + entry-값 변이로 처리. non-String 키는 unsupported 기록.
- 수용기준: Given `/api/prefs`(`Map<String,String>`)를, When 탐색, Then happy 1-entry 2xx + ≥1 변이.
- 레벨: E2E

### REQ-004 — List<scalar> 원소
- 유형: Functional · Must
- 설명: `List<scalar>` 바디의 원소[0] 값 변이.
- 수용기준: Given `/api/tags`(`List<String>`)를, When 탐색, Then happy + 원소 변이 ≥1.
- 레벨: E2E

### REQ-005 — concolic leaf명→dot-path lifter
- 유형: Functional · Must
- 설명: runner의 EndpointTarget 생성 직전, `InputCandidates`(numeric/strings/reals) 키 + `Conjunction.atoms`
  + `JoinGuard` ref를 leaf→dot-path로 일괄 승격. 다중 매칭=전 경로, 단 동일-leaf가 한 JoinGuard의 두
  ref면 분리 적용/폴백.
- 수용기준: Given 중첩 숫자 필드(`range.min`)의 concolic 후보 `min`을, When lift, Then 변이가 `range.min`
  경로에 적용된다(단위).
- 레벨: unit

### REQ-006 — Instancio 폴백(sut-jar 리플렉션)
- 유형: Functional · Must
- 설명: Spoon 미해결 타입을 jar-레이아웃 감지→추출→child URLClassLoader(TCCL)→Instancio(seed 결정적)로
  인스턴스화→Jackson 직렬화→BodyShape/happy 도출. `--no-reflect-instantiate`로 비활성.
- 수용기준: Given `/api/shadow`(Spoon `empty`)를, When resolve, Then non-null happy JSON 생성 AND 동일
  FQN→동일 JSON(결정적 스냅샷).
- 레벨: unit/integration

### REQ-007 — cross-loader Jackson 안전(커스텀 직렬화 폴백)
- 유형: Non-functional (correctness) · Must
- 설명: 타깃/필드에 커스텀 Jackson(`@JsonSerialize` 등)이 있으면 잘못된 JSON 생성 대신 `unsupported-shape`로
  폴백(ASM 감지).
- 수용기준: Given 커스텀 직렬화 타입을, When resolve, Then JSON을 만들지 않고 unsupported 기록.
- 레벨: unit

### REQ-008 — loud-failure (UnsupportedShape)
- 유형: Functional · Must
- 설명: 미처리 형상을 `ExplorationReport.unsupportedShapes`(신규 record)에 기록. DroppedPath 미재사용.
- 수용기준: Given 진짜 미지원 형상(non-String key Map 등)을, When 탐색, Then `unsupportedShapes`에
  (endpointId, typeFqn, reason) 기록 AND 조용히 스킵되지 않음.
- 레벨: unit/integration

### REQ-009 — 회귀 불변식 + superset
- 유형: Non-functional (regression) · Must
- 설명: `EndpointExploration.exploredPathCount` 노출; 명시 엔드포인트(batch/deep/tags/prefs)에 `>1` 단언.
  전 모듈·e2e GREEN, 기존 커버리지 superset.
- 수용기준: Given 기존+신규 e2e, Then 전부 GREEN AND 명시 엔드포인트 exploredPathCount>1 AND 생성
  테스트 수 비축소.
- 레벨: E2E/regression

### REQ-010 — 깊이 상한 변경 + 기존 테스트 갱신
- 유형: Functional · Must
- 설명: `MAX_NESTING_DEPTH` 2→4. `BodyShapeExtractorNestedTest.nestedDepth_cappedAtMax`를 새 cap에 맞게
  갱신·GREEN.
- 수용기준: Given 깊이>4 체인을, When extract, Then cap에서 리프 종료 AND 기존 nested 테스트 GREEN.
- 레벨: unit

### REQ-011 — 다형 best-effort
- 유형: Functional · Should
- 설명: abstract/interface 바디는 `@JsonSubTypes` 첫 구상 서브타입으로, 없으면 Instancio, 그래도 안 되면
  unsupported.
- 수용기준: Given `@JsonSubTypes` 가진 추상 바디를, When 처리, Then 첫 서브타입으로 happy 합성.
- 레벨: unit · (미연기 Should — fixture 있으면 검증, 없으면 단위 only)

## 추적 매트릭스
| REQ-ID | 요구사항 | 수용 테스트 | Level | Status |
|--------|----------|-------------|-------|--------|
| REQ-001 | 깊이3 중첩 | E2E `DeepPostTest`(happy `{"l1":{"l2":{"value":…,"count":1}}}` 200; `count:-1`→422; `remove-l1.l2.value`→400) + `BodyShapeExtractorGenericTest#deepNested` | E2E+unit | 🟢 green |
| REQ-002 | 중첩-배열 원소 nest | `JsonPathsTest#nestDottedKeys_perArrayElement` | unit | 🟢 green |
| REQ-003 | Map 바디 | E2E `PrefsPostTest`(`{"sampleKey":…}` 200; `{}`→400) + `BodyShapeExtractorTest#mapBody_stringKey/nonStringKey_empty` | E2E+unit | 🟢 green |
| REQ-004 | List<scalar> 원소 | E2E `TagsPostTest`(`["sample"]` 200; `[]`→400) + `SampleInputSynthesizerTest#scalarList_alreadyWorks` | E2E+unit | 🟢 green |
| REQ-005 | concolic lifter | `CandidateLifterTest`(8 tests: leaf→dot-path, fan-out, tuple unique-only 등) | unit | 🟢 green |
| REQ-006 | Instancio 폴백 | `ReflectiveBodyInstantiatorTest#{resolvesPlainJarType,deterministicSeed,disabledReturnsEmpty}` | unit | 🟢 green |
| REQ-007 | cross-loader Jackson 안전 | `ReflectiveBodyInstantiatorTest#{customJacksonFallsBackToEmpty,customJacksonOnGetterFallsBackToEmpty}` | unit | 🟢 green |
| REQ-008 | UnsupportedShape 기록 | `JsonRoundTripTest`(record) + BuilderCli seam 기록(reflect 실패/비활성) | unit/integration | 🟢 green |
| REQ-009 | 회귀 superset | 전 모듈 497 + e2e 74(66→74) GREEN + `EndpointExploration.exploredPathCount` | E2E/regression | 🟢 green |
| REQ-010 | 깊이 상한 갱신 | `BodyShapeExtractorNestedTest#nestedDepth_cappedAtMax`(7-deep, cap=4) | unit | 🟢 green |
| REQ-011 | 다형 best-effort | (미구현 — Should 연기) | unit | 🔵 deferred |

Coverage: 10/10 green (100%) — 대상 = Must 10. REQ-011(다형, Should)은 fixture 부재로 **연기(🔵)**,
분모 제외 — 후속(Instancio가 interface도 best-effort 처리하므로 폴백으로 부분 커버). 제외: REQ-011.

## 커버리지 규칙
- 분모 = Must(10). REQ-011(Should)은 연기(🔵)로 분모 제외. → 10/10 green (100%).
- 이중루프: REQ-001/003/004/009의 E2E 먼저 red, 단위 TDD로 나머지 드라이브 → green. (PR 전 매트릭스 green 달성.)

## 자기검토
1. 고아 행위 없음 — spec AC-1~6 + 계약(cross-loader 안전·loud-fail)이 모두 REQ로.
2. 원자성 — 구조(001~004)·lifter(005)·폴백(006/007)·기록(008)·회귀(009) 분리.
3. 수용기준 완비 — GWT + 레벨 명시.
4. 커버리지 규칙 — 분모(11)·제외(없음).

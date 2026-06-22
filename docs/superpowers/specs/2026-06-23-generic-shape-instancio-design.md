# 임의 형상 입력 생성 — generic 재귀 빌더 + Instancio 폴백 — 설계 (v2)

- 일자: 2026-06-23
- 브랜치: feat-generic-shape-instancio
- 배경: [`2026-06-22-array-nested-mutation-joinguard-design.md`](2026-06-22-array-nested-mutation-joinguard-design.md)
  (배열/중첩/조인가드, 머지됨). 본 작업은 "새 바디 형상마다 케이스 추가 → 조용히 실패/스킵" *반복 패턴* 을
  구조적으로 제거한다.
- 결정(인박스·사용자 명시): **C 하이브리드 + Option 1 + Instancio 1차 포함**.
- 리뷰: 3-벤더 design-doc 리뷰(Sonnet×2 + Cursor) 전원 needs_revision → 본 v2가 반영본.
- 스코프 밖(후속): LLM 값-오라클, OpenAPI 입력 소스.

## 문제 (현황 정합)

**이미 해결됨**: 최상위 `List<DTO>`(flat 원소) 변이, 깊이 2 중첩 DTO, field-to-field 조인가드 — 직전 머지.

**미해결(본 작업 대상)**:
1. **깊이 3+ 중첩 / 중첩-배열 원소**: `MAX_NESTING_DEPTH=2`(BodyShapeExtractor:27) 상한 + 배열 원소에
   `nestDottedKeys` 미적용(runner는 `baseInput instanceof ObjectNode`일 때만 호출) → 중첩 DTO를 *원소로*
   가진 `List<NestedDTO>`는 원소가 평면 점-키(`{"address.city":…}`)로 남아 역직렬화 실패.
2. **`Map<K,V>` 바디·`List<scalar>` 원소 변이**: 미지원.
3. **Spoon 미해결 타입**(shadow/외부/opaque generic): shape `Optional.empty()` → 엔드포인트 *조용히 스킵*.
4. **concolic 중첩 값 갭**: `InputCandidates`는 필드 **leaf명**(`Map<String,Set<Long>>`, 예 `min`) 키 →
   중첩 경로(`range.min`)·중첩 조인가드 미도달.
5. **silent-skip 일반**: 위 미처리들이 *조용히* 스킵/no-op(report 미기록) → 회귀로 안 잡힘.

## 범위
- **포함**: (1) generic 재귀 구조 모델·happy 빌더(중첩 임의깊이·중첩배열·`Map`·`List<scalar>`·다형 best-effort).
  (2) generic 변이 순회(+배열 원소 nestDottedKeys). (3) concolic leaf명→dot-path lifter(Conjunction/JoinGuard
  포함). (4) Instancio 폴백(sut-jar 리플렉션). (5) loud-failure 백스톱(unsupported-shape 기록 + 회귀 불변식).
- **제외(YAGNI)**: LLM/OpenAPI 입력 소스, 컬렉션 원소별 *서로 다른* 변이(element[0] 대표), 다형 완전 해결,
  분석환경 인스턴스화(A2 — 아래 §변경 4에서 명시적 *defer*).

## 설계

### 변경 1 — generic 재귀 구조 모델·happy 빌더

`BodyShapeExtractor`의 평탄화와 `SampleInputSynthesizer` 합성을 *완전 재귀* 로 일반화. 한 재귀 분류기:

- **scalar/enum/시간** → dot-path 리프(BodyField + javaType). happy 값: `SampleInputSynthesizer`의
  `scalarValue(...)`를 **package-private static 유틸로 추출**해 재사용(현재 private:155/160 → 시그니처
  `scalarValue(String javaType, List<FieldConstraint> cons, String fieldName)`로 승격, 기존 호출부 보존).
- **DTO(record/class)** → 컴포넌트별 재귀(`parent.child`), **경로별 cycle guard**(스택-로컬 Set).
- **컬렉션(`List/Set/Collection/Iterable`/배열)** → 원소 타입 재귀; happy=1-element. 원소 DTO면 중첩 dot-path,
  scalar면 스칼라 리프.
- **`Map<K,V>`** → 탐지: `type.getQualifiedName()=="java.util.Map" && actualTypeArguments.size()==2`
  (key=`get(0)`, value=`get(1)`). happy=1-entry(`{"<sampleKey>": <sampleV>}`), 변이는 entry 값. 키 타입이
  String 아니면 `unsupported-shape` 기록(§변경 5).
- **다형/abstract/interface** → Spoon이 `@JsonSubTypes`의 첫 구상 서브타입을 찾으면 그걸로 재귀; 없으면
  Instancio(§변경 4) 위임; 그래도 안 되면 `unsupported-shape`.
- **깊이 상한**: `MAX_DEPTH`를 **2→4 상향**(현실 DTO 충분 + 폭주 한계; depth=0이 첫 컴포넌트 → 최대 5
  세그먼트). **회귀**: `BodyShapeExtractorNestedTest.nestedDepth_cappedAtMax`가 depth=2를 인코딩 →
  새 cap에 맞게 단언 갱신(GREEN 유지). 깊이/ cycle 초과는 그 경로를 *리프로 정상 종료*(silent 아님 —
  의도된 종료, unsupported 아님).
- 미해결 타입 → §변경 4 위임.

happy JSON 중첩: dot-path 리프 → 중첩 객체는 `JsonPaths.nestDottedKeys`(변경 2가 배열 원소까지 확장).

### 변경 2 — generic 변이 순회 (+ 배열 원소 nestDottedKeys)

- 변이 경로 = `BodyShape` dot-path 필드(변경 1이 임의 형상까지 채움). Option 1 — 별도 트리-walk 불필요.
- `EndpointExplorationRunner`에서 happy 바디 nest 적용을 **배열에도** 확장: `baseInput`이 ArrayNode면 각
  ObjectNode 원소에 `JsonPaths.nestDottedKeys(element)` 적용(현재는 root ObjectNode만 → I5 버그 수정).
  (이것이 `List<NestedDTO>` 원소의 평면-키 역직렬화 실패를 고친다.)
- `applyToBody`가 중첩 배열·배열-of-중첩도 element[0] 대표로 재귀 적용. 비-ObjectNode 원소 가드.
- 어떤 단계도 컨테이너 타입에 `return`(silent no-op) 하지 않음 — 미처리는 §변경 5로 *기록*.

### 변경 3 — concolic leaf명→dot-path lifter

- **위치(고정)**: `EndpointExplorationRunner.run`에서 `mutableFields` 확정 직후·`EndpointTarget` 생성
  **직전 한 곳**. `candidates.numeric()/strings()/reals()` 키 + `Conjunction.atoms().fieldRef` +
  `JoinGuard.left/rightRef`를 leaf→dot-path로 **일괄 승격**(전 채널 일관).
- **매핑**: leaf명 == dot-path 마지막 세그먼트면 그 경로로. **다중 매칭** → 전 경로에 적용. **단,
  같은 leaf가 한 JoinGuard의 두 ref로 동시에 들어가면**(예 `startDate.value < endDate.value`) 동일 값 적용이
  arm을 깨므로 그 경우 **각 경로를 분리 적용**(동시 세팅 금지) — 안 되면 그 가드는 *기존 leaf 매칭으로
  폴백 + known-limitation 기록*. 매칭 0 → 최상위 평면 필드(기존 동작).
- 결정적(정렬). 평면 케이스 회귀 0(leaf==최상위명=자기 자신).

### 변경 4 — Instancio 폴백 (sut-jar 리플렉션)

신규 `ReflectiveBodyInstantiator`. **seam**: `extractFromTypeFlattened`가 비-JDK 타입에 `Optional.empty()`면
`BuilderCli`가 `resolve(fqn, sutJar)`(→`Optional<ReflectiveBody>`{BodyShape + happy JSON 템플릿}) 호출;
그래도 empty면 endpoint 스킵 + `unsupported-shape` 기록.

- **jar 레이아웃 decision table**:
  | sut-jar | 동작 |
  |---|---|
  | Spring Boot fat(`BOOT-INF/` 존재) | `BOOT-INF/classes` + `BOOT-INF/lib/*.jar` 추출 → URLClassLoader |
  | plain jar | 단일 classes URL로 로더 |
  | 추출/로딩 실패 | `unsupported-shape` 기록(A2는 defer) |
- **추출 lifecycle**: `<java.io.tmpdir>/grb-instancio/<sha256(jar)>/`에 1회 추출, `.done` 마커로 재추출 skip,
  JVM `addShutdownHook` 정리. `BOOT-INF/lib` 총량이 임계(기본 500MB) 초과면 경고 + `unsupported`.
- **classloader topology(중요)**: child `URLClassLoader([classes-dir, lib jars…], parent=빌더 app 로더)`.
  Instancio 호출 동안 **그 child 로더를 thread context classloader(TCCL)로 설정**해 Instancio가 전이 타입을
  child에서 해석하게 함(parent=platform이면 전이 타입 ClassNotFound — I1). try/finally로 TCCL 복원.
- **cross-loader Jackson 안전성(중요)**: SUT가 타깃 타입(또는 전이 필드)에 커스텀 Jackson을 걸면 빌더
  Jackson이 잘못된 JSON을 *조용히* 낼 수 있음(I1). → 추출된 `BOOT-INF/classes`를 **ASM로 스캔**해
  `@JsonSerialize/@JsonDeserialize/@JsonTypeInfo` 또는 Module 등록이 타깃/필드에 있으면 **`unsupported-shape`
  로 폴백**(잘못된 JSON 생성 금지). (ASM 스캔 인프라는 ConcolicOracle/BranchCoverageAnalyzer에 이미 존재.)
- **인스턴스화/직렬화**: `loader.loadClass(fqn)` → `Instancio.of(clazz).withSeed((long) fqn.hashCode()).create()`
  (seed 결정적; 같은 타입 = 같은 스켈레톤, 도메인 값은 하류서 덮음) → 빌더 Jackson 직렬화 → JSON에서
  dot-path BodyShape 도출(트리 walk). 의존: `org.instancio:instancio-core`(libs.versions.toml + build.gradle.kts).
- **활성화**: 자동(Spoon 미해결 시). `--no-reflect-instantiate`로 비활성(→ `unsupported` 기록).
- **A2(분석환경 인스턴스화)**: **defer**. 빌더-JVM 경로 실패 시 `unsupported` 기록하고 진행. A2(러닝 SUT
  프로세스 내 인스턴스화 엔드포인트)는 후속 spec.

### 변경 5 — loud-failure 백스톱

- `ExplorationReport`(shared-model)에 **신규** `record UnsupportedShape(String endpointId, String typeFqn,
  String reason)` + `List<UnsupportedShape> unsupportedShapes` 필드 추가. **DroppedPath 재사용 금지**
  (그건 HTTP status 필드 의미 — I3). 미처리 형상은 여기에 기록(조용히 스킵 금지). `BuilderCli` skip 분기도 경유.
- **회귀 불변식**: `EndpointExploration`에 `int exploredPathCount`(=`pathsByEngine` 합) 노출 → e2e가
  명시 엔드포인트(`post-api-orders-batch`(List<DTO>), 신규 `post-api-deep`(깊이3), `post-api-tags`(List<scalar>),
  `post-api-prefs`(Map))에 대해 `exploredPathCount > 1` 단언 → silent no-op이 *테스트로* 실패.

### 데이터 흐름
```
SUT 타입 ─Spoon 해결?─┬─yes→ generic 재귀 BodyShape(dot-path+javaType) ─────────┐
                      └─no → ReflectiveBodyInstantiator: jar 레이아웃→추출→child URLClassLoader
                              (TCCL)→Instancio(seed)→[ASM 커스텀-Jackson 스캔: 있으면 unsupported]
                              →빌더 Jackson 직렬화→dot-path BodyShape ──────────┤
concolic InputCandidates(leaf명) ─lifter(runner, 일괄)→ dot-path ───────────────┤→ mutableFields/happy
       │                                                                         │
   generic happy 빌더 + nestDottedKeys(객체 root & 배열 원소) → nested JSON happy │
       │                                                                         │
   explorer/fuzzer: applyToBody(중첩/배열 generic)+JsonPaths 변이 ─미처리?→ report.unsupportedShapes
       │
   실행+JaCoCo → 커버리지/SQL → 결정적 JUnit
```

## E2E / 수용 기준 (요구사항명세에서 REQ-ID)

신규 fixture(order-service):
- `DeepNestedController`: `record Root(Level1 l1)`/`Level1(Level2 l2)`/`Level2(String value, int count)` —
  깊이 3 (AC-1).
- `CollectionsController`: `@RequestBody Map<String,String> prefs`(Map) + `List<String> tags`(List<scalar>) (AC-2).
- `ShadowBodyController`: `@RequestBody`가 **--sut-src에 없는** 타입(컴파일/런타임 의존 jar의 클래스 또는
  별도 컴파일 jar를 `BOOT-INF/lib`에) — Spoon `Optional.empty()` 확인 (AC-3).

수용 기준:
- **AC-1(깊이3 중첩)**: happy 중첩 JSON `{"l1":{"l2":{"value":…,"count":…}}}` 2xx + 깊은 리프 변이 분기 도달.
- **AC-2(Map/List<scalar>)**: 두 형상 happy 합성 + ≥1 변이(원소/엔트리 값).
- **AC-3(Instancio 폴백)**: ShadowBody 타입을 Instancio로 happy 인스턴스화(빌더 단위/통합; jar 로딩 포함).
  결정성: 동일 FQN→동일 JSON 스냅샷 단위 테스트.
- **AC-4(concolic 중첩 lifter)**: 중첩 숫자 필드 concolic/조인가드 값이 그 dot-path에 적용(단위).
- **AC-5(loud-failure)**: 진짜 미지원(예: non-String key Map, 커스텀-Jackson 타입)이 `unsupportedShapes`에
  기록되고 조용히 스킵되지 않음(단위/통합).
- **AC-6(회귀)**: 전 모듈 + e2e GREEN, 평면/배열/깊이2 기존 동작·커버리지 superset.

## 리스크
- **classloading(최대)**: fat-jar 추출 + child URLClassLoader + **TCCL** + cross-loader Jackson(커스텀
  직렬화 ASM 감지로 unsupported 폴백) + 전이 의존. 실패는 `unsupported` 기록(폭주 금지). A2는 defer.
- **신규 의존 Instancio**: seed-결정적 → 불변식 호환.
- **lifter 모호성/joinGuard arm**: 동일-leaf 동시세팅 회피 + known-limitation 기록.
- **공유 컴포넌트 회귀**(직전 교훈): generic 빌더는 JSON @RequestBody 경로 한정, form은 FormBodySynthesizer
  불변. AC-6로 가드.
- **깊이 2→4 변경**: nestedDepth_cappedAtMax 갱신(명시).

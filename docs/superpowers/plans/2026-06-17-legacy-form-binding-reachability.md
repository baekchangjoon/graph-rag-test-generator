# 레거시 @Controller 폼 바인딩 도달성 (범용) — spec (§10 triage 반영본)

작성: 2026-06-17 · 브랜치: worktree-feat-form-command-formatter · 기반: B1(negative-validation) 머지 후

## 1. 문제 / 동기
빌더는 `@Controller` 폼 핸들러를 **커버리지 전용**으로 탐색(폼-urlencoded 발행)한다. 그러나 현재 폼 입력 합성은
**평면 스칼라 필드만** 처리하므로, 다음 레거시 Spring MVC 바인딩 패턴 폼은 **바인딩 실패 → happy arm 미진입**:

1. **다중 커맨드 객체**: `processCreationForm(Owner owner, @Valid Pet pet, …)` — 인덱서가 **첫** FORM 파라미터(Owner)를
   오선택 → 실제 커맨드(Pet) 미바인딩.
2. **참조 엔티티 — name-조회 Formatter**: `Pet.type`(PetType) ← `PetTypeFormatter.parse(name)` 이름 조회.
3. **참조 엔티티 — id-조회 Converter / Spring Data**: `Converter<String,E>`(PK 조회) 또는 Spring Data
   `DomainClassConverter`(엔티티를 id로 변환). name이 아닌 **PK 토큰** 요구.
4. **중첩 객체 바인딩**: 커맨드 필드가 컨버터 없는 POJO일 때 Spring은 `field.sub=value` 점-경로로 바인딩.
5. **레거시 PropertyEditor**: `@InitBinder`의 `registerCustomEditor(T.class, …)` — 컨트롤러-local 변환.

(2)·(3)·(5)는 "참조 토큰" 메커니즘(Phase 3)으로 통합 커버(값은 런타임 trial로 결정 — name vs PK). petclinic은
(1)·(2)만 운동 → (3)·(4)·(5)는 **in-repo 픽스처**로만 검증(§5).

## 2. 목표 / 비목표
- **목표**: `@Controller` 폼 커맨드의 각 필드를 **바인딩 종류**에 맞는 request 파라미터로 합성해 데이터 바인딩을
  성공시키고 폼 핸들러 happy arm에 진입한다. 5개 패턴(다중-커맨드, 참조 name/id, 중첩, PropertyEditor)을
  **정적 분류 + 런타임 trial** 조합으로 커버.
- **안전 폴백(핵심·회귀 0)**: 바인딩 종류 미확정·테이블 미해석·후보 전부 실패 시 **기존 동작(스칼라/skip)으로
  폴백**. "추가 도달만, 회귀 0."
- **비목표**:
  - 폼 **테스트 생성** — 커버리지 전용 유지(`Generator`가 `ParamKind.FORM` skip).
  - 임의 `parse`/`setAsText` **정적 해석** — 값은 런타임 trial. 정적 분석은 "어떤 타입이 변환되는가"까지만.
  - **컬렉션 인덱스 바인딩**(`field[0].sub`) — **v1 비목표 확정**(분류기가 컬렉션 필드를 스칼라/skip 폴백; 후속 분리). (GPT I6)
  - **다중 추가-PATH 정밀 FK 시드**(`/owners/{ownerId}/pets/{petId}/edit`) — 별도 후속(a-2, 기존 한계 계승).
  - 뷰 렌더링 단언, @SessionAttributes·복합 path 바인딩, non-DB PropertyEditor(날짜/통화 등 DB 미백업 토큰 — trial
    실패 시 skip). (Sonnet I7)

## 3. 접근
**바인딩 종류 분류 → 종류별 합성** (FORM 커맨드 필드별):

| 종류 | 판정 | 파라미터 | 값 |
|---|---|---|---|
| 스칼라 | String/숫자/Boolean/시간/enum | `field=v` | 현행 |
| 참조 | 타입 ∈ convertedTypes(컨트롤러 스코프 적용) ∪ @Entity | `field=token` | 백업행 **PK·name 후보 → 런타임 trial** |
| 중첩 | 바인딩가능 하위필드 보유 & 비-참조 & 비-스칼라 | `field.sub=v` (평면 점-경로 재귀) | 재귀 합성 |
| 컬렉션 | List/Set\<Y> | — | 비목표 → 스칼라/skip 폴백 |

### 3.1 정적 컨버터 레지스트리 (`ConverterRegistryIndexer`, Spoon noClasspath)
- 전역(global) 등록원 → `Set<String> convertedTypes`(FQN):
  - `class … implements org.springframework.format.Formatter<T>`
  - `class … implements …converter.Converter<String,T>`(또는 `Converter<?,T>`)
- 컨트롤러-local 등록원 → `Map<String controllerFqn, Set<String> types>`:
  - `@InitBinder` 메서드 body의 `*.registerCustomEditor(T.class, …)` 호출 → 그 컨트롤러에만 적용. (Gemini I3)
- **제네릭 타입 인자 T 해석**(noClasspath): `getActualTypeArguments`의 `getQualifiedName()`을 1차, bare simple-name이면
  Spoon 모델 전 타입을 simple-name으로 교차참조, 그래도 모호하면 skip+warn(EndpointIndexer의 simple-name 폴백 패턴 계승). (Sonnet I1)
- `@Entity` 타입: 컨버터 미감지여도 **참조 후보로 best-effort 취급**(Spring Data id 변환 가정 — 단 매칭 repository
  없으면 id trial 실패, name trial만 성공 가능; 불확실성 인정). (Sonnet I6)
- 산출: `IndexResult.formBindingIndex`(아래 §4)에 surface(B1 `validBodyEndpointIds`와 동형).

### 3.2 참조 토큰 런타임 trial (임의 parse 우회 — name/id 통합)
- **백업 테이블 해석 우선순위**(결정적): (1) 커맨드의 `@ManyToOne @JoinColumn(name=fk)` → 스키마 `TableSchema`의
  FK 부모 테이블(가장 신뢰 — 런타임 스키마 기반) → (2) 참조 엔티티의 `@Entity`/`@Table(name)` → (3)
  `camelToSnake(simpleClassName)` 최후 폴백. 미해석이면 빈 후보 → 필드 skip(폴백). (Sonnet I2)
- **DB read는 러너에서**(static 합성기 아님 — Connection 보유; Gemini I2 순환참조 해소): `EndpointExplorationRunner`가
  exploration 직전 백업 테이블의 기존 행을 SELECT(없으면 시드 후 재조회). 후보 = **{PK 값, name-류 문자열 컬럼 값}**.
  - 다중 dialect: `DbConfig.Type`(POSTGRES/MYSQL/MARIADB)별 `SELECT <pk>,<name> FROM <table> LIMIT 1` (기존
    `Seeds`/`SchemaExtractor` dialect 패턴 재사용; schema=public 등 기존 가정 계승). 빈 테이블이면 default 행 시드. (GPT I5)
  - name-류 컬럼 선택: 컬럼명 `name` 우선 → 없으면 첫 non-PK 문자열(CHAR/TEXT) 컬럼. (R2 결정성)
- **reference-aware happy base**(GPT I2/I4): 합성기가 참조 필드 값을 **직접** happy base에 설정(1순위 후보=name).
  단일필드 string mutation에 의존하지 않음(참조 필드 javaType≠String이라 `InputMutator.constraintDirected`의
  String-only stringCandidates 경로로는 mutation 미생성). 모든 참조 필드를 base에서 동시에 유효화 → 다필드 폼도 1회에
  바인딩. base가 실패하면 필드별 2순위 후보(PK)로 backtrack(필드당 후보 ≤2, 합 ≤budget).

### 3.3 중첩 평탄화
- 참조도 스칼라도 아닌 POJO 필드 = 중첩. 그 bodyShape를 재귀로 풀어 **평면 점-경로 스칼라 키**(`prefix.sub=scalar`)로
  happy base에 직접 emit. `formEncode`는 이미 비-스칼라(객체/배열) 폼 필드를 드롭하므로(현행), 중첩은 반드시 평면
  점-경로 스칼라로 내보낸다(중첩 ObjectNode를 두지 않음). (Gemini I1 정확판)
- 가드: 바인딩가능 하위필드가 없거나(빈 POJO) 순환참조면 중첩 처리 포기 → 스칼라/skip 폴백. 깊이 상한 + 방문 타입 집합. (Gemini I4, R3)

### 3.4 폼 커맨드 선택 (`EndpointIndexer`)
- **신규 헬퍼** `selectFormCommand(method, model)`: FORM 후보(비-@RequestBody, bodyShape 보유) 중 `@Valid`/`@Validated`
  붙은 것 우선, 없으면 현 휴리스틱(첫 후보). 기존 `hasValidRequestBody`(JSON gate)는 **건드리지 않음**. (Sonnet I4, GPT I7)

## 4. 컴포넌트
- `ConverterRegistryIndexer` (신규): §3.1 수집.
- **`FormFieldBinding` 메타데이터 캐리어**(GPT I3): `BodyShape.BodyField`를 확장하거나 별도 `FormBindingIndex`에
  `{fieldName, fieldType, bindingKind(SCALAR/REFERENCE/NESTED), referencedTable, joinColumn, refPkColumn, refNameColumn}`
  보유 → 인덱서가 산출, 러너/합성기가 소비. `IndexResult.formBindingIndex`로 surface.
- `EndpointIndexer.extractParams`: `selectFormCommand`(@Valid 우선) + 커맨드 필드의 bindingKind 분류(@ManyToOne/@JoinColumn,
  @Table, convertedTypes 참조).
- `FormBodySynthesizer`(신규 또는 `SampleInputSynthesizer` 확장): bindingKind별 happy base 합성 — 스칼라(현행), 참조(후보
  주입 지점 표시), 중첩(점-경로 재귀).
- `EndpointExplorationRunner`: 참조 필드 백업행 SELECT/seed → 후보로 base 값 설정 + backtrack trial.
- `formEncode`/`bodyOnly`: 점-경로 키 보존 확인(bodyOnly는 param명만 제거하므로 점-경로 키 무영향; formEncode 비-스칼라
  드롭 규칙은 §3.3로 회피).

## 5. E2E / 수용 테스트 (정의된 done)
**outer-loop (먼저 RED) — in-repo `@Controller` 픽스처**(order-service, B1 SignupController 정신). 각 폼은 **happy arm이
올바른 파라미터 합성일 때만 도달**(오합성 시 바인딩 실패 arm). `BuilderE2eTest` 단언(메커니즘 5종):

1. **다중-커맨드**: `(HelperObj, @Valid CmdObj)` → 커맨드=CmdObj 인덱싱(FORM 타입·javaType 단언) + CmdObj 필드 바인딩 happy arm.
2. **참조-name-Formatter**: 엔티티 필드 ← `Formatter<E>`(이름 조회) → 유효 name 토큰 바인딩 성공 arm.
3. **참조-id-Converter**: 엔티티 필드 ← `Converter<String,E>`(PK 조회) → 유효 id 토큰 바인딩 성공 arm.
4. **중첩 POJO**: 커맨드의 중첩 객체 필드 → `nested.sub` 점-경로 바인딩 성공 arm.
5. **PropertyEditor**: `@InitBinder registerCustomEditor`로 바인딩되는 (DB-backed) 필드 → 유효 토큰 arm; **다른
   컨트롤러의 동일 타입 필드는 중첩으로 처리됨**(컨트롤러-local 스코프 회귀 가드). (Gemini I3)
- 각 가드: 합성 미적용으로 되돌리면 바인딩 실패 arm만 → FAIL. arm 구분 = branchesTaken + status(302)(B1 폼 패턴 계승).

**outer-loop (실 SUT 회귀 — 로컬 게이트, CI 아님)**: `.work/run-suites.sh petclinic` — **이 스크립트는 메인 체크아웃의
로컬 하니스(repo 미커밋)** 이므로 CI가 아닌 **로컬 수동 회귀**다(GPT I1 정직성). petclinic이 없는 환경에선 in-repo
픽스처(위 5종)가 CI 게이트. 단언: `post-owners-ownerid-pets-new`가 커맨드=Pet로 인덱싱 + `type=<유효 PetType 이름>`
바인딩 성공 arm(302 redirect) 도달, coveredAppBranches 증가.

**inner-loop (단위 TDD)**:
- `selectFormCommand`: 다중 후보에서 @Valid 선택 / @Valid 없으면 첫 후보 폴백(무회귀).
- `ConverterRegistryIndexer`: Formatter<T>·Converter<String,T> 전역 수집 + 제네릭 simple-name 폴백(와일드카드 import 픽스처) + @InitBinder registerCustomEditor 컨트롤러-local 수집.
- **백업 테이블 해석**(분리 테스트, Sonnet I3): (a) @ManyToOne @JoinColumn(type_id) + FK[type_id→types] → "types"; (b) FK 없고 @Table(name) → 그 이름; (c) 둘 다 없으면 camelToSnake 폴백.
- **참조 후보 산출**: 백업 테이블 행 [cat,dog] → 후보 {pk, "cat"}; non-String 참조 필드도 form body가 `type=<candidate>`가 되는지(GPT I2).
- 중첩 점-경로 재귀(깊이≥2·순환 가드·빈 POJO 폴백), bindingKind 분류(스칼라/참조/중첩/컬렉션), 안전 폴백.

**done**: in-repo E2E(5종) + 단위 GREEN + petclinic 로컬 회귀(도달 증가·크래시 0) + 전체 회귀 GREEN.

## 6. Phase 분해 (각 Phase: double-loop TDD + spec-compliance·code-quality 리뷰)
- **Phase 0**: E2E 픽스처(5종 @Controller 폼) + 수용 단언 작성(RED). petclinic 베이스라인 기록.
- **Phase 1**: `selectFormCommand`(@Valid 폼 커맨드 선택). 최소·독립.
- **Phase 2**: 중첩 객체 평면 점-경로 재귀 합성(+ formEncode 상호작용 §3.3).
- **Phase 3**: `ConverterRegistryIndexer`(Formatter/Converter/@Entity) + `FormFieldBinding` 메타 + 참조 백업테이블 해석 +
  러너 DB read/seed + reference-aware base/backtrack trial(name/id). (핵심·최대)
- **Phase 4**: PropertyEditor(@InitBinder, 컨트롤러-local) 레지스트리 합류 → Phase 3 trial 재사용. 컬렉션 비목표 확정.

## 7. 회귀 (regression-on-sut-expansion)
- order-service: 전 e2e + `BuilderE2eTest`(기존 happy/422/state-guard/negative-validation/폼 양-arm 단언 유지 + 신규 폼 5종).
- petclinic/diary/community: 폼 도달 증가, 비-폼 무변. 컨버터/중첩 없는 SUT 무변(정적+실측).

## 8. 위험
- **R1 백업 테이블/이름 컬럼 오해석** → 바인딩 실패. 완화: FK 우선 우선순위 + best-effort + 후보 trial(name/PK) + 폴백.
- **R2 컨버터 미감지**(addFormatters 람다 등 동적) → 중첩 오분류. 완화: @Entity는 컨버터 없어도 참조 취급 + 중첩은
  바인딩가능 하위필드 있을 때만(없으면 스칼라 폴백) + trial 실패 시 폴백.
- **R3 중첩 무한 재귀** → 깊이 상한 + 방문 타입 집합.
- **R4 다중 참조 필드 동시 유효** → reference-aware base(전 참조 필드 1순위 후보 동시 설정) + 필드당 후보 ≤2 backtrack.
- **R5 다중 DB dialect DB read** → POSTGRES/MYSQL/MARIADB별 SELECT, schema 가정 계승, 빈 테이블 시드.
- **R6 다중 추가-PATH 부분 도달**(edit) — 기존 한계 계승, 명시.

## 9. 상태
설계 승인(2026-06-16). spec + 3-모델 리뷰 + triage 반영 완료 → writing-plans(Phase별) → Phase 0부터 double-loop TDD.

**구현 완료(2026-06-21).** Phase 0~4 전부 GREEN. 5종 메커니즘(다중-커맨드/참조-name/참조-id/중첩/PropertyEditor)
모두 `BuilderIntegrationTest`에서 양 arm(302) 도달. 핵심 산출물: `FormFieldBinding`(static 메타), `selectFormCommand`
(@Valid/@Validated 우선), `ConverterRegistryIndexer`(Formatter/Converter/@InitBinder), `EndpointIndexer.classifyFormBindings`
(REFERENCE→NESTED→SCALAR), `FormBodySynthesizer`(중첩 점-경로 평면화 + refValues 주입), 러너 런타임 trial
(백업테이블 해석 FK→@Table→camelToSnake, SELECT/seed, name 1순위 + PK backtrack `discoveredBy="form-ref-trial"`).
컬렉션 필드는 v1 비목표 → 스칼라/skip 폴백(확정). 전체 회귀 0.

## 10. 3-모델 리뷰 triage (Sonnet design-doc-reviewer / Gemini 3.5 Flash / GPT-5.5)
Sonnet=approved_with_conditions, Gemini=needs_revision, GPT=needs_revision. 수렴·근거 기반 지적 전부 반영:
- **반영**: 참조 필드 값 직접 배선(javaType≠String이라 stringCandidates 미동작 — GPT I2) + reference-aware base/다필드
  동시(GPT I4); `FormFieldBinding` 메타 캐리어(GPT I3); 백업테이블 우선순위+분리 테스트(Sonnet I2/I3); DB read는 러너에서
  다중 dialect(GPT I5, Gemini I2 순환참조 해소); Spoon 제네릭 simple-name 폴백(Sonnet I1); @InitBinder PropertyEditor
  컨트롤러-local 스코프(Gemini I3); 중첩 빈-POJO/순환 폴백 가드(Gemini I4); @Entity id-변환 best-effort 완화(Sonnet I6);
  PropertyEditor DB-backed만(Sonnet I7); 컬렉션 v1 비목표 확정(GPT I6); §1/§5 메커니즘 5종 정합(Sonnet I5);
  `selectFormCommand` 별도 헬퍼(hasValidRequestBody 불변 — Sonnet I4, GPT I7); run-suites.sh는 로컬 게이트 명시(GPT I1).
- **거부**: Gemini I1(`bodyOnly`가 FORM 드롭) — **검증 결과 오류**. `bodyOnly`는 param **이름**만 제거하고 폼 필드는
  top-level 키라 보존됨(작동하는 post-web-orders E2E가 입증). 인접한 정확한 이슈(`formEncode` 비-스칼라 드롭)는 §3.3에 반영.

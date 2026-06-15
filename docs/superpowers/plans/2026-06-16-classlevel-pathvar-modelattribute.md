# 클래스-레벨 path 변수 `@ModelAttribute` 역추출 (작업 a)

작성: 2026-06-16 · 브랜치: worktree-feat-classlevel-pathvar · 기반: #39(@Controller 폼 인덱싱) 머지 후

## 1. 문제 / 동기
#39로 `@Controller` 폼 핸들러를 인덱싱하게 됐지만, petclinic `PetController`/`VisitController`는
**클래스-레벨 path 변수**(`@RequestMapping("/owners/{ownerId}")`)를 핸들러 파라미터가 아니라
`@ModelAttribute` 헬퍼 메서드에서만 해석한다:

```java
@Controller @RequestMapping("/owners/{ownerId}")
class PetController {
    @ModelAttribute("owner")
    Owner findOwner(@PathVariable("ownerId") int ownerId) { return owners.findById(ownerId).orElseThrow(); }
    @PostMapping("/pets/new")
    String processCreationForm(Owner owner, @Valid Pet pet, BindingResult result, ...) { ... }
}
```

`processCreationForm`의 파라미터에는 `ownerId`(@PathVariable)가 **없다**. 따라서 인덱서는 `{ownerId}`를
PATH 파라미터로 잡지 못하고, 탐색 시 `buildPathAndQuery`가 치환 못 한 placeholder를 센티널("0")로 둔다
(#39 방어). 결과: `findOwner`가 owner=0을 못 찾아 `orElseThrow` → 폼 핸들러 **진입 전에** 5xx/4xx.
커버리지 측정상 `post-owners-ownerid-pets-new` = **0/12 branches**(폼 바인딩·검증 분기 전무).

`VisitController.processNewVisitForm`도 동일 — `petId`는 핸들러에 `@PathVariable int petId`로 있으나
`ownerId`는 `@ModelAttribute loadPetWithVisit(@PathVariable ownerId, @PathVariable petId)`에서만.

## 2. 목표 / 비목표
- **목표**: 핸들러 path 템플릿의 `{placeholder}` 중 핸들러 파라미터에 `@PathVariable`로 없는 것을, **같은
  컨트롤러의 다른 메서드(`@ModelAttribute` 등)의 `@PathVariable` 파라미터**에서 타입을 역추출해 PATH
  파라미터로 등록한다. 등록되면 기존 `ReadInputSynthesizer` 시드 인프라가 자동으로 그 리소스(+FK 부모)를
  시드 → `@ModelAttribute` 헬퍼가 성공 → 폼 핸들러 진입 → 폼 바인딩/검증 양 arm.
- **비목표**:
  - 폼 커맨드 객체 정확 선택(petclinic `processCreationForm(Owner, Pet)`에서 Owner vs Pet) — 별도(작업 b 인접). (a)는 **도달성**(헬퍼 성공·핸들러 진입)만 책임진다.
  - entity-Formatter(`PetTypeFormatter`) 값 합성 — 작업 b.
  - **다중 추가-PATH의 비-target PATH 정밀 FK 시드**(예: `/owners/{ownerId}/pets/{petId}/edit`에서 ownerId·petId 동시 정확 매핑) — 별도 후속(a-2). 근거: 현재 `ReadInputSynthesizer.mapParamToColumn`은 PATH를 일괄 target PK에 매핑하므로 다중 PATH는 충돌(마지막이 덮어씀)한다(3-모델 리뷰 Gemini I1). 이를 last=target-PK·앞=FK-부모로 바꾸려면 `resolveTargetTable`까지 손대 회귀면이 넓어진다. (a)는 **단일 추가-PATH를 완전 해결**(petclinic 최대 타깃 `post-owners-ownerid-pets-new`는 추가-PATH가 `{ownerId}` 하나뿐 → (a)로 해금). 다중 추가-PATH(edit/visits)는 크래시 없이 부분 도달만 보장(헬퍼 일부 성공)하고 §7 R3·한계에 명시.
  - **콜론 정규식 path 변수**(`{id:\d+}`) — order-service/petclinic 부재. placeholder 파서는 `\{([^/}]+)\}`만 인식하고 콜론 포함 이름은 매칭 실패로 skip(센티널 폴백, 회귀 0).
  - `@RestController` 동작 변경 — 무관(REST는 `@PathVariable`을 항상 핸들러 파라미터에 둠).
  - `@SessionAttributes`·복합 객체 path 바인딩 등 비전형 패턴.

## 3. 접근
**정규화 이름 헬퍼(전 구간 단일 기준)**: `pathVarName(CtParameter p)` = `@PathVariable`의 `value` →
없으면 `name` → 없으면 `p.getSimpleName()`. 기존 `annotationPath`는 `value`/`path` 키만 읽으므로 `name` 키를
읽는 별도 추출(`annotationStringValue(a, "value", "name")`)을 추가한다. 이 정규화 이름을 (1) prepass 맵 키,
(2) **핸들러 `@PathVariable`의 `EndpointParam.name`**, (3) placeholder 비교 기준에 **모두** 사용한다.
→ 부수효과(의도된 수정): 기존엔 `@PathVariable("ownerId") int id`를 `EndpointParam.name="id"`로 잡아
`buildPathAndQuery`의 `{ownerId}` 치환이 실패하던 잠재 버그가 함께 교정된다(이름 매칭 일관). 이름 비교는
대소문자 구분(Spring 규칙).

`EndpointIndexer.index`에서 타입(컨트롤러) 단위 1패스 선처리:

1. **타입의 모든 메서드**에서 `@PathVariable` 파라미터를 수집해 `Map<pathVarName, javaType>` 구성
   (`@ModelAttribute`/`@InitBinder`/핸들러 무관 — 같은 컨트롤러 내 어디서든 그 path 변수의 타입 신호).
   **충돌 해결(동일 이름 타입 2종)**: (a) `required` 미지정/`true`가 `required=false`보다 우선, (b) 그래도
   동률이면 **javaType 사전순**으로 결정적 선택. (`getMethods()`가 unordered Set이라 "첫 등장"은 비결정적 —
   사전순 tiebreak로 JVM/실행 간 안정화. `@PathVariable`은 어느 메서드든 같은 이름=같은 변수라 타입 충돌 드묾.)
2. **placeholder 추출 = 순수 함수** `extractPlaceholders(fullPath) → LinkedHashSet<String>`: 정규식
   `\{([^/}]+)\}`로 캡처(슬래시·`}` 불포함). 콜론 정규식(`{id:\d+}`)은 매칭되지 않아 자동 제외(비목표).
   각 핸들러에서 `extractParams` 후, placeholder 집합에서 **이미 PATH로 잡힌 정규화 이름**을 제외한 나머지를
   1의 맵에서 타입을 찾아 PATH 파라미터로 **추가**한다. 맵에 없으면(타입 신호 없음) skip — 센티널 동작(#39)
   유지(회귀 0).
3. **파라미터 정렬 규약**: 최종 endpoint params를 `PATH → QUERY → FORM → BODY` 순으로 정렬하되 **동일 kind
   내에서는 원래 등장 순서 유지(안정 정렬)**. 역추출 PATH가 뒤에 append돼도 이 정렬로 PATH가 앞에 온다.
   `ReadInputSynthesizer`는 PATH를 target PK로 매핑(단일 추가-PATH 기준; 다중 PATH 정밀 FK 매핑은 비목표 a-2).

## 4. 설계 결정 / 대안
- **D1. 역추출 범위 = 같은 컨트롤러 타입 내 전 메서드.** 대안(헬퍼 메서드 `@ModelAttribute`만 스캔)은
  `VisitController`처럼 핸들러에 일부(petId)+헬퍼에 일부(ownerId)가 흩어진 경우를 단순 포괄. `@PathVariable`은
  본질적으로 라우트 변수라 어느 메서드에 있든 같은 이름=같은 변수. 안전.
- **D2. 타입 신호 없으면 skip(추가 안 함).** placeholder가 어디에도 `@PathVariable`로 안 나타나면(희귀)
  타입을 모르므로 센티널 폴백 유지. 인덱싱 실패가 아니라 #39 동작으로 graceful degrade → 회귀 0.
- **D3. 다중 추가-PATH 정밀 시드는 비목표(a-2 분리).** `mapParamToColumn`은 PATH를 일괄 target PK에 매핑
  하므로(검증: ReadInputSynthesizer.java L346-349) 다중 PATH는 충돌한다. (a)는 단일 추가-PATH를 완전 해결.
  단일 추가-PATH + target의 FK 부모는 기존 시드 인프라가 자동 처리(예: 벤치마크 orders.user_id → users 시드).
- **D4. `@RestController` 무영향**: REST는 `@PathVariable`을 핸들러에 두므로 1의 맵에 이미 핸들러 param이
  포함되고 2에서 "이미 PATH로 잡힘"으로 걸러져 추가가 없다 → IndexResult 동일. 단, §3 정규화 이름 도입으로
  `@PathVariable("x") T y` 형태의 `EndpointParam.name`이 `y`→`x`로 바뀔 수 있다(의도된 교정). 회귀 영향은
  실측으로 확인(§6): order-service/petclinic의 REST by-id는 대부분 이름 일치라 무변, 불일치 케이스는
  치환·시드가 오히려 정확해진다.

## 5. E2E / 수용 테스트 (정의된 done)
**outer-loop(먼저 RED)**: in-repo 결정적 벤치마크 **신규 파일** 추가(기존 `OrderWebController`는 유지·공존).

신규 `samples/order-service/src/main/java/io/graphrag/sample/orders/UserOrderWebController.java`:
- `@Controller @RequestMapping("/web/users/{userId}")` (클래스레벨 단일 추가-PATH `{userId}` — String PK).
- `@ModelAttribute("user") User findUser(@PathVariable("userId") String userId)` =
  `users.findById(userId).orElseThrow(() -> new IllegalArgumentException(...))` (역추출 안 되면 5xx).
- `@PostMapping("/submit") String submit(OrderForm form)` — `User user`를 핸들러 파라미터로 받지 **않는다**
  (받으면 첫 FORM-적격 파라미터로 잡혀 OrderForm을 가림). `findUser(@ModelAttribute)`는 매 요청 전 호출되어
  도달성 가드 역할. path는 `/submit`(테이블명 미포함)으로 둬 target-table 휴리스틱이 users로 정확히 잡히게 한다,
  **amount 가드(양 arm redirect 302)**: `if (form.getAmount() == null || form.getAmount() < 1 ||
  form.getAmount() > 1000) return "redirect:/web/users/error"; return "redirect:/web/users/ok";`
- inner `OrderForm`(JavaBean: `Integer amount` + getter/setter). users 테이블은 기존 `User.java`로 존재 →
  `userId` PATH가 users 행 시드 → `findUser` 성공(FK 부모 없음, 단일 테이블).

`BuilderE2eTest` 수용 단언(기존 `post-web-orders` 가드 유지하고 **추가**, `containsExactly` 목록에
`post-web-users-userid-submit` 정렬 위치로 삽입):
- 해당 endpoint가 **`userId` PATH** 파라미터를 가진다(FORM 아님 — 역추출 성공).
- valid-token path(negative-auth 제외)가 **분기 집합 다른 ≥2개 + 모두 302**(폼 양 arm — `findUser` 성공으로
  핸들러 진입 후 amount 가드 갈림).
- `userId` PATH가 시드한 `users` 행이 `asset.seeds()`에 존재(`findUser` 성공의 증거).
- **회귀 가드**: (a) 없으면 userId 센티널 → `findUser` orElseThrow 5xx → valid path 분기 집합 1개 → 단언 FAIL.

**inner-loop(단위 TDD)**: `EndpointIndexerTest` — (i) 클래스레벨 `{ownerId}`가 `@ModelAttribute` 메서드의
`@PathVariable("ownerId")`에서 역추출되어 핸들러가 PATH(ownerId, 정규화 이름) 파라미터를 가진다, (ii) 핸들러+헬퍼
혼재(petId 핸들러 `@PathVariable int petId`, ownerId 헬퍼)도 둘 다 PATH, (iii) 타입 신호 없는 placeholder는
추가 안 됨(skip), (iv) `@RestController`는 무변(IndexResult 동일), (v) **충돌 우선순위**: 동일 `{x}`가
`required=false int`와 `long`(둘 다 헬퍼)로 등장 → required=true(long)가 required=false(int)보다 우선 →
long 채택, (vi) **정렬**: 핸들러가 FORM만
선언하고 역추출 PATH 1개 → params가 `[PATH, FORM]` 순, (vii) `@PathVariable("x") T y`의 `EndpointParam.name`이
`x`(정규화)다.

**done(A — in-repo, CI 자동 검증)**: 위 E2E 수용 단언 + 단위 (i)~(vii) GREEN, order-service e2e 전체 GREEN.
**done(B — 외부 스윕, 수동/관측)**: petclinic builder 전 사이클 — `post-owners-ownerid-pets-new`(단일 ownerId)
폼 진입 분기 0/12→증가, 다중 PATH(edit/visits)는 크래시 0·부분 도달, `@RestController` path/branch 무변,
APP-AGGREGATE 비감소. 대표 MSA 1~2종(notification/analytics) builder 실측으로 IndexResult 무변 확인.

## 6. 회귀 (regression-on-sut-expansion)
- order-service: e2e 전체 + `BuilderE2eTest`(done A).
- petclinic: builder 전 사이클 — `post-owners-ownerid-pets-new`(단일 ownerId) 폼 진입 분기 향상,
  edit/visits(다중 PATH) 크래시 0·부분, `@RestController` path/branch 무변, 크래시 0(done B).
- MSA: **검증 방법** — D4로 @RestController는 무영향이지만 정규화-이름 도입이 `EndpointParam.name`을 바꿀 수
  있으므로 대표 1~2종(notification/analytics) builder를 실행해 **endpoint id·params(kind+name) 목록이 사전과
  동일**한지 비교(IndexResult 동치). 나머지 4종은 동일 코드경로라 정적 동치로 갈음하되 그 사실을 명시(silent
  truncation 아님).

## 7. 위험
- **R1. 잘못된 타입 역추출**로 엉뚱한 시드 → target 오선택. 완화: D2 skip + D3는 기존 휴리스틱 재사용(신규 추론 없음).
- **R2. placeholder 정규식**이 `{id:\d+}` 정규식 매핑·중첩 `{}`를 오파싱. 완화: `\{([^/}]+)\}`(슬래시·`}` 불포함), 콜론 앞부분만 이름.
- **R3. 폼 커맨드 모호(Owner vs Pet)**로 양 arm이 기대만큼 안 열릴 수 있음 — (a)는 도달성만 보장, 깊은 커버는 작업 b. petclinic 단언은 order-service 벤치마크로 한정(petclinic은 회귀+향상 관측만).

## 8. 3-모델 리뷰 triage (Sonnet / Gemini 3.5 Flash / GPT-5.2)
세 모델 모두 approved_with_conditions/needs_revision — 접근(역추출)은 sound, 정밀화 필요로 일치.

**반영(important):**
- **정규화 이름 헬퍼 전 구간 일관**(Sonnet I2, Gemini I2, GPT I1): `pathVarName()`(value→name→simpleName) +
  `name` 키 읽는 추출 추가. prepass·핸들러추출·placeholder비교 동일 기준(§3). 기존 이름-불일치 치환 버그도 교정.
- **충돌 우선순위 결정 규칙 + 테스트**(Sonnet I3 부분, GPT I2): required true>false → 동률 시 javaType 사전순(결정적, §3-1, 단위 (v)).

## 9. 사후 코드리뷰 반영(2차)
- (spec F2 doc-drift) §5 벤치마크: `@PostMapping("/orders") submit(User, OrderForm)` → `@PostMapping("/submit") submit(OrderForm)`로 정정. User 핸들러-파라미터 제거(첫 FORM 슬롯 가림 방지), path를 테이블명 미포함 `/submit`으로(target=users 정확). endpoint id `post-web-users-userid-submit`.
- (spec F1 / code #2) 충돌 동률 tiebreak를 "첫 등장"(getMethods() Set 순서 의존, 비결정) → **javaType 사전순**(결정적)으로 변경. tie-break (b) "핸들러 타입 우선"은 §4 D1(어느 메서드든 같은 변수)에 비춰 redundant라 미채택.
- (spec F4) 단위 (iv) `@RestController` 무변 명시 테스트(`restControllerWithMatchingPathVarNameUnchanged`) 추가.
- (code #1) `extractPlaceholders` 콜론 정규식 주석 정정(캡처되지만 정규화 이름 불일치로 skip).
- **placeholder 순수함수 + 콜론 비목표**(GPT I3, Sonnet I7, Gemini I3): `extractPlaceholders`, `{id:\d+}` skip(§2,§3-2).
- **파라미터 정렬 규약 PATH→QUERY→FORM→BODY 안정정렬 + 테스트**(Sonnet I5, GPT I4): §3-3, 단위 (vi).
- **벤치마크 구체화·공존·containsExactly 갱신·amount 가드**(Sonnet I1/I4/I6, GPT I5): §5 UserOrderWebController 명세.
- **done A(in-repo CI) / B(외부 스윕) 분리 + MSA 검증 방법**(GPT I6, Sonnet I8): §5 done, §6.

**reject(근거):**
- **Gemini I1(다중 PATH `mapParamToColumn` 전면 개선)**: (a) 범위에서 분리(비목표 a-2, §2/§4 D3). 회귀면이
  넓고(`resolveTargetTable`까지), petclinic 최대 타깃 `pets/new`는 단일 ownerId라 (a)만으로 해금됨. 다중 PATH는
  크래시 0·부분 도달로 정직하게 한계 명시. → 별도 후속에서 last=target-PK·앞=FK-부모 매핑으로 처리.

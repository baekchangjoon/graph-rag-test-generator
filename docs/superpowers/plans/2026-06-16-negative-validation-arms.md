# 검증 reject arm 체계화 (negative validation 변종) — 범주 B / B1

작성: 2026-06-16 · 브랜치: worktree-feat-negative-validation-arms · 기반: #41 머지 후

## 1. 문제 / 동기
검증 **reject arm(4xx)** 을 체계적으로 여는 메커니즘이 없다. 현재 negative path는 **negative-auth**(무효
토큰)뿐이고, 검증 위반 arm은 fuzzer가 경계(nights 0/31)를 **우연히** 변이할 때만 일부 열린다. `priceTier=null`,
`email 무효`, `@Pattern 불일치` 같은 **비-경계 위반은 결정적으로 생성되지 않아** reject 분기가 닫혀 있다.

`ValidationConstraintExtractor`는 이미 `@NotNull/@NotBlank/@NotEmpty/@Size/@Min/@Max/@Pattern/@Email/
@Positive/@Negative`를 추출하지만(happy 합성에만 사용), **위반 변종을 합성하지 않는다**.

**범용성(핵심)**: Bean Validation 어노테이션을 쓰는 SUT가 다수 — diary 15·petclinic 13·community 10·
auth-user 5개 어노테이션. REST/폼 무관하게 모든 검증 DTO에 적용되므로 **#3~(a)/(b)의 @Controller 폼 특화와
달리 진짜 범용**(MSA 포함). 코어 리스크 낮음(추출 재사용 + synthesizer 확장).

## 2. 목표 / 비목표
- **목표**: Bean Validation 어노테이션 제약별 **위반 변종 1개**를 결정적으로 합성해 각 reject arm(4xx)을 연다.
  happy에서 **한 필드만** 위반(나머지 valid)시켜 그 제약의 분기만 격리해 캡처.
- **비목표**:
  - **명령형 if-throw 검증**(`ReservationService.create`의 `if(req.priceTier()==null) throw` 등) — null/regex/
    temporal **입력** 가드 추출기가 없어(현 `extractComparisons`는 비교만, `extractStateGuards`는 저장-행 getter만)
    추출 확장이 필요 → **별도 후속(B1-imperative)**. petclinic `ReservationService` 미커버[61,67,70]는 여기 해당.
  - 복합 위반(다필드 동시 무효) — 단일 필드 격리만(분기 귀속 명확).
  - 불투명 가드(`hashCode floorMod`) — 영구 비목표.
  - 생성(Generator) — reject 변종은 **커버리지 전용**(negative-auth와 동일, `discoveredBy="negative-validation"`
    마커로 생성 제외).

## 3. 접근
1. **`ValidationConstraintExtractor` 출력 재사용**(필드 → 제약 목록). 각 제약 → **결정적 위반값**:
   - `@NotNull/@NotBlank/@NotEmpty` → 필드 제거(null) / 빈 문자열
   - `@Min(n)` → `n-1`; `@Max(n)` → `n+1`; `@Positive` → `0`; `@Negative` → `0`; `@PositiveOrZero` → `-1`
   - `@Size(min,max)` → 길이 `min-1`(또는 `max+1`); `@Pattern(re)` → 불일치 sentinel; `@Email` → `"not-an-email"`
2. **`synthesizeNegativeValidationVariants(shape, constraints, happyBody)`**: happy body를 복제해 **한 필드만**
   위반값으로 덮은 변종을 제약마다 1개 생성. 결정적 정렬(필드명·제약종류). 컬럼당 상한(K=6, 초과 시 log).
3. **runner 배선**: by-id/POST 등 body 엔드포인트에서 각 변종을 **1회 발행** → 응답 4xx면
   `ExploredPath(discoveredBy="negative-validation")` 캡처(분기 집합 포함). negative-auth와 동일하게
   per-request dump가 reject arm을 cumulativeCoverage에 크레딧. `GRB_NEGATIVE_VALIDATION=off`로 게이트.

## 4. 설계 결정
- **D1 단일 필드 격리**: 한 변종 = 한 제약 위반 → reject 분기 귀속이 명확하고 결정적. 다필드 동시 위반은 어느
  가드가 먼저 throw인지 모호 → 비목표.
- **D2 어노테이션만(1차)**: `ValidationConstraintExtractor` 재사용 → 추출 확장 0, 코어 리스크 최소. 명령형은 별도.
- **D3 커버리지 전용**: reject 변종은 생성 제외(negative-auth 정신). 4xx 테스트 생성은 별도 가치판단.
- **D4 무회귀**: happy 합성·기존 path 무변. 변종은 추가 발행만(게이트로 off 가능).

## 5. E2E / 수용 테스트 (정의된 done)
**outer-loop(먼저 RED)**: order-service에 **Bean Validation DTO 엔드포인트** 추가(또는 기존 활용). 예:
신규 `@PostMapping("/api/signups")` `@Valid SignupRequest`(`@NotBlank String name`, `@Email String email`,
`@Min(18) int age`, `@Size(min=8) String password`) → 각 필드 위반 시 400(MethodArgumentNotValidException).

`BuilderE2eTest` 수용 단언:
- `post-api-signups`에 **각 제약 위반 path(400)** 가 캡처된다 — `discoveredBy="negative-validation"` path가
  필드별(name 빈/email 무효/age 17/password 짧음)로 ≥4개.
- 회귀 가드: 변종 미적용으로 되돌리면 happy(201/200)만 → reject path 0 → FAIL.

**inner-loop(단위 TDD)**: `synthesizeNegativeValidationVariants` — (i) `@NotNull`→null 변종, (ii) `@Min`→경계-1,
(iii) `@Email`→무효, (iv) `@Pattern`→불일치, (v) `@Size`→길이 위반, (vi) 단일 필드만 위반(나머지 happy 유지),
(vii) 상한 K 초과 드롭+log, (viii) 제약 없으면 빈 결과(무회귀).

**done(A — in-repo, CI)**: 위 E2E + 단위 GREEN, order-service e2e 전체 GREEN.
**done(B — 외부 스윕)**: petclinic/diary/community builder — Bean Validation 가진 엔드포인트의 reject arm 증가
(`negative-validation` path 신규), happy 무변, 크래시 0. analytics/notification/mindgraph(어노테이션 0)는 무변.

## 6. 회귀 (regression-on-sut-expansion)
- order-service: e2e 전체 + `BuilderE2eTest`(기존 happy/422/state-guard 단언 유지 + signups reject).
- petclinic/diary/community: builder 전 사이클 — reject arm 증가, happy 무변. 어노테이션 0인 SUT 무변(정적+실측).

## 7. 위험
- **R1 위반값이 happy 전제를 깸**: 한 필드 위반이 다른 가드 선행 throw를 유발해 의도한 분기 미도달. 완화:
  D1 단일 필드 + happy 나머지 유지 + 결정적 위반값(가드 경계 바로 밖).
- **R2 변종 폭발**: 필드·제약 많으면 발행 수↑. 완화: K=6 상한 + 게이트(off). 추가 발행 ≤ Σ min(K, 제약수).
- **R3 명령형 검증 미커버 잔존**: ReservationService류는 B1으로 안 열림(별도 후속). 정직하게 명시 —
  B1은 **어노테이션 검증**(범용 다수)에 한정, 명령형은 추출 확장 후속.

## 8. 3-모델 리뷰 triage (Sonnet / Gemini 3.5 Flash / GPT-5.2)
세 모델 모두 approved_with_conditions/needs_revision — 방향(어노테이션 위반 변종 체계화)은 sound, 배선·위반값
정밀화 필요로 일치. 전부 반영(구현 시 설계 갱신 반영).

**반영(critical):**
- **`@Valid` 감지 게이트**(Sonnet I1, GPT I6): Spring은 `@RequestBody` 파라미터에 `@Valid`가 있어야
  `MethodArgumentNotValidException`(400). `EndpointIndexer`가 `validBody` 플래그를 surface하고 runner가
  그 엔드포인트에만 negative-validation pass 실행. **JSON `@RequestBody`로 한정**(`ParamKind.FORM`은
  form-encoding이 null/중첩을 드롭 → 바인딩 에러 혼동, 비목표). `ParamKind.BODY`만.
- **orchestrator 우회 + 고유 path-id**(Gemini I1, Sonnet I3, GPT I4): 검증 실패는 SUT 컨트롤러 **진입 전**
  400이라 앱 분기 커버리지가 동일 → `ExplorationOrchestrator`가 status+coverageKey로 **1 path로 병합**.
  따라서 negative-auth와 동일하게 **orchestrator 우회** — 각 변종을 직접 발행하고 `paths`에 append.
  path-id = `endpointId + "-negval-" + field + "-" + kind.toLowerCase()`(고유·결정적).

**반영(important):**
- **지원 제약 = 실제 `ValidationConstraintExtractor.Kind` 기준 + kind별 위반값**(GPT I5, Sonnet I5/I6, Gemini I2/I3):
  - `NOT_NULL` → 필드 제거(null)
  - `NOT_BLANK`(=@NotBlank/@NotEmpty collapse) → String이면 공백 `"   "`(null은 @NotBlank 통과 못 시킴);
    Collection 필드면 `[]`(빈 배열) — `""`는 Jackson 역직렬화 에러(generic 400)
  - `SIZE_MIN` → 길이 `min-1`, `SIZE_MAX` → 길이 `max+1`(추출기가 min=0이면 SIZE_MIN, max=MAX_VALUE면 SIZE_MAX 억제 — 있는 것만)
  - `MIN` → `min-1`, `MAX` → `max+1`, `POSITIVE` → `0`, `POSITIVE_OR_ZERO` → `-1`, `NEGATIVE` → `0`, `NEGATIVE_OR_ZERO` → `1`
  - `EMAIL` → `"not-an-email"`
  - `PATTERN` → 불일치 sentinel을 **Java `Pattern.matches()`로 검증**(우연 매치 시 조정) — 현 코드는 PATTERN 값 생성 보류이므로 이 부분이 신규
- **구현 위치 명시**(GPT I4): `EndpointExplorationRunner`에 negative-auth/state-guard와 **동일 형태의
  "negative-validation pass"** 추가. `synthesizeNegativeValidationVariants`는 합성 헬퍼.
- **K 단위 = 엔드포인트당, K=4**(Sonnet I2, GPT I9): 기존 `VARIANT_CAP=4`와 일치(별도 상수 아님). 변종 후보는
  `(field, kind)` 쌍을 field명·kind 정렬 후 앞에서 엔드포인트당 최대 4, 초과 시 드롭+log.
- **Generator skip 명시**(Gemini I4): `Generator`가 `discoveredBy="negative-validation"` path를 생성 제외
  (negative-auth와 동일 — 이미 그 분기 존재, 마커만 추가).
- **GET by-id 제외**(Gemini I6): body 없는 엔드포인트는 제약 0 → 자동 skip. §3 예시에서 "by-id" 제거.

**반영(recommended):**
- **R1 귀속 불확실성**(Sonnet I7): 변종 응답이 400이 아니면(선행 가드 throw 등) 캡처하되 귀속 불확실. 400이어도
  응답 body에 기대 필드명 없으면 log.warn. 완전 귀속 검증은 비목표.
- **벤치마크 step 0 + in-repo 근거**(Sonnet I4, GPT I8): `/api/signups`는 **존재하지 않으므로 구현 step 0에서
  order-service에 신규 추가**(`SignupController` + `@Valid SignupRequest`). petclinic `ReservationService`[61,67,70]
  근거는 외부 SUT 관측(명령형이라 B1 비목표 — 어노테이션 검증만 B1).

## 9. 상태
설계 + 3-모델 리뷰 + triage **완성**. 구현 보류(다음 세션) — 이번 세션이 매우 길어(3 PR 머지 + #4 설계 + B1
설계) 컨텍스트 한계. 브랜치 `worktree-feat-negative-validation-arms`에 설계 보존, 다음 세션에서 즉시 구현 착수
가능(#4 `Rational` 보존과 동일 방식). 다음: B1 구현 → (b) 폼 커맨드 선택 + entity-Formatter.

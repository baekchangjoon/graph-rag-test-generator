# Stage 4 — Z3 inter-field 제약 솔버 (ConcolicOracle 확장)

작성일: 2026-06-15 · 브랜치: `worktree-feat-z3-interfield` (main 기준)
설계 근거: 백그라운드 워크플로 `design-z3-interfield`(이해→접근안 3개→종합), 소스 주장 verbatim 검증.

## 1. 문제

`ConcolicOracle`(ASM+Z3)는 **단일 필드 선형 정수 경계**(`coeff*field+const==0`)만 푼다. **inter-field 가드**
(두 입력 필드를 한 식에 엮는 비교)는 못 푼다. 검증된 3대 장벽:
1. `Sym.add()`(ConcolicOracle.java:425): 서로 다른 두 필드가 만나면 `Sym.top` 반환 → 2-필드 선형식 붕괴.
2. `solveBoundary()`(329-344): Z3 `IntExpr "f"` 1개 하드코딩 + `mkEq(...,0)`만 → 2변수·부등식 표현 불가.
3. `record()`(323): 비교 **opcode 폐기** → strict 부등식(petclinic 가드) 표현 불가.
추가: float 리터럴 조기 손실(LDC Double→top, 201), `x*y` 비선형(top, 453), enum accessor
`priceTier.getNightlyRate()`는 interprocedural(opaque). 출력 타입 `InputCandidates`(필드-keyed Map)는
**커플된 튜플 `{deposit=X,nights=Y}`을 표현 불가** — 변이는 필드별 독립 적용.

현재 petclinic deposit 가드는 **우연히만** 통과(1000.0 float 휴리스틱 + 1e6 large-mutation으로 deposit이
커짐) — solve가 아니며 결정적 inter-field 승리도, in-repo 회귀 가드도 없다.

## 2. 접근 — ConcolicOracle in-place 확장 (additive 채널, 항상 bail)

종합 결론: **Proposal 1(in-place 확장)을 척추**로, **Proposal 3의 전방위 bail 규율**을 안전 자세로,
**Proposal 2의 feasibility split**(in-repo 가드=결정적 보장 승리 / petclinic=best-effort)을 채택. 별도
oracle/`GRB_INTERFIELD` 채널은 만들지 않음(과설계). **모든 미지원 케이스는 bail = no-op**(48/48 단일필드 무회귀).

핵심:
- **(A) 데이터 모델(additive)**: `InputCandidates`에 3번째 컴포넌트 `List<Map<String,Long>> tuples`(필드→값
  배정). 기존 `candidates`가 이미 `run()`로 흐르므로(125,154-155) **run() 시그니처·BuilderCli 병합 변경 불요**.
- **(B) Sym 2-필드 lift**: Sym이 최대 **2개**(field,coeff)+const 보유(cap=2, 3번째 필드→top). `add()`는
  맵 병합·동일 키 coeff 합산. `mul()`: const×선형=스케일(기존), field×field=top(진짜 비선형, 의도적 미추적).
  단일 필드(키 1개)는 기존과 동일 동작(`score*2==84→42` 보존).
- **(C) opcode threading**: Comparison record에 opcode family(EQ/NE/LT/LE/GT/GE)+2번째 field/coeff 추가.
  `record()`·`IF_ICMP*`(230-234)·`IFx`(228-229)가 실제 opcode 전달. 단일필드 EQ는 기존 `{B-1,B,B+1}` 후보 유지.
- **(D) Z3 2변수 op-aware**: `solveTuple(ctx, comparison)` — 필드명별 `mkIntConst`(하드코딩 'f' 아님), `Σ
  coeff_i*var_i + const`에 opcode 관계(`mkLt/mkLe/mkGt/mkGe/mkEq/mkDistinct`) 적용. 각 var `>=1` soft
  하한(all-zero degenerate 방지), 2s timeout. SAT→Map<String,Long> 튜플 1개, UNSAT/UNKNOWN/timeout→無.
- **(E) rational 스케일(float, best-effort, bailable)**: `field*cleanRational`(예 *1.1)이면 1.1→11/10, 전
  (in)equality에 분모 LCM 곱해 정수화(QF_LIA, **QF_NIA 절대 아님**). 소분모 종료소수만, 아니면 drop(bail).
  in-repo 승리는 이 경로 미사용(정수 가드).
- **(F) enum-grounding(interprocedural, best-effort, bailable, 최고 리스크)**: `invoke()`(277)에서 0-arg
  numeric accessor의 receiver Sym이 known enum-typed 입력 필드면, enum 상수→리터럴 rate 맵(enum 클래스
  바이트 읽기)으로 `nights*rate`를 `nights*<const>`(단일필드 선형)로 치환, 상수별 1회 solve(memoize), 튜플에
  enum 필드 pin. receiver 타입 미해결/getter 비-상수면 bail. (`GETFIELD`는 현재 top[237], enum receiver
  타입 추적은 신규.)
- **(G) consumption(joint() 미러)**: `InputMutator.interField(mutableFields, tuples)` — 튜플당 atomic
  `interfield-<sorted-keys>` mutation 1개(모든 튜플 필드를 body에 동시 set, 가드: 전 필드가 mutableFields에
  존재). `forTarget()`(73-78)에서 constraintDirected/enum/joint **후**, `firstOrder()` **전** 배선. dedupeByName.
- **(H) threading**: `EndpointTarget`에 12번째 컴포넌트 `interFieldTuples`(편의 ctor 2개 `List.of()` 기본).
  `EndpointExplorationRunner.run()`이 `candidates.tuples()`를 두 EndpointTarget 생성(160-162,196-198)에 전달.
- **(I) in-repo 결정적 가드(보장 승리)**: `BookingController.create()`의 email check(line 54) **후**에
  기존 필드만으로 2-필드 선형 **등식** 게이트 추가 — `if (req.loyaltyPoints()!=null && req.loyaltyPoints()+
  2*req.nights() != 600) throw 422;`. 201 happy는 `loyaltyPoints+2*nights == 600` **정확히** 필요.
  **부등식이 아닌 등식**을 쓰는 이유(리뷰 반영, §8): `>=` 부등식은 단일필드 `large-loyaltyPoints=1e6`
  변이로 우연히 충족돼 RED·ablation이 무력화된다(Opus I1/I5). 등식은 어떤 단일필드 극단값(large/zero/neg/
  boundary)으로도 못 맞추고 **오직 solveTuple(EQ)이 푼 튜플**만 충족 → RED·ablation이 airtight.
  **순수 정수 2-필드 LIA**(float·enum 불요). nights는 기존 가드로 1..30이라(§D) solveTuple이 단일필드
  경계를 conjoin해야 nights∈[1,30]·loyalty∈[540,598] in-range 모델을 1회에 찾음.

## 3. E2E/수용 기준 (먼저 작성, 바깥 루프)

1. **in-repo(PRIMARY, 결정적, MUST PASS)**: BookingController에 새 등식 가드 추가 후 `BuilderE2eTest`(파일:
   `graph-rag-builder/src/test/java/io/graphrag/builder/cli/BuilderE2eTest.java`, Docker 필요)가
   `post-api-bookings`에 **201 path 존재 AND 그 201 입력이 `loyaltyPoints+2*nights==600` 만족**(특정 숫자
   아닌 가드-만족으로 단언). solver 전엔 RED(어떤 단일필드 변이로도 등식 불충족→422만), 후엔 GREEN.
   interField() 제거 시 201→422 회귀.
2. **in-repo ablation(귀속 증명)**: `GRB_ORACLE=static`(concolic drop, BuilderCli:209 → `candidates.tuples()`
   empty → interField() mutation 0개) 재실행 시 `post-api-bookings`에 **가드-만족 201 path 부재**(422만).
   `StaticLiteralOracle.analyze()`는 빈 tuples 반환(신규 3-arg ctor 기본). 등식 가드라 단일필드 변이가
   ==600을 못 만들어 ablation이 결정적으로 flip → 201 승리가 solved 튜플 덕임을 입증. order-service e2e 무회귀.
3. **외부(BEST-EFFORT, 명시 외부 승리)**: petclinic build가 `post-api-reservations`의 deposit 가드 true-arm
   201 + 기록 튜플 `(deposit,nights,tier)`가 `deposit*1.1<nights*rate` 만족(rational 스케일 + enum-grounding)
   → Reservation L73 커버 43% plateau 돌파. `GETFIELD`/enum receiver 추적이 petclinic 실제 바이트코드에서
   불가하면 best-effort로 명시 강등(in-repo가 보장 승리). **어느 결과인지 측정해 문서화.**

## 4. Double-loop TDD 순서

1. **바깥 먼저(RED)**: BookingController 가드 추가 → BuilderE2eTest 201-with-satisfying-input 단언. RED 확인(약화 금지).
2. **inner #1 데이터모델**: `InputCandidates.tuples()` 존재·empty·merge union. 기존 4개 단일필드 단언 무회귀.
3. **inner #2 2-필드 Sym lift**: `score-2*bonus+50==0` fixture → 만족 튜플. Sym 2-필드(cap2/3rd→top/mul→top)+widened Comparison, solveTuple(EQ).
4. **inner #3 opcode+부등식**: `a+2*b>=600` fixture → GE 만족 튜플. opcode threading + Z3 관계 dispatch + var>=1 + 2s.
5. **inner #4 rational(petclinic-only)**: `deposit*11<nights*300`/1.1 fixture → LCM×10 QF_LIA. 비-rational fixture는 BAIL.
6. **inner #5 enum-grounding(best-effort)**: enum getRate fixture → 상수별 치환·memoize·pin. 미해결 receiver fixture는 BAIL(無, no throw).
7. **inner #6 bail 규율**: 3-필드/진짜 a*b/opaque getter fixture → tuples empty, no exception.
8. **inner #7 consumption**: InputMutator.interField 단위 + EndpointTarget 12번째+ctor + Runner 두 생성 배선 + forTarget 순서.
9. 단일필드 무회귀 + 전 빌더 단위 + InputMutatorTest GREEN.
10. **바깥 GREEN**: BuilderE2eTest in-repo 201 GREEN + ablation(GRB_ORACLE=static) + e2e 48/48. petclinic build로
    best-effort Reservation 201 + L73 lift **여부 측정·기록**. refactor.

## 5. 범위 / 비범위
- **범위**: 2-필드 선형 정수(LIA) inter-field 가드(in-repo Booking 게이트=보장). float rational 스케일 + enum-grounding은 petclinic best-effort.
- **비범위**: 3+ 필드 단일 선형식(bail), 진짜 비선형 `x*y`(top), 비-rational float(bail), 인식 안 되는 enum getter(bail). 전부 no-op.

## 6. 리스크 (검증됨)
1. **Sym widening = 최고온 경로**(모든 산술 opcode). 버그=단일필드 조용한 회귀. 완화: 4개 무회귀 단언+48/48 e2e 선행, 키-1 맵을 오늘과 동일 동작 유지.
2. **enum-grounding=최고 리스크**: `GETFIELD`→top(237), enum receiver 타입 추적 신규. 완화: 전방위 bail(실패=no-op, 절대 잘못된 201 아님). in-repo는 enum 불요=보장.
3. **float 모델링**: LDC Double→top(201). 깔끔치 않으면 bail→petclinic best-effort. 완화: inner#4에서 좁은 모델링 선택, in-repo는 정수.
4. **Z3 degenerate 모델**(한 필드 거대): 다른 단일필드 가드 위반→비-201. 완화: var>=1 soft 하한, replay가 비-201 폐기.
5. **2-필드 cap**: 3+ 필드 bail(수용 범위, 문서화).
6. **결정성**: Z3 모델 pin(안정 var 순서)+튜플 정렬(canonical key)로 BuilderE2eTest 안정 — 가드-만족으로 단언(특정 숫자 아님).
7. **merge-point stack clearing**(178): 라벨/점프 넘으면 symbolic state 손실. petclinic L73/Booking 가드가 intra-block early인지 검증.
8. **BookingController는 BuilderE2eTest 단언 대상**: 새 가드는 기존 가드 후, happy 합성이 여전히 만족 입력 생성해야 무관 단언 무회귀.

## 7. 관련 파일
- 신규 테스트 fixture: `oracle/fixtures/InterFieldFixture.java`(2-필드 LIA/EQ/rational/BAIL).
- 수정: `oracle/InputCandidates.java`(tuples), `oracle/ConcolicOracle.java`(Sym lift+opcode+solveTuple+rational+enum-ground), `explore/EndpointTarget.java`(12번째), `explore/InputMutator.java`(interField), `run/EndpointExplorationRunner.java`(tuples 전달), `samples/order-service/.../BookingController.java`(in-repo 가드).
- 테스트: `ConcolicOracleTest`, `InputMutatorTest`, `BuilderE2eTest`(수용).
- 문서: `docs/24`·`docs/25 §9`(inter-field 진행 갱신).

## 8. 3-모델 설계 리뷰 triage (Opus/Sonnet/Haiku)

세 모델 리뷰 판정: Opus·Sonnet `approved_with_conditions`, Haiku `needs_revision`. located findings를 판정·반영:

**반영(수정 완료):**
- **RED/ablation 무력화(Opus I1/I5, Sonnet I5) — 최우선**: in-repo 가드를 `>=600`→**등식 `!=600`**로 변경.
  단일필드 large/zero/neg/boundary 변이로 등식 불충족 → solveTuple(EQ)만 충족. §2(I)·§3 반영.
- **opcode 의미(Opus I2)**: `IF_ICMP*`는 `sub` 후 **단일 결합 Sym**(2-필드 coeff 맵) + 원본 opcode로 비교
  (operand 2개 아님). solveTuple은 `Σcoeff_i*var_i + const <op> 0` 인코딩. ⇒ §2(C)/(D) 구현 시 이 의미로.
- **degenerate 모델(Opus I3, Sonnet I3)**: solveTuple이 동일 필드의 알려진 단일필드 경계(mergeComparisonBounds의
  MIN/MAX, 여기선 nights∈[1,30])를 Z3에 conjoin → in-range 모델 1회 확보(replay 낭비 제거). §2(I)에 명시.
- **라우팅(Sonnet I7)**: Comparison 필드 키 1개→기존 `solveBoundary`(±1 불변), 2개→신규 `solveTuple`. solveBoundary 미수정.
- **back-compat(Sonnet I2, Haiku I3)**: `InputCandidates`에 3-arg ctor + 2-arg 편의 ctor(tuples=List.of()).
  `merge()`=tuples union-all, 소비단 `dedupeByName`이 동일 키셋 collapse. `StaticLiteralOracle`은 빈 tuples.
- **"48/48" 모호(Sonnet I4)**: 문서상 "48/48"은 order-service **e2e 스위트** 수치. 단일필드 무회귀는
  `ConcolicOracleTest`(1 테스트, 4 서브단언)+`InputMutatorTest`+전 빌더 단위로 지칭(용어 정정).
- **Sym 구조(Sonnet I1, Haiku I1-I5)**: 구현 시 `Sym`을 `Map<String,Long> terms`(키 ≤2)+`long constant`로
  확정, `add()`=맵 병합(키>2→top), `mul()`=const×선형 스케일/field×field→top. solveTuple/interField/
  EndpointTarget 12번째 시그니처는 inner TDD #1-#2에서 코드로 확정(계획은 계약만; 시그니처 세부는 TDD가 lock).
- **docs/25 §9 갱신 내용(Sonnet I8/I10)**: 완료 시 §9 표에 Stage 4 행(측정된 petclinic lift 또는 best-effort 사유)
  추가 + "기법별 사용처"에 "2-필드 선형 정수 inter-field→solveTuple" 불릿.

**보류/거부(근거):**
- **long LCMP 경로(Sonnet I6)**: in-repo·petclinic 타깃 필드는 모두 `Integer`라 LCMP 미경유. long 2-필드는 **범위 밖
  bail**(top)로 명시 — 거부가 아닌 scope 한정.
- **merge-point stack clearing(리스크 7, Haiku I12)**: 구현 inner#3에서 BookingController.create/petclinic L73
  바이트코드가 intra-block early인지 **검증 후** 진행(가드가 라벨/점프 넘으면 best-effort 강등). 계획 단계 조치 불요.
- **enum-grounding 세부(Haiku I9)**: best-effort·전방위 bail이므로 계획에선 계약만; ASM ClassReader로 enum 상수
  리터럴 rate 맵 구성·memoize·미해결 receiver bail은 inner#5에서 petclinic 실제 바이트코드 대면 후 좁게 확정.

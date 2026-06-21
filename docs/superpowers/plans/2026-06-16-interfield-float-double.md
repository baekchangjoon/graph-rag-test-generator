# float/double inter-field 제약 해결 (작업 #4)

작성: 2026-06-16 · 브랜치: worktree-feat-interfield-float-enum · 기반: #40 머지 후

## 1. 문제 / 동기
`ConcolicOracle`은 입력 필드의 **정수 선형식**(`Σ coeff·field + const`, `coeff`/`const`가 `long`)만 추적해
비교 분기의 경계/튜플을 Z3로 푼다. float/double 비교 가드(`if (price * rate >= minCharge)`)는 바이트코드
`FLOAD/FCONST/FADD/FMUL/FCMPL/DCMP…`가 전부 미처리라 `applyGenericStack`의 default에서
`unhandled opcode` 예외 → 그 메서드의 concolic 분석을 **bail**한다. 결과: 두 float 필드를 동시충족해야
열리는 분기(예: `basePrice * discountRate < minMargin`)에 도달하는 입력을 합성하지 못한다.

enum inter-field는 이미 `ConstraintExtractor.extractConjunctions`(다필드 `&&`)로 커버되므로 비범위.

## 2. 목표 / 비목표
- **목표**: float/double **순수 선형 inter-field**(2-필드)와 단일 float 필드 경계를 Z3 **Real**로 풀어
  입력 후보(튜플/경계 ±1)를 생성한다. 정수 경로는 **완전 무회귀**.
- **비목표**:
  - **비선형**(변수×변수, `FDIV`/`DDIV`의 변수 분모, 초월함수) — `Sym.mul`이 이미 비선형을 top으로 bail.
  - **int↔float 혼합 inter-field**(한 비교에 정수 필드와 float 필드 동시) — best-effort: real 경로로 통합
    풀이는 가능하나 정수 필드의 경계 ±1 의미가 약화되므로 1차 범위에서 **단일 비교 내 전부 같은 도메인**
    (전부 정수 or 전부 real)만 튜플로 푼다. 혼합은 top bail(기존 동작 유지).
  - **enum inter-field** — `extractConjunctions`가 커버(중복 회피).
  - NaN/Infinity 특수값 — 일반 유한 해만.

## 3. 접근
**계수 표현 = rational(`long num, long den`)로 일반화.** 정수는 `den=1`로 표현돼 기존 동작이 보존되고
(`Σ (num/den)·field + (cnum/cden)`), Z3 `mkReal(num, den)`로 **정확히**(double 반올림 없이) 모델링된다.
대안(별도 float 경로 분리)은 회귀가 더 안전하나 `Sym` 산술(add/sub/mul/neg/merge)을 통째로 중복해야 해
유지보수 부담이 크다 → rational 통합을 채택하되 **정수 경로 회귀를 단위+E2E로 철저히 가드**(§5).

1. **`Sym` 일반화**: `terms: Map<String, Long>` → `Map<String, Rational>`, `constant: long` → `Rational`.
   `Rational(num, den)` 불변 record(생성 시 gcd 약분 + den>0 정규화). 정수 리터럴/계수는 `Rational.of(long)`
   = `num/1`. `isReal` 플래그(필드 도메인): float/double 필드가 섞이면 Real 경로로 푼다.
2. **float/double 바이트코드 추가**(step): `FCONST_0/1/2`,`DCONST_0/1`,`FLOAD/DLOAD`,`FSTORE/DSTORE`,
   `FADD/DADD`,`FSUB/DSUB`,`FMUL/DMUL`(상수×변수만 선형, 변수×변수 bail),`FNEG/DNEG`,
   `FCMPL/FCMPG/DCMPL/DCMPG`(LCMP처럼 차를 push 후 IF* 가 rel 판정),`I2F/I2D/F2D/D2F/L2F/L2D`(도메인→real),
   `LDC` Float/Double 상수. float 접근자(`getPrice():F` 등)·`FLOAD`는 `isReal` 필드.
3. **solve 분기**: `solveBoundary`/`solveTuple`에서 비교의 항이 하나라도 real이면 `RealExpr`+`mkReal`
   경로(해는 소수 허용, 결과는 정수면 정수·아니면 소수 문자열), 전부 정수면 **기존 IntExpr 경로 그대로**(무회귀).
   Real 튜플도 `var≥ε`(작은 양수, all-zero 방지) + 합 최소화로 결정적·작은·in-range 값.
4. **후보 주입**: float 후보값은 `InputCandidates.numeric`(현재 `Map<String,Set<Long>>`)에 담을 수 없으므로
   `Map<String,Set<Double>>` 별도 채널 또는 numeric을 BigDecimal/Double로 확장 — §4 D3에서 결정.

## 4. 설계 결정
- **D1 rational 계수**(별도 float 경로 분리 대신): 우아·정확(Z3 Real exact)·혼합 도메인 자연 지원. 비용:
  `Sym` 산술 전면 수정 → 정수 경로 회귀 위험. 완화: §5 정수 회귀 가드(기존 booking 정수 inter-field E2E +
  단위) 필수 GREEN.
- **D2 도메인 추적(`isReal`)**: 필드 생성 지점(FLOAD/float 접근자/I2F 등)에서 마킹. 비교에 real 항이 있으면
  Real solve. 단일 비교 내 int·real 혼합은 비목표(top bail) — 경계 의미 보존.
- **D3 float 후보 채널**: `InputCandidates`에 `Map<String, Set<Double>> reals` 추가(numeric은 Long 유지,
  무회귀). 합성기(`ReadInputSynthesizer`/`SampleInputSynthesizer`)가 float 필드에 reals 후보 주입.
  튜플도 `tuples`(현재 `Map<String,Long>`)와 별도로 `realTuples`(`Map<String,Double>`).
- **D4 정확도**: Z3 Real 해는 rational(`RatNum`)→`BigDecimal`(DECIMAL64)→`Double`, finite만 채택. 단일필드 경계는
  `{B-ε, B, B+ε}`(ε=max(ulp(B),1e-3)) — `B+ε`가 한 방향 분기를 robust하게 연다.
- **D5 부등식 해의 경계 margin(impl에서 추가)**: `solveTupleReal`은 합 최소화 때문에 부등식 해가 제약 **경계
  (==0)에 수렴**한다. 경계의 real 튜플을 SUT가 float로 재계산하면 반올림으로 분기가 닫히는 **band-edge 취약성**이
  생긴다(E2E에서 `combined==99.5`가 float로 99.49999가 돼 422). → 부등식(GE/GT→`sum≥margin`, LE/LT→`sum≤−margin`,
  margin=1e-3)을 써 해를 half-plane **안쪽**으로 민다. 1e-3 ≫ ulp(100f) 이고 band 폭(1.0) ≫ margin 이라 안전.
  등식(EQ/NE)은 정확해만 존재해 margin 불가(float 등식 inter-field는 비목표, §2).

## 5. E2E / 수용 테스트 (정의된 done) — 구현 확정본
**outer-loop(먼저 RED)**: in-repo 결정적 벤치마크를 order-service에 추가 → 신규 `PricingController`.

`@PostMapping /api/pricing` 핸들러가 **상수×변수 2개의 순수 선형**(변수×변수 비선형 회피) **양측 band 가드**:
```java
float combined = req.base() * 2.0f + req.surcharge() * 3.0f;   // base, surcharge: float record 필드
if (combined < 99.5f)  throw 422;   // 하한
if (combined > 100.5f) throw 422;   // 상한
return 201;                          // band [99.5, 100.5]
```
설계 변경(원안 `== 100` 단일 등식에서 교체) 근거 2가지:
- **단측 부등식 금지(GPT I1)**: `combined < 100`은 happy 기본값/large 변이로 우연 충족돼 solver 없이 201이 열려
  귀속이 무너진다. **양측 band**로 가두면 한 필드만 바꾸는 generic 변이로는 두 필드를 동시에 band로 못 몰고,
  **Real solveTuple이 푼 (base,surcharge) 튜플만** 201을 연다.
- **float 등식/경계 취약성(impl에서 발견)**: float `==`나 band edge는 solver 해를 SUT가 float로 재계산할 때
  반올림(~ulp(100f)≈7.6e-6)으로 분기가 닫힌다. → **band 폭 1.0**이 반올림을 흡수하고, 추가로 **solveTupleReal이
  부등식 해를 경계에 붙이지 않고 안쪽으로 margin=1e-3 민다**(§4 D5). 둘이 함께 robust.

`BuilderIntegrationTest`(=문서의 BuilderE2eTest) 수용 단언:
- `post-api-pricing`가 201 path를 가진다(real 튜플 충족). real solve 없으면 422만 → 201 사라져 FAIL(회귀+ablation 가드).
- 201 입력의 두 float 필드가 band `99.5 <= base*2 + surcharge*3 <= 100.5`를 실제로 만족.
- ablation은 단위(`ConcolicFloatTest`)가 함께 보장: oracle이 realTuple을 안 내면 주입 자체가 없어 201 미발생.

**inner-loop(단위 TDD)**: `ConcolicOracle` 단위(또는 신규 `ConcolicFloatTest`) — (i) `FCMPL`+`IFGE`로 단일
float 필드 경계 추출, (ii) 두 float 필드 선형 `FADD(FMUL const,var)` inter-field → real 튜플, (iii) `DCMP`
double 경로, (iv) 변수×변수(FMUL var,var) → bail(튜플 없음), (v) **정수 경로 무회귀**: 기존 정수 비교가
동일 결과(회귀 픽스처). `Rational` 단위(약분/정규화/산술).

**done(A — in-repo, CI)**: 위 E2E + 단위 GREEN, order-service e2e 전체 GREEN(정수 inter-field booking 단언 유지).
**done(B — 외부 스윕)**: petclinic/대표 MSA builder — concolic bail 없이 정수 경로 동일 커버리지(무회귀),
크래시 0.

## 6. 회귀 (regression-on-sut-expansion)
- order-service: e2e 전체 + `BuilderE2eTest`(정수 booking inter-field `loyaltyPoints==nights*600+7` 단언이
  **그대로 GREEN** = rational화가 정수 경로를 안 깼다는 핵심 가드).
- petclinic/대표 MSA: builder 전 사이클 — 정수 concolic 커버리지 무변, float bail로 인한 분석 중단 0.

## 7. 위험
- **R1 rational화 정수 회귀**: `Sym` 산술 전면 수정 → 기존 정수 경로 손상. 완화: D1 den=1 보존 + §5 정수 회귀
  가드(단위 (v) + booking E2E) 필수.
- **R2 Z3 Real 비결정/성능**: Real Optimize가 느리거나 비유일 해. 완화: 기존 2s timeout + `var≥ε`·합최소화로
  결정적·작은 해. 정수 전용 비교는 Real 안 씀(성능 무영향).
- **R3 float 후보 채널 확장**(numeric Long→reals Double)이 합성기/직렬화 변경 유발. 완화: 별도 `reals` 채널
  추가(기존 numeric 불변) → 직렬화/소비 사이트 최소 변경.
- **R4 벤치마크 인위성**: 실측 SUT에 float inter-field 부재 → order-service 벤치마크가 유일 실증. 정직하게
  명시(이 기능은 향후 float-heavy SUT 대비 + 코어 일반화). 정수 경로 회귀 0이 더 중요한 가드.

## 8. 3-모델 리뷰 triage (Sonnet / Gemini 3.5 Flash / GPT-5.2)
세 모델 모두 approved_with_conditions/needs_revision — rational 일반화는 sound하나 ripple·정확성 함정·벤치마크
검증성에서 보강 필요로 일치. 전부 반영(이 작업이 예상보다 큰 코어+파이프라인 변경임을 확정).

**반영(critical):**
- **전제 조건 3종**(Sonnet I1/I7, Gemini I3): ① `seedParams`에 `Type.FLOAT(size1,REAL)`/`Type.DOUBLE(size2,REAL)`
  케이스 추가(현재 default→top), ② `isNumericReturn`/`isBoxedNumeric`에 FLOAT/DOUBLE/Float/Double 추가
  (float 접근자 인식), ③ `LDC` Float/Double → `Sym.constant(Rational, size)`(현재 else→top; FMUL 선형의 linchpin).
- **reals 채널 전수 change-site**(Sonnet I2, Gemini I2, GPT I6): `InputCandidates`(reals/realTuples + merge + tupleKey),
  `EndpointTarget`(realTuples 필드), `EndpointExplorationRunner`(추출·전달), `InputMutator.interField`
  (Double 주입 — 현재 `longValue()` 절단). 하나라도 빠지면 float 튜플이 fuzzer 미도달.
- **int↔float 혼합 = origin 추적 bail**(Gemini I1, GPT I2): `Sym.domain`(INT/REAL) 태그. field 생성 시 타입으로
  결정, `I2F/I2D`는 표현상 real이나 **origin은 INT 유지**. 한 비교에 INT-origin과 REAL-origin 항이 섞이면
  bail(top) — 정수 필드에 1.5 할당돼 JSON 역직렬화 깨지는 것 방지. 순수 INT 또는 순수 REAL 비교만 record.
- **벤치마크 solver-귀속 airtight = 등식 게이트 + ablation**(GPT I1): 부등식은 happy 기본값(boundedFloat 상한
  없으면 1000.0)으로 우연 충족돼 RED 무력. → **등식** 가드 `if (base*2 + surcharge*3 == 100) return 201;
  else 422`(정수 계수·float 필드). happy(1000,1000)=5000≠100 → 422, **Real solveTuple이 푼 정확 튜플
  (예 base=48.5,surcharge=1 → 100.0)만 201**. 추가로 ablation(oracle 끄면 201 소멸) 단언.

**반영(important):**
- **rational overflow 안전**(GPT I3): `long` 계수 산술에 `Math.multiplyExact/addExact` → overflow 시 즉시
  bail(`Sym.top`). 분자/분모 bit-length 상한 초과도 bail. (BigInteger는 성능상 미채택, 안전-bail로 대체.)
- **Z3 rational API·파싱**(Gemini I4, GPT I4): `ctx.mkReal(long num, long den)` numeral 생성, 모델 평가는
  `RatNum`으로 받아 `getBigIntNumerator()/getBigIntDenominator()` → `BigDecimal` → finite 체크 후 Double.
  분수 문자열 naive parse 금지.
- **경계 ε**(Sonnet I5, GPT I5): real 단일 경계는 `{B-ε, B, B+ε}`, `ε=Math.max(Math.ulp(B), 1e-3)`. 정수
  경계는 기존 `{B-1,B,B+1}` 유지. 솔버 하한은 `δ=1e-6`(기호 분리 — Sonnet I6).
- **NaN/Inf 거부**(GPT I7): 후보/튜플 값은 `Double.isFinite` 통과만. 비유한 → 후보 제외.
- **변환 opcode**(Sonnet I10): `F2I/D2I/D2L/L2F/L2D/I2F/I2D/F2D/D2F` 처리(도메인 전환 또는 top fallback) —
  미처리 `unhandled opcode` bail로 누적 비교 유실 방지.
- **done(B) 메트릭**(Sonnet I8): 대표 `.class` 픽스처에 대한 `Comparison` 추출 개수 snapshot 단위 테스트로
  정수 경로 무회귀를 CI 가드화(수동 스윕 의존 축소).

**비목표 확정(GPT I2/Gemini I5):** PATH/QUERY float 파라미터 합성(`scalarFor`)은 벤치마크가 body라 1차 비범위
(필요 시 후속). int↔float 혼합 비교는 bail(위 origin 추적).

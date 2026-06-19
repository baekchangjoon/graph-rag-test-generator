# 25 — 입력 발견 이론: 정적분석 · 심볼릭 · 콘콜릭 · ASM+Z3 · LLM 비교 (초급 가이드)

> 대상: 이 프로젝트에 처음 합류한 엔지니어. "분기를 여는 입력값을 어떻게 찾는가"라는 한 가지
> 질문을 중심으로, 관련 기법들을 비교하고 graph-rag-builder가 왜 지금의 선택을 했는지 설명한다.

## 0. 풀려는 문제 한 줄

> SUT(테스트 대상)의 어떤 분기 `if (조건)`을 **참/거짓 양쪽으로** 실행시키는 **입력값**을 자동으로 찾고 싶다.

예시:
```java
String classify(int amount) {
    if (amount * 2 == 84) return "lucky";   // 이 분기를 열려면 amount=42가 필요하다
    return "base";
}
```
`amount=42`는 소스 어디에도 안 적혀 있다(84와 2만 있음). 무작위로 던지면 거의 못 맞춘다.
"42를 어떻게 알아내느냐"가 이 문서의 주제다.

---

## 1. 정적 분석 (Static Analysis)

**정의**: 코드를 **실행하지 않고** 텍스트/구조만 분석. 두 층위가 있다.
- **소스(AST) 분석**: `.java`를 파싱해 트리로 본다. 우리는 **Spoon**을 쓴다.
- **바이트코드 분석**: 컴파일된 `.class`를 본다. 우리는 **ASM**을 쓴다.

**할 수 있는 것**: 엔드포인트 목록, 메서드 시그니처, `if`의 조건식, 소스에 박힌 리터럴(`if (x > 100)`의 `100`) 추출.

| 장점 | 단점/한계 |
|---|---|
| 빠르고 환경(DB·네트워크) 불필요 | 런타임에만 정해지는 값(설정/DB/계산결과)은 못 봄 |
| 결정적(같은 코드 → 같은 결과) | 리플렉션·동적 디스패치·프록시는 추적 어려움 |
| JVM 버전 추적 라이브러리(ASM) 사용 시 호환성 좋음 | "조건을 *만족*하는 값"을 *유도*하진 못함(리터럴을 읽을 뿐) |

→ 정적 분석만으로는 `amount*2==84`의 `42`를 **유도**하지 못한다. `84`,`2`만 읽는다.

---

## 2. 심볼릭 실행 (Symbolic Execution)

**정의**: 입력을 구체값이 아니라 **기호(symbol)** `α`로 두고 프로그램을 "기호로" 실행한다. 각 분기에서
**경로 조건(path condition)** 을 수식으로 쌓는다. 예: `amount`를 `α`로 두면, `if (amount*2==84)`의
참 분기 경로조건은 `2·α == 84`. 이 수식을 **SMT 솔버**(아래)로 풀면 `α=42`가 나온다.

| 장점 | 단점/한계 |
|---|---|
| **정확**: 조건을 만족하는 값을 *유도*(계산이 끼어도 풀어냄) | **경로 폭발**: 분기 N개 → 경로 2^N, 루프는 무한 |
| 비-리터럴/파생값도 해결 | **환경 모델링** 필요: native·JDK·DB·네트워크 호출을 기호로 다루려면 "모델"을 작성해야 함 |
| 도달 불가 경로 증명 가능 | 도구가 무겁고 JVM 버전을 잘 못 따라감(예: JPF/SPF는 Java 8–11) |

대표 도구: **Symbolic PathFinder(SPF)** — JPF(특수 JVM) 위에서 동작.

---

## 3. 콘콜릭 실행 (Concolic = Concrete + Symbolic)

**정의**: 프로그램을 **실제(concrete) 입력으로 진짜 실행**하면서, *동시에* 경로 조건을 **기호로** 수집한다.
한 분기의 조건을 **부정(negate)** 해서 SMT로 풀면 → "다른 가지로 가는 새 입력"을 얻는다. 이걸 반복해
새 경로를 점점 넓힌다.

비유: 미로를 실제로 걸으며(concrete) 갈림길마다 "여기서 반대로 가려면 어떤 열쇠가 필요한지"를
메모(symbolic)하고, 그 열쇠를 솔버로 만들어 다음엔 반대 길로 간다.

| 장점 | 단점/한계 |
|---|---|
| 심볼릭의 정확성 + 실제 실행이라 환경 모델링 부담↓ | 여전히 **계측(instrumentation)** 필요 — 프로그램을 실행하며 경로조건을 수집해야 함 |
| 솔버가 막히면 구체 실행값으로 진행(견고) | in-process 실행 전제가 많아 Spring full-context·모던 JVM과 궁합 이슈 |
| 무작위 fuzzing보다 깊이 도달 | taint(값↔입력 역추적)·솔버 통합이 복잡 |

대표 도구: **JDart**(JPF 기반), CATG/jCUTE(연구용).

---

## 4. SMT 솔버 / Z3

**SMT**(Satisfiability Modulo Theories) = "이 제약식들을 동시에 만족하는 값이 있나? 있으면 하나 줘".
**Z3**(Microsoft)가 대표. 정수·실수·비트벡터·**문자열**·배열 등 "이론(theory)"을 안다.

예: `2·α − 84 == 0` 입력 → Z3 출력 `α = 42`. 만족 불가면 `UNSAT`(예: `2·α == 85` → 정수해 없음).

심볼릭/콘콜릭이 "수식을 만들고", Z3가 "수식을 푼다". 둘은 짝이다.

---

## 5. ASM (바이트코드 라이브러리)

**ASM**(`org.ow2.asm`) = JVM `.class` 바이트코드를 읽고/쓰는 표준 라이브러리. 스택 머신 명령
(`ILOAD`, `IMUL`, `IF_ICMPEQ` …)을 트리/방문자로 다룬다. **JVM 새 버전을 빠르게 따라간다**(Java 17/21/23 OK).
우리는 ASM으로 SUT의 boot jar 바이트코드를 "기호로 훑어" 분기 조건을 추출한다.

---

## 6. 우리의 "ASM+Z3" (`oracle/ConcolicOracle`) — 정확히 무엇인가

**한 줄**: SUT 바이트코드를 ASM으로 **정적·심볼릭하게** 훑어, 입력 필드에서 파생된 **정수 선형식**
`coeff·field + const`를 추적하고, 각 분기의 경계 `coeff·field+const==0`을 **Z3로 풀어** 경계값
`{B-1, B, B+1}`을 입력 후보로 낸다. 문자열 길이(`field.length()==5 → "xxxxx"`)도 같은 방식.

```
바이트코드: ILOAD amount; ICONST_2; IMUL; BIPUSH 84; IF_ICMPEQ
   심볼릭 스택:  amount(=1·f) → ×2 → 2·f → vs 84  → 비교식 (2·f − 84)
   Z3:  2·f − 84 == 0  →  f(amount) = 42   →  후보 {41, 42, 43}
```

**용어 주의(중요)**: 우리 것은 이름이 `ConcolicOracle`지만, 엄밀히는 **정적(static) 심볼릭/추상해석**이다
— 프로그램을 *실제로 실행하며* 경로조건을 모으는 진짜 콘콜릭이 아니라, 바이트코드를 *읽어서* 수식을 만든다.
**"콘콜릭"은 시스템 전체 수준에서 성립**한다: 오라클(심볼릭)이 입력을 *제안*하고, graph-rag-builder가
그 입력으로 SUT를 *실제 HTTP 실행*(concrete)해 커버리지로 확정한다 → 합치면 concrete+symbolic.

| 장점 | 단점/한계 (1차 구현) |
|---|---|
| 비-리터럴 값 유도(`x*3==21→7`, `bonus*2==1e10→5e9`) | **intra-method, 단일 필드, 정수 선형**만. 비선형·다변수·실수는 보수적 skip |
| ASM+Z3라 **버전 rot 없음**, 우리가 통제 | 문자열 동치/정규식(Z3 string theory) 미구현 |
| 결정적, 후보를 기존 변이 파이프라인에 합류 | enum 순서, 메서드 간(interprocedural) 전파 미구현 |
| in-process 실행 불필요(바이트코드만 읽음) | 불투명 값(`hashCode`) 불가 |

자세한 지원 범위: `docs/24-exploration-backends-and-input-oracle.md`, `docs/22-static-discovery-limits.md`.

---

## 7. graph-rag-builder 전체 접근

핵심 설계: **out-of-process 블랙박스 + 입력 오라클 + arm-level 커버리지**.

```
SUT를 운영 boot jar 그대로 외부 프로세스로 기동(HTTP 경계)
  ← 입력: happy 합성 + (generic 경계 변이 ⊕ InputOracle 후보[static-literal + concolic])
  → 관측: 요청 단위 JaCoCo exec data 누적 병합 = arm-level 커버리지, probe 지문으로 path 식별
  → 새 분기를 연 입력을 시드로 환류(coverage-guided), 그래프(graph.json) 산출
```

**왜 이렇게?**
- **in-process 심볼릭/콘콜릭(JPF류)을 안 씀**: Spring full-context + heterogeneous JDK(Java 11/17/23)에서
  JPF가 못 돈다(버전 lag). 그래서 "관측은 외부 HTTP, 입력 발견만 ASM+Z3 정적"으로 분리.
- **교체 가능 인터페이스(`InputOracle`)**: 정적 리터럴/콘콜릭/(미래)EvoSuite·심볼릭을 갈아끼움.

| 장점 | 단점/한계 |
|---|---|
| 운영 jar 그대로 → **충실도 높음**(실제 Spring/JPA/MyBatis 동작 관측) | **결합** 다변수·상태 의존 가드엔 여전히 약함(아래 §9) |
| 결정적, JVM 버전 비종속 | (Stage 1/2로 개선) **독립** 원자 conjunction은 joint 변이로 동시충족하나, 한 식에서 상호작용하는 결합 다변수는 미해결 |
| 실제 SQL/외부HTTP/시드까지 캡처 → 통합테스트 생성 가능 | (Stage 0으로 해결) enum/날짜/이메일 유효 값 합성은 이제 됨 |

> **갱신(2026-06-15)**: 아래 §9가 이 표의 "한계"를 단계별로 어떻게 좁혔는지(Stage 0–3b) 정리한다.

**관측 경계는 HTTP만이 아니다 (2026-06-15)**: "out-of-process 관측"의 진입점은 HTTP 엔드포인트에
국한되지 않는다. 이벤트 구동 SUT는 메시지로도 코드가 실행된다 — 그래서 빌더는 **@KafkaListener
consumer**(토픽에 유효 이벤트 발행)와 **STOMP/WS 핸들러**도 같은 JaCoCo dump 모델로 관측한다. 각
캡처가 baseline+delta dump로 핸들러 커버를 떠서 전역 커버리지(runWideExec)에 병합하므로, exploration
커버리지는 HTTP+consumer+WS를 합산한다. (SQL을 안 쓰는 Redis consumer도 핸들러 분기는 잡힌다.)
이를 빠뜨리면 "consumer가 실제로 돌아도 커버리지 0"이 되어 기능을 과소평가한다. 상세: `docs/24` 말미.

---

## 7.5 입력 파라미터를 조합·생성하는 과정 (solver · fuzzer · seed) — 예제

초급자가 가장 헷갈리는 부분이다. "한 endpoint의 입력 파라미터들을 **어떻게 만들고 어떻게 조합**하나",
그리고 **solver / fuzzer / seed**가 각각 무슨 역할인지 한 예제로 끝까지 따라가 보자.

### 예제 SUT — `POST /api/bookings` (order-service의 Booking)

```java
record CreateBookingRequest(String customerEmail, Integer nights, Integer loyaltyPoints,
                            BookingTier tier, LocalDate checkInDate) {}   // tier = {BASIC, VIP}

if (nights < 1 || nights > 30)                       throw 422;          // ① 단일 숫자 범위
if (tier == null)                                    throw 422;          // ②
if (tier == VIP && loyaltyPoints < 500)              throw 422;          // ③ 다필드 conjunction
if (!EMAIL.matches(customerEmail))                   throw 422;          // ④ 이메일
if (!checkInDate.isAfter(now()))                     throw 422;          // ⑤ 날짜
// 모두 통과 → 201 created
```
목표: 위 분기들의 **양쪽(통과/실패)** 을 다 실행시키는 입력 조합들을 자동 생성.

### 1단계 — happy 기준 입력 1개 합성 (출발점)

`SampleInputSynthesizer`가 **유효한 한 벌**을 만든다(Stage 0). 이게 모든 조합의 baseline:
```
base = { customerEmail:"probe@example.com", nights:1, loyaltyPoints:1,
         tier:"BASIC", checkInDate:"2999-01-01" }      → 201 (모든 가드 통과)
```
포인트: enum은 첫 상수(BASIC), 날짜는 미래 ISO, 이메일은 정규식 통과값. 이게 안 되면 ④⑤에서 막혀
③ 같은 깊은 가드에 **도달조차** 못 한다.

### 2단계 — 정적 분석 + solver가 "후보 값"을 만든다 (값 그 자체)

코드를 읽어(실행X) 각 필드에 넣어볼 **의미 있는 값**을 뽑는다:

| 출처 | 산출 후보 | 비고 |
|---|---|---|
| `extractComparisons` + 경계 펼침 | `nights ∈ {0,1,2, 29,30,31}` | ① 경계값 (리터럴 1,30에서) |
| `EnumConstantExtractor` | `tier ∈ {BASIC, VIP}` | enum 상수 |
| `extractConjunctions` | `{tier==VIP, loyaltyPoints<500}` 묶음 | ③ 다필드 가드 |
| **`ConcolicOracle`(ASM+**Z3**)** | (파생 가드 있을 때) `score*2==84 → 42` | **solver가 유도** |

**solver(Z3)의 역할**: 소스에 **안 적힌** 값을 *수식을 풀어서* 만든다. Booking엔 파생 가드가 없어 안 쓰이지만,
`if (amount*2 == 84)` 같은 게 있으면 ConcolicOracle가 `2·f−84==0`을 Z3로 풀어 `f=42`를 후보로 낸다(§6).
즉 solver는 "**어떤 값**을 넣어야 이 등식이 성립하나"를 답한다 — fuzzer가 무작정 못 맞추는 값.

### 3단계 — 변이 카탈로그로 "조합 규칙"을 만든다 (`InputMutator.forTarget`)

후보들을 **base에 적용하는 변형(mutation) 목록**으로 바꾼다. 각 mutation은 "base를 받아 한 군데(또는
conjunction이면 여러 군데)를 바꾼 새 입력을 돌려주는 함수":
```
constraintDirected : bound-nights-0, bound-nights-1, bound-nights-30, bound-nights-31 …
enumValues         : enum-tier-BASIC, enum-tier-VIP                         (enum 각 상수)
joint              : joint-…-loyaltyPoints_tier  →  {tier:"VIP", loyaltyPoints:499} 동시 세팅
firstOrder(generic): remove-/null-/zero-/negative-/large- (필드별, 값 무지성)
```
**우선순위**: 고신호(constraint/enum/joint)를 generic firstOrder **앞**에 둔다 — 예산이 적을 때 무지성
변이가 의미 있는 변이를 굶기지 않게.

### 4단계 — fuzzer가 입력을 실제로 돌려 조합을 넓힌다 (2개 엔진)

"fuzzer"는 두 엔진의 협업이다. **둘 다 같은 변이 목록을 쓰되 적용 대상이 다르다**:

**(a) `HeuristicExplorer` — 1차(1st-order)**: 각 변이를 **base 하나에** 적용 → 요청 1개씩.
```
base + bound-nights-31   → {…, nights:31}             → 422 ① (nights>30)
base + enum-tier-VIP     → {…, tier:VIP, loyalty:1}   → 422 ③ (VIP && 1<500)   ← base의 loyalty=1이 이미 <500
base + joint(VIP,499)    → {…, tier:VIP, loyalty:499} → 422 ③
base + bad email 변이    → …                          → 422 ④
base (변이 없음)         →                            → 201
```
각 요청마다 JaCoCo로 **이 요청이 연 분기(arm)** 를 dump한다.

**(b) `CoverageGuidedFuzzer` — 2차 이상(higher-order)**: **"새 분기를 연 입력"만 시드 큐에 모아**,
그 위에 변이를 **또** 쌓는다. 즉 조합이 1군데→2군데→…로 깊어진다.

> 왜 필요한가(핵심 예 — petclinic): 가드가 **순차**라 깊은 가드는 앞 가드를 다 통과해야 닿는다.
> `base`에 roomNumber만 무효라 하자. `bound-roomNumber-100`을 base에 적용하면 **앞 가드 전부 통과한
> 입력**이 되고, 이게 새 분기를 열어 **시드**가 된다. 다음 라운드에서 그 시드 위에 `enum-tier-VIP`를
> 얹으면 → roomNumber 유효 **AND** tier=VIP가 **동시에** 성립 → ③ true-arm 도달. 1차 변이 하나로는
> 못 닿고, **시드를 통한 조합 누적**으로 닿는다. (joint 변이는 이 조합을 한 방에 만들어 견고화.)

"**novel(새 분기)이면 시드로 환류 → 그 위에 또 변이**"가 무작위 fuzzing보다 깊이 들어가는 비결이다.
새 분기가 더 이상 안 나오면(연속 N회 dry) 그 endpoint 탐색을 끝낸다(saturation).

### 5단계 — "seed"는 두 가지다 (반드시 구분)

| 종류 | 무엇 | 누가 | 예 |
|---|---|---|---|
| **DB seed**(`SeedRow`) | 분석 DB에 미리 넣는 **행** | synthesizer + `Seeds.insert` | GET/PUT/DELETE `/{id}`가 읽을 booking 행. (mutating by-id는 요청마다 `resetSeeds`로 fresh 복원 — Stage 3b) |
| **explorer seed**(`KnownCoverage.Seed`) | **새 분기를 연 입력**(요청 body) | CoverageGuidedFuzzer | 위 4(b)의 `base+bound-roomNumber-100` 입력 |

이름이 같아 헷갈리지만, 하나는 **DB 데이터**(읽을 대상), 하나는 **입력 조합의 발판**(다음 변이의 base)이다.

### 한 줄 요약

> **solver**(Z3) = *값*을 푼다(소스에 없는 값까지). **변이 카탈로그** = 그 값들을 base에 적용하는 *규칙*.
> **fuzzer** = base/시드에 변이를 적용해 실제로 *돌려보고*, **novel이면 시드로 환류**해 조합을 깊게 쌓는다.
> **seed** = (DB 행) 읽을 데이터 / (explorer) 다음 조합의 발판. 모두 결정적(Random/시간 없음).

---

## 8. "LLM에게 직접 코드를 주고 입력/테스트를 만들라" vs 우리 접근

| 관점 | LLM 직접 요청 | graph-rag-builder (정적분석 + ASM/Z3 + 실행관측) |
|---|---|---|
| 입력 유도 방식 | 패턴 추론(학습된 직관) | 수식화 + Z3 풀이 + 실제 실행 검증 |
| **결정성/재현성** | 낮음(같은 요청도 달라질 수 있음) | **높음**(같은 코드 → 같은 결과) |
| **정확성 보장** | 환각 가능(그럴듯하지만 틀린 값) | Z3가 만족성 보장, 실행으로 확정 |
| 환경 실행/관측 | 없음(코드만 봄) | **실제 SUT 실행** → SQL/HTTP/커버리지 관측 |
| 복잡·창의적 추론 | **강함**(자연어 명세, 도메인 의미, 다변수도 시도) | 약함(정해진 기법 범위) |
| 비용/속도 | 토큰 비용·지연 | 로컬 분석, 토큰 0 |
| 한계 | 검증 없으면 신뢰 어려움 | 기법 범위 밖(다변수/의미)은 못 함 |

**공통점**: 둘 다 "코드를 보고 분기를 여는 입력을 추론"하려 한다.
**차이의 핵심**: 우리는 **결정적·검증가능·실행관측**을 택해 신뢰성을 얻는 대신, LLM의 **유연한 의미 추론**을
포기했다. (그래서 이 프로젝트는 도구 안에 LLM을 두지 않는다 — 결정성이 1순위.) 둘은 상호보완적이며,
미래엔 "LLM이 어려운 다변수/의미 케이스의 후보를 제안 → 우리 파이프라인이 실행으로 검증"하는 하이브리드도 가능.

---

## 9. 정리 — 언제 무엇을 쓰나 + 단계별로 좁힌 한계

기법별 사용처:
- **소스에 리터럴로 박힌 조건** → 정적 리터럴 추출(StaticLiteralOracle)로 충분.
- **계산/파생된 단일 변수 조건**(`x*2==84`) → ASM+Z3 concolic이 유도.
- **enum 값·날짜·이메일 유효 입력** → 합성(Stage 0): enum 첫 상수, ISO 날짜, 유효 이메일.
- **메서드 내 `&&` 다필드 가드(독립 원자)**(`tier==VIP && loyalty<500`) → conjunction 추출 + joint 변이
  (Stage 1/2). SMT 불요 — 원자별 만족값을 동시 세팅.
- **by-id 상태(읽기/수정/삭제가 사전 데이터 의존)** → path-id+리소스 시드 + 요청별 시드 리셋(Stage 3/3b).

**단계별로 좁힌 한계 (petclinic `ReservationService` 벤치마크, coveredAppBranches)**:

| 단계 | 무엇을 풀었나 | 측정 |
|---|---|---|
| Stage 0 | 유효 enum/날짜/이메일로 역직렬화 통과 → service 검증 진입 | 33→47/253 |
| Stage 1/2 | `tier==VIP && loyalty<500` 등 독립 다필드 가드 true-arm | 47→69/253 |
| Stage 3 | by-id(GET/PUT/DELETE /{id}) 진입 + 시드 읽기(enum 컬럼) | 69→113/253 |
| Stage 3b | mutating by-id 생성 테스트가 빈 DB에서 재현(시드 리셋) | by-id 16/16 통과 |

> **먼저 두 용어 (초급자용)**:
> - **arm(분기 갈래)**: `if (cond)`는 갈래가 둘 — `cond`가 참일 때 실행되는 **true-arm**, 거짓일 때의
>   **false-arm**. JaCoCo branch(arm-level) 100%는 **두 arm을 모두 실행**해야 한다. 한 입력은 보통
>   한 arm만 찍으므로, 나머지 arm을 여는 **다른 입력 또는 다른 시드 데이터**가 따로 필요하다.
> - **단일필드 vs inter-field(필드 간) 제약**: `nights >= 1`처럼 **한 필드만** 보는 조건은 그 필드 값을
>   독립적으로 고르면 끝(현 휴리스틱이 처리). 반면 `deposit*1.1 < nights*rate`처럼 **여러 필드를 한 식에
>   엮는** 조건(inter-field)은 필드를 따로 못 정한다 — 셋이 부등식을 **동시에** 만족해야 하므로 연립으로
>   풀어야 한다(= Z3 같은 SMT 솔버의 일).

**Stage 4 — 일부 정공 (2026-06-15)**:
- ✅ **상태 의존 가드 양 arm(저장된 단일 행) — 정적 StateGuardOracle로 해결**: `getter().isBefore/
  isAfter(now)`(TEMPORAL)·`getter() != A && != B`(ENUM) 가드를 정적 인식하고, 반대 arm을 여는 **대체 시드
  행 변종**(과거 1900-01-01 날짜 / 부정집합 밖 enum 상수)을 합성해 by-id 요청으로 구동한다. **런타임
  에이전트 불요**(flip 값이 solve가 아니라 고정 상수). order-service GET stale 404 / DELETE conflict 409 arm을
  missed→covered(branch 85% 106/124). `docs/superpowers/plans/2026-06-15-stage4-state-guard-two-arm-seeds.md`.
  - 여전히 보류: **집계/capacity 다중 행**(`COUNT(status==CONFIRMED)>=cap`)·인식 안 되는 임의 상태 가드
    (계산형·cross-entity)는 in-process concolic 라인(PoC `.work/concolic-poc/`)의 몫.
- ✅ **2-필드 선형 inter-field — Z3 `solveTuple`로 해결**: `ConcolicOracle`의 `Sym`을 2개 필드까지 선형식
  `Σ(coeff·field)+const`로 확장하고(3개째·진짜 곱은 bail), 비교 opcode를 threading해 두 필드를 동시충족하는
  정수 튜플을 Z3로 푼다(합 최소화 → 작은·in-range 값). `InputMutator.interField`가 한 atomic 변이로 적용.
  결정적 in-repo 승리: order-service `loyaltyPoints == nights*600+7` → 튜플 (607,1)만 201을 열며 필드별
  변이로는 불가(BuilderIntegrationTest 단언). `docs/superpowers/plans/2026-06-15-stage4-z3-interfield-solver.md`.

**여전히 미해결(Stage 4 잔여)**:
- **결합 다변수 중 비선형·interprocedural**(`deposit*1.1 < nights*priceTier.getNightlyRate()`):
  float 계수(rational 스케일)·enum 메서드 grounding(`priceTier.getNightlyRate()`)·3+변수는 **best-effort/보류**
  (petclinic Reservation은 아직 휴리스틱 의존). 2-필드 **정수 선형**은 위 ✅로 정공.
- **정규식 일반 생성·불투명 값**(`hashCode`): solver로도 어려움 / 영구 비목표.

관련 문서: `docs/22`(정적 한계), `docs/23`(입력 생성 흐름), `docs/24`(탐색 백엔드·단계별 진행).

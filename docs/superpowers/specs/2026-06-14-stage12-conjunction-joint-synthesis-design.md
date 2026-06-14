# Stage 1+2 — conjunction 추출 + joint 입력 합성 — 설계

작성일: 2026-06-14 (v2 — opus/sonnet/haiku 검토 반영)
관련: docs/24(탐색 백엔드), Stage 0 spec/결과, petclinic boarding 실측
범위 결정: 사용자 선택 = **Lean(conjunctive 우선)**. interprocedural ASM 데이터플로우/비선형 SMT는 비목표.

## 배경 / 문제 (실측)

Stage 0 이후 petclinic `ReservationService`의 **단변수 가드**(nights/roomNumber/animalCount/petName/
priceTier-null L49–70)는 기존 오라클(소스 리터럴 → boundary 변이) + seed 누적으로 도달.
남은 미달성의 핵심은 **한 `if`의 `&&`로 2개+ 필드가 동시에 특정 조합이어야 열리는 분기**:

- `ReservationService.java:64` — `priceTier == PriceTier.VIP && loyaltyPoints < 500`
  - 두 필드 독립(enum 1 + int 1). 막힌 진짜 이유 — **1차 원인은 enum 변이 부재**, conjunction은 보조:
    1. **(1차) enum 필드가 변이 대상에서 제외**(`firstOrder`는 numeric/String만; enum은 remove/null만).
       게다가 `priceTier == PriceTier.VIP`는 enum 상수 `==` 비교라 `extractComparisons`(정수 리터럴만,
       `addComparison`이 `literalLong` 요구)도 `extractStringEqualities`(`.equals` invocation만)도
       **추출하지 못함** → "VIP"가 어떤 후보에도 안 들어옴. **검토 확인**(opus/sonnet): L64는
       `CtBinaryOperator(EQ)`이고 `PriceTier.VIP`는 `CtFieldRead`(숫자 리터럴 아님)라 현행 두 추출기
       모두 미포착. → **enum 상수 변이만 추가해도** loyaltyPoints happy 기본값(=1, <500)이 이미 참이라
       `priceTier=VIP`를 (선행 가드 통과한 seed 위에) 얹으면 L64 true-arm 도달 가능.
    2. **(보조) joint**: `InputMutator`가 한 번에 한 필드만 변이 → 기본값이 다른 원자를 만족 못 하는
       일반 conjunction(예: loyaltyPoints 기본값이 500↑인 SUT)은 enum 변이만으론 못 엶. joint(동시
       세팅)이 그 일반 케이스를 견고히 커버. conjunction 추출은 **어떤 필드들을 함께 세팅할지**를
       알려줘 joint 조합 폭발을 의미 있는 그룹으로 한정한다.

## 목표

1. **Stage 1**: 메서드 내 `&&` 조건을 **conjunction 단위로 추출**(원자들의 동시성 보존). 원자 종류 3개:
   정수 비교(`field op L`), enum 동치(`field == Enum.CONST`), 문자열 동치(`field.equals("v")`).
2. **Stage 2**: 추출된 conjunction을 **joint mutation**(원자 필드들을 동시에 만족값으로 세팅)으로,
   그리고 enum 필드 일반에 대해 **enum-상수 변이**(선언된 각 상수 시도)로 환류한다.

→ `priceTier=VIP` + `loyaltyPoints=499`를 **한 입력으로 동시** 세팅 → L64 true-arm 도달.

## 비목표 (명시 — 범위 결정 반영)

- **interprocedural ASM 데이터플로우 / call summary**: `depositAmount*1.1 < nights*priceTier.getNightlyRate()`
  (L73, 비선형 곱셈 + getNightlyRate 메서드간 + 3변수 결합)는 **안 연다**. 비용 대비 ROI 낮음.
- **다변수 SMT(Z3 multi-var)**: conjunction 원자들이 **서로 독립**(각 원자가 단일 필드)일 때는
  SMT 불필요 — 원자별 만족값을 독립 선택해 합치면 됨. 원자들이 **결합**(같은 식에서 상호작용)하는
  경우(L73류)는 비목표.
- **DB 상태 의존 가드**(`countByRoomNumberAndStatus >= ROOM_CAPACITY` L76/L138): 다중 행 시딩 필요 →
  범위 밖.
- **불투명 가드**(`Math.floorMod(promoCode.hashCode(),7)==3` L84): Z3로도 못 풂 → 범위 밖.
- **`||` 분해**: 범위 가드 `nights<1 || nights>30`은 단일 필드 → 기존 boundary 변이가 이미 처리.
  conjunction 추출은 **`&&`만**, 그리고 **2개+ 서로 다른 필드를 참조하는 것만** 보존(단일 필드
  conjunction은 기존 변이와 중복이라 버림).
- **valid-prefix 전체 합성**: joint mutation은 **자신의 원자 필드만** 세팅. L64 앞선 단변수 가드
  (roomNumber 등)는 **CoverageGuidedFuzzer의 seed 누적**으로 통과한 body 위에 joint를 얹어 도달한다
  (아래 §reachability). 모든 선행 가드를 joint가 직접 채우는 완전 valid-body 합성은 향후 과제.

## 설계

### Stage 1 — `ConstraintExtractor.extractConjunctions(Path srcDir)`

신규 메서드. 전 계층 1회 빌드(Launcher noClasspath/complianceLevel 17, 기존과 동일). 반환 `List<Conjunction>`.

신규 레코드(같은 파일, 기존 `Comparison`/`StringEquality` 레코드 옆에 추가):
```java
public record Conjunction(String classFqn, String method, int line, List<Atom> atoms) { }
public record Atom(Kind kind, String fieldRef, String op, long numLiteral, String value) {
    public enum Kind { NUMERIC, ENUM_EQ, STRING_EQ }
}
```
- 비-NUMERIC 원자는 `op="=="`, `numLiteral=0`(무의미; joint는 `kind`로 분기하고 NUMERIC만 numLiteral 읽음).

추출 절차 (**검토 반영 — 중복 방지가 핵심**):
- `model.getElements(new TypeFilter<>(CtIf.class))` + `CtConditional`로 조건식 노드를 모은다.
  각 노드의 `getCondition()`을 **루트로** 본다. **`getElements(CtBinaryOperator)`로 전체 AND를
  훑지 않는다** — 그러면 `(A&&B)&&C`가 두 AND 노드로 잡혀 conjunction이 중복된다(sonnet CRITICAL-1).
- 루트 조건이 `CtBinaryOperator(BinaryOperatorKind.AND)`인 것만 대상.
- `&&`를 **재귀 평탄화**(AND면 좌/우 재귀, 아니면 leaf)해 leaf 원자 리스트 수집 — `List`로 **선언 순서
  보존**(결정성). `A && B && C` → [A,B,C].
- 각 leaf를 원자로 변환(가능한 것만; 불가 leaf는 그 leaf만 skip — `||` 하위식 등은 자연히 탈락):
  - **NUMERIC**: `field op intLit` / `intLit op field`. **기존 `addComparison`의 정규화(리터럴 우변,
    좌변이면 `FLIP`)를 그대로 재사용**해 `op`가 항상 field-on-left 정규형이 되게 한다(sonnet CRITICAL-2).
    `literalLong`이 Double/Float을 배제하므로 **정수 리터럴만**(double 비교 원자는 비포착 — 한계, 아래
    위험 참조). → `Atom(NUMERIC, fieldRef, normOp, literal, null)`.
  - **STRING_EQ**: `field.equals("v")` / `"v".equals(field)`. 기존 `extractStringEqualities` 판별 로직
    재사용 → `Atom(STRING_EQ, fieldRef, "==", 0, "v")`.
  - **ENUM_EQ**(신규): `field == Type.CONST` / `Type.CONST == field`. 판별: 한 변이 `fieldRef(...)`로
    추출 가능(`CtInvocation` 접근자 또는 `CtVariableRead`), 반대 변이 **`CtFieldRead`이고 그
    `getTarget()`이 `CtTypeAccess`**(= `Type.CONST` 정적 상수 읽기). 값 = 그 `CtFieldRead`의
    `getVariable().getSimpleName()`(= "VIP"). → `Atom(ENUM_EQ, fieldRef, "==", 0, "VIP")`.
    **noClasspath 동작은 Task의 첫 단위 테스트(픽스처 `a == E.X && b < N`)로 선검증**한다 — Spoon이
    `CtTypeAccess`/simpleName을 주지 못하면 그 자리서 판별식을 조정(opus G1/sonnet MAJOR-3).
- **필터**: 원자 중 **서로 다른 fieldRef가 2개 미만**이면 conjunction 버림(단일 필드는 기존 per-field
  변이로 충분). L64는 priceTier+loyaltyPoints 2필드라 통과(검토 확인).
- 결정적 정렬: classFqn → method → line.

`extract`(handler ConditionSpan)·`extractComparisons`·`extractStringEqualities`는 **불변**.

### Stage 2 — `InputMutator` joint/enum 변이 + `EndpointTarget` 확장

`EndpointTarget`(record) canonical 생성자에 2개 필드를 **맨 끝**에 추가 → 11-인자가 됨:
```java
Map<String, List<String>> enumConstants,          // enum FQN → 상수(선언순). Stage 0 추출물 재사용.
List<ConstraintExtractor.Conjunction> conjunctions // 전 계층 conjunction(필드명으로 매칭)
```
**검토 반영(opus M1/sonnet MAJOR-1)**: 기존 보조 생성자 2개(`EndpointTarget.java:25-30`, `32-38`)의
`this(...)` 위임도 새 2인자 `Map.of(), List.of()`를 **각각 덧붙여야** 컴파일된다(누락 시 빌드 실패).

`InputMutator.forTarget(target)`에 두 변이 소스 추가(기존 firstOrder/constraintDirected 뒤, dedupe 전):
```java
all.addAll(enumValues(target.mutableFields(), target.enumConstants()));
all.addAll(joint(target.mutableFields(), target.conjunctions()));
```

**enum-상수 변이** `enumValues(fields, enumConstants)`:
- 각 필드의 `javaType()`이 `enumConstants` 키면(또는 simple-name 폴백, SampleInputSynthesizer와 동일
  규칙) 각 상수 `c`마다 `Mutation("enum-"+name+"-"+c, body -> body.put(name, c))`.

**joint 변이** `joint(fields, conjunctions)`:
- `fieldNames = mutableFields의 name 집합`(문자열 동일성 매칭 — conditionBounds와 동일 방식).
- 각 conjunction: **모든** 원자의 `fieldRef`가 `fieldNames`에 존재할 때만 채택(부분 매칭 버림 — 일부
  필드가 body에 없으면 그 가드는 이 endpoint 입력으로 제어 불가).
- 채택 시 **단일 Mutation**: 원자 리스트를 순회하며 `kind`로 분기해 차례로 put(한 `UnaryOperator`):
  - `case NUMERIC` → `body.put(field, satisfy(op, L))`, `satisfy`: `<`→L-1, `<=`→L, `>`→L+1, `>=`→L,
    `==`→L, `!=`→L+1 (op는 field-on-left 정규형 보장). NUMERIC만 numLiteral 읽음.
  - `case ENUM_EQ`/`case STRING_EQ` → `body.put(field, value)`(상수명/문자열 그대로).
  - 예: L64 conjunction → `body.put("priceTier","VIP"); body.put("loyaltyPoints", 499);`
  - 이름: `"joint-" + simpleClass + "-" + line + "-" + 원자 fieldRef들(정렬·join)`. **검토 반영
    (opus G3/sonnet MAJOR-2)**: line만 쓰면 같은 클래스·같은 라인의 서로 다른 conjunction이 동일 이름이
    되어 `dedupeByName`이 하나를 소실시킨다 → fieldRef를 이름에 포함해 유일성 확보. `simpleClass` =
    classFqn의 마지막 `.` 뒤 토큰.
- 한 conjunction = 한 joint mutation(true-arm 도달용). false-arm은 happy/기존 단일 변이가 이미 커버.

기존 `firstOrder`/`constraintDirected`/`dedupeByName`은 불변. 새 변이도 `dedupeByName`으로 합류.

### 배선 (BuilderCli → EndpointExplorationRunner.run → EndpointTarget)

전체 경로(검토 반영 — sonnet MAJOR-4, 빠짐없이):
1. `BuilderCli`: `allComparisons` 추출부 근처에서 1회
   `List<Conjunction> allConjunctions = constraintExtractor.extractConjunctions(config.sutSrc());`
2. `EndpointExplorationRunner.run(...)` 시그니처(현재 7-인자, `EndpointExplorationRunner.java:115-119`)에
   `List<ConstraintExtractor.Conjunction> conjunctions`를 **마지막 인자**로 추가.
3. `run()` 내부의 `EndpointTarget` 생성부(`EndpointExplorationRunner.java:161-163`)에 `enumConstants`
   (이미 runner 필드, Stage 0)와 `conjunctions`(run 인자)를 **마지막 2개 인자**로 전달.
4. `BuilderCli`의 유일한 호출부 `runner.run(...)`(`BuilderCli.java:226`)에 `allConjunctions`를 마지막
   인자로 추가.

`enumConstants`는 run() 인자가 아님(이미 생성자로 주입된 runner 필드) — EndpointTarget 생성 시 필드를 그대로 전달.

### reachability (중요 — 한계 명시)

joint/enum 변이는 자신의 원자 필드만 세팅하므로, **선행 순차 가드**(L49–61)가 통과된 body 위에
적용돼야 L64에 도달한다. 두 경로로 보장:
1. **seed 누적**: `CoverageGuidedFuzzer`는 모든 변이를 **seed들**에 적용. `bound-roomNumber-{100,499}`이
   만든 "L49–61 통과" seed(= happy + roomNumber 유효) 위에 `enum-priceTier-VIP`/`joint-...`가 얹혀
   L64(앞선 가드 모두 통과 + VIP&&loyalty<500) true 도달.
2. Stage 0 happy의 선행 가드 통과 상태(검토 정정 — opus C1): happy는 nights=1✓, **roomNumber=1✗**,
   animalCount=1✓, petName✓, priceTier=BASIC✓, email✓, date✓ → **L52(roomNumber)에서 막힘**.
   (참고: depositAmount=1.0도 L73에서 무효지만 L73은 L64보다 **뒤**라 L64 도달엔 무관.) 따라서 L64
   도달엔 roomNumber만 고치면 충분 → `bound-roomNumber` seed가 그 역할.

선행 가드가 여러 개 무효인 SUT는 fully-valid seed가 예산 내 안 생길 수 있음 → joint가 깊은 곳에 닿지
못할 수 있음(의도된 한계; valid-prefix 완전 합성은 향후).

## 측정 (Stage 0와 동일 기준)

- service 분기는 `ExplorationReport.coveredAppBranches`(whole-app)로만 보임(handler 리포트 아님).
- A/B: 동일 petclinic jar에 Stage1+2 전(=현 main)과 후, `coveredAppBranches` 비교 + `GRB_ORACLE`
  static/both 각각. L64 true-arm 도달 = `post-api-reservations` 응답에 `VIP requires at least 500
  loyalty points`(422) 등장으로 확인.

## 결정성

conjunction 정렬 고정, 원자 만족값 결정적, enum 선언 순서 고정. Random/시간 금지(docs/04).

## 테스트

- `ConstraintExtractorTest`(보강): conjunction 픽스처(`a == Enum.X && b < N`, 단일필드 `x<1||x>9`는
  제외 확인, 중첩 `&&` 평탄화) → atoms/kind/만족값 근거 검증.
- `InputMutatorTest`(보강): enum 필드 → 각 상수 변이; conjunction → 단일 joint mutation이 모든 원자
  필드를 만족값으로 동시 세팅; 부분 매칭(필드 일부 부재) → joint 미생성.
- 회귀: order-service e2e 22/22, 전 모듈 단위 GREEN.
- 성과(A/B): petclinic `coveredAppBranches` 전/후 + L64 도달 증거.

## 성공 기준

1. order-service 회귀 GREEN.
2. petclinic `coveredAppBranches`가 현 main 대비 **증가**, 특히 `ReservationService:64`
   (`priceTier==VIP && loyaltyPoints<500`) **true-arm 도달**(응답 메시지로 확인).
3. 신규 추출/변이 단위 테스트로 결정성 보장.
4. L73/L76/L84(비목표)는 미달성 허용.

## 위험과 완화

- **noClasspath enum 식별**(opus G1/sonnet MAJOR-3): `field == Type.CONST` 판별을 `CtFieldRead` +
  `getTarget() instanceof CtTypeAccess` + `getVariable().getSimpleName()`로 하되, **구현 첫 단계의
  단위 테스트로 실제 Spoon 출력을 선검증**하고 안 되면 판별식 조정.
- **double 비교 원자 비포착**(opus M2): `literalLong`이 Double/Float 배제 → conjunction 안의 double
  비교 leaf는 원자로 안 잡힘. 그 conjunction은 원자 일부만 남아 필터(서로 다른 2필드)에서 탈락하거나,
  남은 원자만으로 joint가 만들어져도 실제 가드는 안 열림(거짓 기대 방지를 위해 **부분 매칭 버림** 정책이
  완충). petclinic L64는 int/enum이라 무관. 일반 double-결합 가드는 비목표(L73).
- **전역 필드명 매칭 동명 오매칭**: 서로 다른 DTO의 동명 필드 가능 → 기존 conditionBounds도 동일 한계.
  joint는 "모든 원자 필드가 body에 존재"를 요구해 오탐 일부 억제.
- **enum simple-name 폴백 충돌**(opus G4): `enumValues`의 simple-name 폴백은 동명 enum 중 첫 매치를 집음
  (SampleInputSynthesizer와 동일). 서로 다른 패키지 동명 enum이면 오매칭 가능 — 드묾, 결정성은 유지.
- **seed 누적 의존**으로 깊은 conjunction 미도달 가능 → 한계 명시(성공 기준 4가 반영).
- **검토 반려**: `!=` 만족값 `L+1`의 정수 오버플로(sonnet MAJOR-5)는 기존 constraintDirected의
  `numArg±1`과 동일 수준으로 미처리(YAGNI). joint 변이 수 증가의 예산 상호작용(sonnet MINOR-2)은
  petclinic conjunction 소수라 기존 saturation 한계로 충분 → 별도 처리 안 함.

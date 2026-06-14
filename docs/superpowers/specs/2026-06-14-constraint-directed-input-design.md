# 제약 지향 입력 생성 (Constraint-Directed Input Generation) — 설계

작성일: 2026-06-14
대상: graph-rag-builder 탐색 엔진 (#2, "concolic" 자리의 실용적 대체)

## 목표

탐색기가 **구문상 유효하지만 특정 제약을 위반/경계에 닿는** 입력을 만들어, 현재
일반 boundary 변이가 놓치는 **검증·핸들러 분기**를 커버하도록 한다. 측정은 #4에서
추가한 app-aggregate(`coveredAppBranches/totalAppBranches`)로 평가한다.

배경(실측): `InputMutator`는 generic boundary 변이(remove/null/zero/negative/large/
empty/literal)만 한다. `ConstraintExtractor`가 뽑은 handler 조건식은 **리포트용
텍스트로만** 쓰이고 입력 생성에 환류되지 않는다. Bean Validation 제약(@NotBlank/
@Size/@Min/@Max…)은 전혀 활용되지 않는다. → 검증/핸들러 분기에 결정적으로 못 닿음.

## 비목표 (YAGNI)

- 심볼릭 실행/SMT(JDart/SPF) 없음.
- @Pattern 정규식 역산(만족/위반 문자열 자동 생성) 없음 — 인식만 하고 값 생성은 보류.
- 다중 제약 조합(2nd-order) 없음 — 1차 제약별 단일 변이.

> **2026-06-14 개정:** 조건식(비교식) 추출 범위를 **handler 메서드 → SUT 소스 전 계층
> (컨트롤러/서비스/공통/도메인 등 모든 클래스·메서드)** 으로 확장했다. thin-controller/
> fat-service 아키텍처에서 의미 있는 분기 조건은 서비스·공통 계층에 있으므로 handler
> 한정은 본질적 한계였다(실측: 8개 MSA 서비스 컨트롤러에 `field op literal` 0건).
> 필드 매칭은 simple-name 기준이며, 엔드포인트의 `mutableFields`에 없는 필드명은
> `constraintDirected`에서 자동 무시되므로 전역 추출이어도 안전하다(거짓 입력 없음).
> body DTO Bean Validation(소스 A)은 본래대로 요청 바디 타입 한정(조건식이 아니라
> 입력 타입의 선언적 제약이므로).

## 두 가지 신규 값 소스 (둘 다 기존 `InputMutator`로 환류)

### 소스 A: Bean Validation 제약 (body DTO)

새 `ValidationConstraintExtractor`(Spoon). `@RequestBody` DTO 타입(record component
또는 field)의 `jakarta.validation.constraints.*` 애너테이션을 읽어
`Map<String fieldName, List<FieldConstraint>>` 반환. `BodyShapeExtractor`와 같은
모델 순회 패턴 재사용.

지원 애너테이션 → 위반/경계 값:

| 애너테이션 | 대상 타입 | 생성 변이 |
|---|---|---|
| `@NotNull` | any | null (이미 generic에 있음 → 중복 제거) |
| `@NotBlank`,`@NotEmpty` | String | "" (generic empty와 합쳐짐) |
| `@Size(min=m,max=M)` | String | `"x".repeat(m-1)` (m>0이면, too-short), `"x".repeat(M+1)` (M<무한이면 too-long) |
| `@Min(v)` | numeric | `v-1` (위반), `v` (경계 통과) |
| `@Max(v)` | numeric | `v+1` (위반), `v` (경계 통과) |
| `@Positive` | numeric | `0`, `-1` (generic과 합쳐짐) |
| `@PositiveOrZero` | numeric | `-1` |
| `@Negative`/`@NegativeOrZero` | numeric | `1`/`0` |
| `@Email` | String | `"not-an-email"` (위반) |
| `@Pattern(regexp=…)` | String | (인식만, 값 생성 보류) |

`FieldConstraint`은 builder 내부 record (shared-model 미변경):
`record FieldConstraint(String field, Kind kind, long numArg, String strArg)`,
`enum Kind { MIN, MAX, SIZE, POSITIVE, NEGATIVE, EMAIL, PATTERN, … }`.

### 소스 B: 비교식 경계 (전 계층 AST 추출 — 2026-06-14 개정)

`ConstraintExtractor.extractComparisons(srcDir)`가 **SUT 소스 모델 전체**의
`CtBinaryOperator`를 1회 순회(정규식 아님, AST 직접)하여 비교식
`<fieldRef> <op> <literal>` / `<literal> <op> <fieldRef>`를 수집한다.
(op ∈ `> >= < <= == !=`, 정수 리터럴만 1차.) 각 비교는 발생한
`(classFqn, method, line)`로 태깅된다. fieldRef는 `req.amount()`/
`request.getAmount()`/`amount`(접근자→프로퍼티명, 또는 변수·필드명)에서 추출.

`ConditionBoundarySolver.solve(List<Comparison>)` → `Map<String fieldName,
Set<Long> boundaryValues>`: 각 리터럴 L → {L-1, L, L+1} (TreeMap/TreeSet, 결정적).

- **전역 1회 추출**: BuilderCli가 엔드포인트 루프 전 `extractComparisons(srcDir)`를
  1회 호출, 모든 엔드포인트가 공유. (per-endpoint 재빌드 제거 → 성능 개선)
- **필드 투영**: `constraintDirected`가 엔드포인트의 `mutableFields` ∩ 숫자 필드에만
  bound를 적용 → 전역 비교식이라도 해당 엔드포인트와 무관한 필드명은 자동 무시.
- **rec-1(solverRelevantMissed)**: handler-method 미커버 분기와 겹치는 비교식만
  세야 의미가 있으므로, 리포트 단계에서 `(classFqn==handlerClass &&
  method==handlerMethod)`로 필터 후 라인 매칭한다(태깅된 메타 활용).

파싱 실패/메서드 밖(필드 초기화자 등) 비교식은 무시(best-effort). 결정적.

#### 소스 B-문자열: 문자열 동치 (2026-06-14 추가)

숫자 비교식과 대칭으로, `ConstraintExtractor.extractStringEqualities(srcDir)`가 전 계층에서
`field.equals("LIT")` / `"LIT".equals(field)`(CtInvocation `equals`, 인자 1개)를 추출해
`StringEquality(classFqn, method, fieldRef, value, line)`로 태깅. 필드별 문자열 후보값으로
모아 `constraintDirected`가 `streq-<field>-<value>` 변이를 생성(해당 String mutableField 한정).
자바 문자열 비교는 `.equals` 메서드콜이라 `CtBinaryOperator`(숫자 경로)에 안 잡히므로 별도 추출.
enum-style 대문자 리터럴은 기존 `LiteralCandidateExtractor`도 후보로 제공하지만, 그쪽은 handler
클래스 한정·필드 무관이고, 이 경로는 전 계층·필드 특정이며 소문자/혼합 리터럴도 잡는다.

## `InputMutator` 확장

새 정적 메서드:
```java
static List<Mutation> constraintDirected(
        List<BodyShape.BodyField> fields,
        Map<String, List<FieldConstraint>> fieldConstraints,
        Map<String, Set<Long>> conditionBounds)
```
- 필드 선언 순서 → 제약 종류 고정 순서로 Mutation 생성 (결정적, Random 금지).
- 위 표의 위반/경계 값을 `body.put(field, value)` 변이로.
- conditionBounds의 각 (field, value)마다 `body.put(field, value)` 변이 (숫자 필드만).
- generic `firstOrder`와 **중복되는 변이는 이름 기반으로 dedupe**(이미 explorer가
  `markTried(body)`로 동일 body 재시도를 막으므로 예산 낭비는 자연 차단되나, 변이
  목록 차원에서도 같은 (field,value) 중복은 제거).

`firstOrder`는 그대로 두고, explorer가 두 목록을 **이어붙여** 사용
(`firstOrder(...) ++ constraintDirected(...)`).

## 배선

- `BodyShape.BodyField`는 변경하지 않는다(WS 경로 영향 회피). 제약은 별도 맵으로 전달.
- `BuilderCli.build()`: 엔드포인트별로
  - `Map<String,List<FieldConstraint>> fieldConstraints =
     new ValidationConstraintExtractor().extract(model, bodyDtoQualifiedName)` —
    단, Spoon 모델 재빌드 비용을 줄이려면 `LiteralCandidateExtractor`/`ConstraintExtractor`와
    **모델 1회 빌드 공유**가 바람직. 1차 구현은 각자 빌드(기존 패턴 유지), 성능 이슈 시 공유로 리팩터.
  - `Map<String,Set<Long>> conditionBounds = new ConditionBoundarySolver().solve(conditions)`.
  - 두 맵을 `EndpointExplorationRunner.run(...)` → `EndpointTarget`에 신규 필드로 전달.
- `EndpointTarget`에 `fieldConstraints`, `conditionBounds` 추가. `HeuristicExplorer`/
  `CoverageGuidedFuzzer`가 mutation 목록 생성 시 `constraintDirected`를 합류.
  - 현재 `InputMutator.firstOrder(target.mutableFields(), target.literalCandidates())`
    호출부 2곳을 `firstOrder(...) + constraintDirected(...)`로 교체.
- read-path(GET)는 body 제약 없음 → fieldConstraints 비어 있음(무영향). conditionBounds는
  PATH/QUERY 숫자 파라미터에도 적용 가능(보너스, mutableFields에 해당 필드가 있으면 자동).

## 결정성

모든 추출/생성은 선언/리터럴 순서 고정, 정렬된 컬렉션 사용. 시간/Random 금지(docs/04).

## 테스트

- `ValidationConstraintExtractorTest`: 각 애너테이션이 달린 record/class 픽스처 →
  기대 `FieldConstraint` 목록.
- `ConditionBoundarySolverTest`: `"req.amount() > 100"`, `"q <= 0"`, `"x == 5"`,
  접근자/필드 형태, 파싱 실패 케이스 → 기대 boundary 맵.
- `InputMutatorTest`(기존 보강): `constraintDirected`가 @Size/@Min/@Max/@Email에 대해
  올바른 위반·경계 변이를 결정적으로 생성하고 generic과 중복 제거.
- 회귀(e2e + 격리 하네스): order-service/petclinic/auth-user/diary 49/49 유지 +
  app-aggregate 커버리지 **증가**(특히 petclinic 검증 분기). `.work/run-suites.sh` 재사용.

## 성공 기준

1. 4개 SUT 회귀 GREEN 유지(49/49).
2. 최소 한 SUT에서 app-aggregate `coveredAppBranches` 증가(생성 입력이 새 검증/핸들러
   분기를 열었음을 수치로 입증).
3. 새 추출기/솔버/변이는 단위 테스트로 결정성 보장.

## 위험과 완화

- Spoon 모델 다중 빌드 비용 → 1차는 기존 패턴 유지(엔드포인트당 빌드). 느리면 모델 공유.
- `@Min/@Max`의 value가 long 범위 밖/소수 → long 파싱 실패 시 해당 제약 skip.
- 조건식 파싱 오탐 → best-effort, 실패는 무시(생성 누락일 뿐 오류 아님).
- 위반 입력이 4xx만 늘리고 분기는 그대로일 수 있음(diary처럼 핸들러가 우회되는 SUT)
  → 성공 기준 2를 "최소 한 SUT"로 두어 SUT 의존성 흡수.

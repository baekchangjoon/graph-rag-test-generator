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
- 서비스 계층(컨트롤러 외) 조건식 추출 없음 — 이번 범위는 handler 메서드 조건 + body DTO 제약.
- 다중 제약 조합(2nd-order) 없음 — 1차 제약별 단일 변이.

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

### 소스 B: handler 조건식 경계 (이미 추출된 텍스트 파싱)

새 `ConditionBoundarySolver`. `ConstraintExtractor`의 `ConditionSpan.text()`를 받아
단순 비교식 `<fieldRef> <op> <literal>` 또는 `<literal> <op> <fieldRef>` 을 정규식으로
파싱. (op ∈ `> >= < <= == !=`). fieldRef는 `req.amount()`/`request.getAmount()`/
`amount` 형태에서 필드명 추출(접근자 → 프로퍼티명). 숫자 리터럴만 1차 지원.

→ `Map<String fieldName, Set<Long> boundaryValues>`: 각 리터럴 L에 대해 {L-1, L, L+1}.

파싱 실패한 조건은 무시(best-effort). 결정적.

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

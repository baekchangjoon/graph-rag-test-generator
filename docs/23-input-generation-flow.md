# 입력 조합 생성 흐름 — "의미있는 결과" 판정과 환류

작성일: 2026-06-14
대상: graph-rag-builder 탐색 엔진 (`io.graphrag.builder.explore`, `...run`, `...index`)

이 문서는 graph-rag-builder가 SUT를 실행하며 **입력 조합을 생성하는 과정**과, 매 입력의
실행 결과가 **"의미있다(novel)"고 판정되는 순간** 무엇이 환류되어 다음 조합을 만드는지를
기술한다. (배경 결정: `docs/decisions/explorer-engines.md`, 백엔드 전략: `docs/24-exploration-backends-and-input-oracle.md`)

## 한눈에 보기

```
[정적 분석: SUT 소스 1회 빌드]            [SUT 프로세스: 외부 HTTP + JaCoCo agent]
 ├ EndpointIndexer (엔드포인트/바디shape)        ▲ 요청           │ 요청단위 분기 dump
 ├ LiteralCandidateExtractor (enum 리터럴)       │                ▼
 ├ ValidationConstraintExtractor (@Min/@Size…)   │      InvocationOutcome(status, coveredBranches)
 └ ConstraintExtractor.extractComparisons        │                │
       (전 계층 field op literal)                 │                │
                  │                               │                ▼
                  ▼                               │      ┌─────────────────────────┐
        EndpointTarget                            │      │ "의미있는가?" 판정       │
   (baseInput, mutableFields,                     │      │ KnownCoverage.isNovel()  │
    literalCandidates,                            │      │  = 새 분기를 열었는가     │
    fieldConstraints, conditionBounds)            │      └───────────┬─────────────┘
                  │                               │         novel일 때만 │
                  ▼                               │                     ▼
        InputMutator.forTarget(target)            │      merge(분기 누적) + addSeed(이 입력)
   = constraintDirected ++ enumValues ++ joint    │                     │
     ++ firstOrder(generic)                        │
                  │ 변이 목록                      │     CoverageGuidedFuzzer가 시드에
                  ▼                               │     다시 변이 적용 → 다음 조합
        엔진이 입력 생성 ──────────────────────────┘     (2nd-order 이상)
```

핵심: **입력의 실행 결과가 "새 분기를 열었다(novel)"고 판정되는 순간이 곧 다음 입력 조합을
만드는 트리거**다. novel하지 않은 입력은 버려지고(시드가 되지 않음), novel한 입력만 시드 큐에
환류되어 그 위에 다시 변이가 쌓인다.

## 1. 입력의 출발점 — happy 합성 + 시드

엔드포인트마다 `EndpointExplorationRunner.run(...)` → `happyInput(...)`이:
- GET 또는 **비-GET by-id**(PATH 파라미터 보유): `ReadInputSynthesizer`로 path/query + 리소스 시드
  (유효 PK) 합성. 비-GET by-id면 body(`SampleInputSynthesizer`)와 병합 → PUT/DELETE `{id}`가
  유효 id로 service에 진입(Stage 3).
- 그 외(POST 등): `SampleInputSynthesizer`로 body만.
- **합성 유효값(Stage 0)**: enum 필드 → enum 첫 상수(`EnumConstantExtractor` 유래), `LocalDate` → ISO,
  `*email` → 유효 이메일, boolean 파라미터 → `"true"`. 시드 행의 enum 컬럼은 가드 유래 유효 상수
  (`extractEnumColumns`)로 채워 읽기 500을 막음(Stage 3).
- 시드 행을 DB에 INSERT한 뒤 `coverage.dump(true)`로 부팅·시드 구간 분기를 잘라내고 baseline 확보.
- 변이 대상 필드 `mutableFields`: 바디 필드(POST/PUT) 또는 PATH/QUERY 파라미터(GET).
- **mutating by-id(PUT/DELETE /{id})**: 탐색이 공유 시드 행을 변이·누적하지 않도록, 래핑된 invoker가
  **각 요청 전에 리소스를 fresh 시드로 리셋**(`resetSeeds` = reverse-DELETE 후 재-INSERT). 각 path 응답이
  (fresh 시드, 그 요청)의 순수 함수가 되어 생성 테스트가 빈 DB에서 재현된다(Stage 3b).

## 2. 정적 분석 결과를 입력 생성에 환류 (BuilderCli, 1회 빌드)

`BuilderCli.build()`가 엔드포인트 루프 전/중에 Spoon으로 다음을 추출해 `EndpointTarget`에 싣는다:

| 추출기 | 산출 | 범위 |
|---|---|---|
| `LiteralCandidateExtractor` | enum-스타일 문자열 리터럴(`"EXPRESS"`) | handler 클래스 |
| `ValidationConstraintExtractor` | `@Min/@Max/@Size/@Email/@Positive…` → `FieldConstraint` | `@RequestBody` DTO 타입 |
| `ConstraintExtractor.extractComparisons(srcDir)` | `field op literal` 비교식 → `Comparison(classFqn,method,fieldRef,op,literal,line)` | **SUT 소스 전 계층(컨트롤러/서비스/공통/도메인) 1회 빌드** |
| `ConstraintExtractor.extractConjunctions(srcDir)` | 메서드 내 `&&` 다필드 가드 → `Conjunction(atoms)` (원자: NUMERIC/ENUM_EQ/STRING_EQ, 서로 다른 2필드+) | **전 계층 1회** (joint 변이용, Stage 1/2) |
| `ConstraintExtractor.extractEnumColumns(srcDir)` | `accessor()==Type.CONST` 가드 → 컬럼(snake)→유효 enum 상수 | **전 계층 1회** (enum 컬럼 시드, Stage 3) |
| `EnumConstantExtractor.extract(srcDir)` | enum FQN → 선언 순서 상수 | SUT 소스 1회 (enum 값 합성/변이) |
| `ConstraintExtractor.extract(class,method)` | 분기 조건 텍스트 `ConditionSpan` | handler 메서드 (리포트용 `ExploredPath.constraints`, 입력 생성 아님) |

비교식은 **전역 1회 추출** 후 모든 엔드포인트가 공유한다. `ConditionBoundarySolver.solve(comparisons)`가
각 리터럴 L을 `{L-1, L, L+1}`로 펼쳐 `Map<field, Set<Long>>`(conditionBounds)를 만든다.
전역이지만 안전한 이유: `constraintDirected`가 **엔드포인트의 `mutableFields` ∩ 숫자 필드**에만
bound를 적용하므로, 무관한 필드명의 전역 비교식은 자동 무시된다(거짓 입력 없음).

## 3. 변이 카탈로그 — `InputMutator.forTarget(target)`

두 탐색 엔진은 동일하게 `forTarget`를 쓴다. 여러 목록을 이어붙여 이름 기준 dedupe한 것:

- **`constraintDirected` (제약 지향)** —
  - Bean Validation: `@Min(v)`→`v-1`/`v`, `@Max(v)`→`v+1`/`v`, `@Size`→too-short/too-long+경계,
    `@Email`→`"not-an-email"`, `@Positive/@Negative…`→위반값. (`@NotNull/@NotBlank`는 generic이
    덮으므로 no-op, `@Pattern`은 인식만)
  - 비교식 경계: conditionBounds의 각 `(field, v)`마다 `bound-<field>-<v>` (숫자 필드).
- **`enumValues` (Stage 1/2)** — enum 필드별로 선언된 각 상수 세팅: `enum-<field>-<상수>`. enum 값에
  갈리는 분기(예 `tier==VIP`)를 연다.
- **`joint` (Stage 1/2)** — `extractConjunctions`의 각 conjunction을, 원자들을 **동시에** 만족값으로
  세팅하는 단일 변이: `joint-<class>-<line>-<fields>`. NUMERIC은 op별 만족값(`<`→L-1 등), ENUM_EQ/
  STRING_EQ는 상수. 다필드 동시 가드(예 `tier==VIP && loyalty<500`)를 연다(seed 누적 위에서 도달).
- **`firstOrder` (generic boundary)** — 필드별: `remove`/`null`(전 타입), `zero`/`negative`/
  `large(1,000,000)`(숫자), `empty`/`missing-ref`/`literal-<후보>`(문자열).

**우선순위**: 예산이 적을 때 generic firstOrder가 고신호 변이를 굶지 않도록, constraint-directed/enum/joint를
firstOrder **앞**에 둔다. 모든 순서는 필드 선언/리터럴 정렬로 고정 — **Random/시간 금지(결정성, docs/04)**.

## 4. "의미있는 결과" 판정과 환류 — 엔진별

### 엔진 1: `HeuristicExplorer` (1차)
happy 입력 + 각 변이를 baseInput에 1회씩 적용. 입력마다 `tryInput`:
1. `KnownCoverage.markTried(body)` — 이미 시도한 body면 skip(중복·예산 절약·결정성).
2. `ExplorationBudget.tryConsume()` — 예산 소진 시 종료.
3. `target.invoker().invoke(body)` — SUT에 HTTP 호출, 요청단위 JaCoCo dump →
   `InvocationOutcome(status, coveredBranches)`.
4. **의미있음 판정**: `KnownCoverage.isNovel(coveredBranches)` = 누적 `covered`에 없는 분기를
   하나라도 열었는가. **novel이면** `merge`(분기 누적) + `addSeed(body, status)`.

### 엔진 2: `CoverageGuidedFuzzer` (2차+)
엔진 1이 남긴 **시드 큐**(= novel 입력들)를 2xx 우선 정렬 후, 각 시드에 **같은 변이 카탈로그를
다시 적용** → 조합이 누적된다(예: "필드 A를 경계값으로 만든 novel 입력" 위에 "필드 B 변이"). 동일
루프(markTried→budget→invoke→isNovel→merge+addSeed). 한 시드 패스가 연속 `saturationLimit`
(코드 상수 `FUZZER_SATURATION = 2`)회 novelty 없으면 **포화 종료**.

`ExplorationOrchestrator`가 두 엔진을 순차 실행하며 예산을 분할(첫 엔진 cap=총예산 절반, 미사용분
다음 엔진 양도)하고 `KnownCoverage`를 공유, 분기 집합 기준으로 path를 dedupe한다.

## 5. 산출물 (그래프 + 리포트)

- 발견된 distinct path → `ExploredPath`(body, status, response, 캡처 SQL/HTTP id, `branchesTaken`,
  `discoveredBy`, `constraints`, `validationWarnings`, `seedIds`).
- `ExplorationReport.EndpointExploration`: handler-method `covered/total/missedBranches`,
  `pathsByEngine`, **`solverRelevantMissed`**(미커버 분기 중 같은 handler의 `field op literal`
  비교식 라인과 겹치는 수 — 콘콜릭 복귀 트리거 지표). 앱 전체는 `coveredAppBranches/totalAppBranches`.

## 부록: 현재 코퍼스에서의 관찰 (2026-06-14)

전 계층 비교식 추출을 적용했음에도, order-service(샘플) + petclinic + tainted-spring 8개 MSA
서비스 전체에서 **HTTP 요청 필드에 대한 `field op literal` 분기는 0건**이다(존재하는 비교식은
`totalElements`/`idx`/`count`/`added` 등 내부·파생 변수). 또한 Bean Validation 숫자 제약은
diary의 `@Min(1) @Max(10) energyScore` 1건뿐이며 diary는 Java23 커버리지 측정이 별도로 깨져 있다.

따라서 이 코퍼스에서 `constraintDirected`의 고유 기여는 실측상 0이다 — generic 변이
(`0`/`-1`/`large`/`empty`/`null`) + 리터럴 후보가 이 앱들의 분기를 이미 덮기 때문이다.
이 기능의 고유 가치(등치 `== literal`, 비-0 임계값, 숫자 `@Min/@Max/@Size` 경계)를 실증하려면
해당 구조를 가진 SUT(또는 통제된 엔드포인트)가 필요하다. 메커니즘 자체는 단위 테스트로 입증됨.

**갱신(2026-06-15)**: 위 공백을 메우려 order-service에 **Booking 리소스**(통제된 엔드포인트)를 추가했다 —
enum 컬럼(tier/status), `LocalDate`, 이메일, 다필드 가드(`tier==VIP && loyaltyPoints<500`),
by-id PUT/DELETE(boolean param 포함). 이로써 Stage 0/1/2/3/3b가 **CI(order-service e2e)에서 라이브로
실증·회귀 보호**된다(e2e 22→45 tests). petclinic boarding에서도 실측: enum/joint 변이로
`tier==VIP` arm 도달, by-id 생성 테스트가 fresh DB에서 통과. 비-Booking 기존 앱들에선 위 한계 그대로.

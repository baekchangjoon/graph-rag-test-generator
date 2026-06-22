# 배열/중첩 바디 변이 + 다중 필드 조인 가드 — 설계

- 일자: 2026-06-22
- 브랜치: feat-array-nested-mutation-joinguard
- 선행: [`2026-06-18-list-dto-body-shape-design.md`](2026-06-18-list-dto-body-shape-design.md)
  (컬렉션 body shape happy-only). 본 작업은 그 spec이 **비목표로 명시한** 항목 — 컬렉션
  원소별 변이, 빈 배열 arm, 중첩 바디 — 를 이어받는다.
- 조인 가드 의미: secretary inbox 체인(300s 타임아웃 → consult-secretary twin-brain CLI 오류)
  소진으로 **safe_default(A: field-to-field 관계 가드)** 적용.

## 문제

`InputMutator`의 변이가 **최상위 평면 객체의 `body.put(name, value)`** 만 다룬다. 세 갭:

1. **최상위 `List<DTO>` 배열 바디 — 원소 필드 변이 0건.**
   `HeuristicExplorer.java:24`(`if (base instanceof ObjectNode objBase)`)와
   `CoverageGuidedFuzzer.java:33`(`if (!(target.baseInput() instanceof ObjectNode)) return 빈 결과`)가
   배열 바디면 변이 루프를 통째로 건너뛴다. `SampleInputSynthesizer.java:51-59`가 1-element
   ArrayNode `[{…el}]`를 seed로 만들지만 happy 1회만 호출되고 원소 필드는 변이되지 않는다.
   `mutableFields`는 이미 **원소 DTO 필드**를 담고 있어(`shape.fields()`) 변이 후보는 생성되나
   적용 지점이 없다.

2. **중첩 객체 필드 도달 불가.**
   모든 변이 빌더가 `body.put(name, …)` / `body.putNull(name)` / `body.remove(name)`로 **최상위
   키**만 쓴다. `address.city` 같은 중첩 객체 필드를 가진 가드 분기를 열 수 없다. 현재
   `BodyShapeExtractor.extract`는 nested DTO 컴포넌트를 **그 타입명 그대로** 평면 BodyField로
   담아(재귀 없음) 리프 스칼라까지 펼치지 않는다.

3. **field-to-field 조인 가드 미추출.**
   `ConstraintExtractor.toAtom:285-289`는 `field OP literal`(NUMERIC), `field == Enum.CONST`(ENUM_EQ),
   `field.equals("LIT")`(STRING_EQ)만 atom으로 만든다. 두 입력 필드를 **서로** 비교하는 가드(예:
   `if (req.getMin() > req.getMax())`, `if (a.getStart().equals(a.getEnd()))`)는 양변이 모두 field-ref라
   리터럴 분기에 안 걸려 `null`을 반환 → 해당 분기 arm을 여는 입력이 합성되지 않는다.

## 범위

- **포함**:
  - (#1) 최상위 컬렉션 바디(`List/Set/Collection/Iterable<DTO>`, `DTO[]`)의 **원소 필드 변이** +
    빈 배열 구조 변이 1종.
  - (#2) 중첩 객체 필드의 **dot-path 변이**(깊이 상한·cycle guard).
  - (#3) field-to-field 관계 가드(숫자 `<,<=,>,>=,==,!=`, `equals` 문자열) 추출 + 양 arm 동시세팅 변이.
- **제외(비목표 — YAGNI)**:
  - 다중 원소 배열, 원소별 **서로 다른** 변이(원소 0만 대표 변이), 중첩 컬렉션/맵 바디.
  - 3개 이상 필드를 잇는 조인 가드(2-필드만), 조인 가드의 Z3 비선형 해 탐색(상수 후보 세팅만).
  - field-to-field 가드의 배열 **원소 간**(element[i] vs element[j]) 비교.

## 설계

네 개의 외과적 변경. 변이 시그니처(`UnaryOperator<ObjectNode>`)는 **불변** — 기존 변이 빌더의
시그니처/이름/순서를 보존해 dedupe·markTried·예산 회계가 그대로 유지된다.

### 변경 1 — `InputMutator` path-aware put (중첩 #2 해결)

`putStr`/`putLong` 및 `firstOrder`/`constraintDirected`/`joint`/`enumValues`/`interField`/
`interFieldReal`/`realBounds`의 람다에서 쓰는 `body.put/remove/putNull(name)`을 **dot-path 인지
헬퍼**로 교체한다. 새 private 헬퍼:

```java
// "a.b.c" → root.with("a").with("b").put("c", value). 점이 없으면 root.put(name, value)와 동일.
static void putPath(ObjectNode root, String path, <primitive> value);   // long/int/double/String 오버로드
static void putNullPath(ObjectNode root, String path);
static void removePath(ObjectNode root, String path);   // 부모까지 하강 후 리프 remove
```

- 중간 노드가 없거나 ObjectNode가 아니면 **새 ObjectNode로 materialize**(가드가 그 객체의 필드를
  읽으려면 객체가 존재해야 함). remove는 부모가 없으면 no-op.
- `name`에 점이 없으면 정확히 기존 동작. ⇒ 평면 객체 회귀 0.

### 변경 2 — `BodyShapeExtractor` 중첩 dot-path 평탄화 (중첩 필드 공급)

`extract`(및 그 재귀)가 DTO/record 컴포넌트를 펼칠 때, 컴포넌트 타입이 **scalar/enum/collection이
아니면**(= 모델 내 해석 가능한 DTO) 재귀로 `parent.child` **스칼라 리프**를 전개한다.

- **scalar 판정**: 기존 `SCALAR_TYPES` 재사용. enum·collection·미해결(shadow/외부)·**깊이 상한 도달**은
  리프로 종료. 깊이 상한 `MAX_NESTING_DEPTH = 3`(루트=0).
- **cycle guard**: 방문 타입 FQN `Set`을 재귀 경로에 전달, 재방문 타입은 그 경로에서 스칼라 폴백 리프로
  종료(무한 재귀 차단). `cyclicNested_…` 테스트로 고정.
- **빈/미해결 nested**: 펼칠 리프가 없으면 부모 경로명을 스칼라 리프로 폴백(엔드포인트 skip 방지).
- 산출 `BodyField.name` = dot-path(`shipTo.zipCode`), `javaType` = 리프 스칼라 FQN.
  ⇒ `InputMutator.forTarget`이 **자동으로** 중첩 리프에 대한 변이를 생성(변경 1의 path-put이 적용).

플랫 BodyField만 쓰는 호출부(`SampleInputSynthesizer` happy 합성 등)는 dot-path를 만나도 **happy 값
합성에는 그대로** 동작해야 한다 — 합성 경로의 회귀를 E2E로 확인(아래 수용 기준 REQ).

### 변경 3 — 배열 원소 변이 어댑터 (최상위 컬렉션 #1 해결)

`InputMutator`에 컨테이너-무관 적용 헬퍼 추가:

```java
// ObjectNode → m 직접 적용. ArrayNode → 깊은 복사 후 각 원소 ObjectNode에 m 적용. 그 외 → 원본 반환.
public static JsonNode applyToBody(JsonNode body, Mutation m);
```

- 두 explorer의 `instanceof ObjectNode` 가드를 제거하고 **배열·객체 모두** 변이 루프를 돌도록 변경,
  변이 적용을 `m.apply().apply(copy)` 대신 `InputMutator.applyToBody(copy, m)`로 통일.
- `CoverageGuidedFuzzer`의 시드 큐도 배열 시드를 허용(현재 early-return 제거). `KnownCoverage.markTried`는
  JsonNode 동치 기반이라 배열도 그대로 동작.
- **배열 구조 변이 1종**: `empty-array`(빈 `[]`) — 컬렉션 가드(`if(list.isEmpty())` 등) arm 도달.
  `HeuristicExplorer`에서 배열 바디일 때만 추가(원소 0개 대표).
- 원소 0개만 대표 변이(다중 원소 비목표). 결정성·예산 유지.

### 변경 4 — 조인 가드 추출 + `InputMutator.joinGuards`

**추출** — `ConstraintExtractor`:
- `toAtom`에서 양변이 모두 field-ref(리터럴/enum 아님)이고 op가 관계 연산자면 새 레코드를 만들 수
  있도록, **독립 추출기** `extractJoinGuards(srcDir)` 추가(conjunction 내부가 아니라 비교식 전반):

```java
public record JoinGuard(String classFqn, String method, int line,
                        String leftRef, String op, String rightRef, JoinKind kind) {}
public enum JoinKind { NUMERIC, STRING }   // STRING = a.equals(b)
```

- NUMERIC: `lhsFieldRef OP rhsFieldRef`(양변 getter/accessor field-ref). STRING: `a.equals(b)`(양변
  field-ref, 리터럴 아님). 정렬·dedupe는 기존 Comparison 패턴 따름.

**변이** — `InputMutator.joinGuards(fields, guards)`:
- 두 필드가 **모두** `mutableFields`에 있을 때만(부분이면 skip — joint/interField와 동일 정책).
- NUMERIC 가드당 3개: `a==b`(둘 다 0), `a<b`(a=0,b=1), `a>b`(a=1,b=0). 동시세팅 atomic(한 변이가
  두 필드 모두 put). 이름에 `(left,op,right,arm)` 포함 → dedupe 충돌 방지.
- STRING 가드당 2개: `a==b`("x","x"), `a≠b`("x","y").
- path-aware put(변경 1)·배열 어댑터(변경 3)가 자동 적용 → 중첩/배열 원소의 조인 가드도 커버.
- `forTarget`에 `all.addAll(joinGuards(...))`를 joint 다음(고신호 그룹)에 추가. `EndpointTarget`에
  `List<JoinGuard> joinGuards` 필드 추가(기존 편의 생성자는 `List.of()` 디폴트).

### 데이터 흐름

```
SUT 소스 ──ConstraintExtractor.extractJoinGuards──▶ List<JoinGuard> ─┐
SUT 타입 ──BodyShapeExtractor(중첩 평탄화)──▶ BodyShape(dot-path fields)─┤
                                                                      ├▶ EndpointTarget
                                                                      │      │
                                  InputMutator.forTarget(target) ◀────┘      │
                                  (joinGuards + 기존 변이, dot-path name)      │
                                         │                                    │
   explorer: applyToBody(copy(seed), m) ─┴─ ObjectNode/ArrayNode 자동 분기 ◀──┘
                                         └─ path-put이 중첩 리프 도달
```

### 격리·테스트성
- `putPath`/`applyToBody`/`extractJoinGuards`/`joinGuards`는 순수 함수(시간·랜덤 없음) → 결정적 단위
  테스트로 독립 검증.
- 변이 빌더 시그니처 불변 → 기존 `InputMutatorTest` 회귀 그대로 유효.

## E2E/수용 기준 (요구사항명세에서 REQ-ID로 확정)

최고 가능 수준 = **out-of-process 빌드→생성된 테스트 GREEN** E2E(기존 e2e 하니스). 대상 SUT는
in-repo 샘플(컬렉션 바디·중첩 DTO·조인 가드를 가진 핸들러)을 기준으로 한다. 없으면 최소 핸들러를
in-repo 샘플 SUT에 추가(외부 관측 행위 검증을 위한 fixture).

- **AC-1(배열)**: 최상위 `List<DTO>` POST에서 원소 필드 변이가 ≥1개 신규 분기/4xx를 커버(happy 외).
- **AC-2(중첩)**: 중첩 객체 필드(`a.b`) 변이가 SUT의 중첩 필드 가드 분기에 도달.
- **AC-3(조인)**: field-to-field 숫자 가드(`min > max`)의 양 arm을, 문자열 `equals` 조인 가드의
  같음/다름 arm을 각각 커버.
- **AC-4(회귀)**: 기존 평면 객체 바디 탐색 결과·커버리지 불변(전 모듈 회귀 GREEN).

## 리스크
- **느린 빌드/예산**: 배열·중첩으로 변이 수 증가. → element[0]·깊이3·empty-array 1종으로 상한. 예산
  회계(markTried/tryConsume) 불변이라 폭주 시 자연 종료.
- **중첩 평탄화 호환**: dot-path BodyField를 happy 합성 경로가 깨지 않는지 AC-4로 가드.
- **feat-tia-index/feat-success-oracle 워크트리와의 향후 머지 충돌**: 현재 어느 쪽도 중첩 평탄화·조인
  가드 미보유(확인 완료) → 본 작업이 최초. 머지 시점 reconcile는 별도 관심사.

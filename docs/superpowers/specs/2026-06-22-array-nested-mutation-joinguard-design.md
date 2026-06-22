# 배열/중첩 바디 변이 + 다중 필드 조인 가드 — 설계

- 일자: 2026-06-22
- 브랜치: feat-array-nested-mutation-joinguard
- 선행: [`2026-06-18-list-dto-body-shape-design.md`](2026-06-18-list-dto-body-shape-design.md)
  (컬렉션 body shape happy-only). 본 작업은 그 spec이 **비목표로 명시한** 항목 — 컬렉션
  원소별 변이, 빈 배열 arm, 중첩 바디 — 를 이어받는다.
- 조인 가드 의미: secretary inbox 체인(300s 타임아웃 → consult-secretary twin-brain CLI 오류)
  소진으로 **safe_default(A: field-to-field 관계 가드)** 적용.
- 리뷰: 3-벤더 design-doc 리뷰(Claude Sonnet×2 + Cursor) 전원 needs_revision → 본 v2가 반영본.
  (Gemini 슬롯 타임아웃 → Sonnet 폴백.)

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
   `ConstraintExtractor.toAtom`(현재 285–301)은 `field OP literal`(NUMERIC), `field == Enum.CONST`,
   `field.equals("LIT")`만 atom으로 만든다. 두 입력 필드를 **서로** 비교하는 가드는 양변이 모두
   field-ref라 리터럴·enum 분기에 안 걸려 `return null`(301행)로 끝난다. 실증: 단위 fixture
   `BoundsController.java`의 `if (req.amount() > req.score())`에 "리터럴 없음 → 무시" 주석이 달려
   있고, `ConstraintExtractorComparisonsTest`가 그 **무시 동작을 단언**한다.

## 범위

- **포함**:
  - (#1) 최상위 컬렉션 바디(`List/Set/Collection/Iterable<DTO>`, `DTO[]`)의 **원소 필드 변이** +
    빈 배열 구조 변이 1종.
  - (#2) 중첩 객체 필드의 **dot-path 변이**(깊이 상한·cycle guard).
  - (#3) **flat 필드 간** field-to-field 관계 가드(숫자 `<,<=,>,>=,==,!=`, `equals` 문자열) 추출 +
    양 arm 동시세팅 변이.
- **제외(비목표 — YAGNI)**:
  - 다중 원소 배열, 원소별 **서로 다른** 변이(원소[0]만 대표 변이), 중첩 컬렉션/맵 바디.
  - 3개 이상 필드를 잇는 조인 가드(2-필드만), 조인 가드의 Z3 비선형 해(상수 후보 세팅만).
  - **중첩 필드 간** 조인 가드(`a.x OP b.y`). 근거: `ConstraintExtractor.fieldRef()`는 단일
    accessor명만 반환(`getMin()`→`min`)하므로 dot-path와 매칭되지 않는다. 체인→dot-path 확장은
    후속. 본 작업의 AC-3 fixture(BoundsController)는 flat 필드만 쓴다.
  - field-to-field 가드의 배열 **원소 간**(element[i] vs element[j]) 비교.

## 설계

네 개의 외과적 변경. 변이 시그니처(`Mutation` = `UnaryOperator<ObjectNode>`)는 **불변** — 기존 변이
빌더의 시그니처/이름/순서를 보존해 dedupe·markTried·예산 회계가 그대로 유지된다.

### 변경 1 — `InputMutator` path-aware put (중첩 #2 해결)

`putStr`/`putLong` 및 `firstOrder`/`constraintDirected`/`joint`/`enumValues`/`interField`/
`interFieldReal`/`realBounds`의 람다가 쓰는 `body.put/remove/putNull(name)`을 **dot-path 인지
헬퍼**로 교체한다. 새 package-private 정적 헬퍼:

```java
static void putPath(ObjectNode root, String path, int value);
static void putPath(ObjectNode root, String path, long value);
static void putPath(ObjectNode root, String path, double value);
static void putPath(ObjectNode root, String path, String value);
static void putNullPath(ObjectNode root, String path);
static void removePath(ObjectNode root, String path);
```

**구현 — Jackson `.with(String)` 금지.** `ObjectNode.with(String)`은 해당 키가 이미 **non-Object
노드**(NullNode/스칼라/ArrayNode)면 `UnsupportedOperationException`을 던진다. 이 상태는 도달 가능하다
(예: `null-address` 변이가 `address`를 NullNode로 만든 뒤 `putPath(body,"address.city",…)`). 따라서
세그먼트마다 **명시적으로 하강**한다:

```
ObjectNode node = root;
for (각 중간 세그먼트 seg) {
    JsonNode child = node.get(seg);
    if (!(child instanceof ObjectNode)) {        // 없거나 non-Object → 새 ObjectNode로 교체
        ObjectNode created = node.objectNode();
        node.set(seg, created);
        child = created;
    }
    node = (ObjectNode) child;
}
node.put(leaf, value);   // 타입별 오버로드: int→IntNode, long→LongNode, double→DoubleNode, String→TextNode
```

- `name`에 점이 없으면 정확히 기존 동작(`root.put(name, value)`). ⇒ 평면 객체 회귀 0.
- `removePath`: 부모까지 위와 같이 하강 후 리프 `remove`. 부모 경로 중간이 없으면 no-op. **리프 제거로
  부모가 빈 `{}`가 되어도 부모는 남긴다**(가장 단순; 단위 테스트로 `{"a":{"b":1}}`→`removePath("a.b")`
  =`{"a":{}}`, `{"a":{"b":1,"c":2}}`→`{"a":{"c":2}}` 고정).
- `putNullPath`: 리프를 `putNull`. (부모 객체는 존재, 리프만 null — 최상위 `null-` 변이와 동형.)

### 변경 2 — `BodyShapeExtractor` 중첩 dot-path 평탄화 (중첩 필드 공급)

> **역전파 정정(v3, 구현 중 회귀 발견).** 최초 설계는 `extract()`를 전역 평탄화했으나, 이는
> `EndpointIndexer.classifyFormBindings`(필드 타입이 nested POJO여야 NESTED/REFERENCE 분류)와
> `FormBodySynthesizer`(dotted 이름→리터럴 form 키 위임)를 깨뜨린다(form-body 기능과 공유 컴포넌트
> 충돌). 따라서 평탄화를 **JSON `@RequestBody` 경로 전용으로 scope**한다:
> - `extract()`/`extractFromType()`는 **un-flattened 복원**(form 분류·합성 계약 보존).
> - 재귀 평탄화는 **별도 메서드** `extractFromTypeFlattened(model, type)`로 분리.
> - `EndpointIndexer`에서 `@RequestBody`(ParamKind.BODY)만 flattened 변형으로 bodyShape를 저장;
>   form 커맨드(ParamKind.FORM)는 un-flattened `extractFromType` 유지.
> - REQ-005 검증은 `extract()`가 아니라 `extractFromTypeFlattened`를 대상으로 한다.

`extractFromTypeFlattened`에 **재귀 평탄화**를 둔다. DTO/record 컴포넌트 타입이 scalar/enum/collection이
아니고 모델 내 해석 가능한 DTO면, 재귀로 `parent.child` **스칼라 리프**를 전개한다.

- **scalar 판정**: 기존 `SCALAR_TYPES` 재사용. enum·collection·미해결(shadow/외부)·**깊이 상한 도달**은
  리프로 종료.
- **깊이 상한** `MAX_NESTING_DEPTH = 2`(depth=0이 첫 컴포넌트 레벨 → dot-path 세그먼트 최대 3, 예
  `a.b.c`). 폭주 방지 임의 상한 —
  FormBodySynthesizer 등 타 평탄화와 독립(공유 유틸 아님; 본 작업 한정).
- **cycle guard**: 방문 타입 FQN `Set`을 **재귀 경로별(스택-로컬, 파라미터 전달)** 로 둔다. 전역 Set이
  아니다 — 전역이면 `record Order(Address billing, Address shipping)`의 같은 타입 형제 필드가 잘못
  잘린다. 재방문 타입은 그 경로에서 스칼라 폴백 리프로 종료.
- **빈/미해결 nested**: 펼칠 리프가 없으면 부모 경로명을 스칼라 리프로 폴백(엔드포인트 skip 방지).
- 산출 `BodyField.name` = dot-path(`address.city`), `javaType` = 리프 스칼라 FQN. ⇒ `forTarget`이
  자동으로 중첩 리프 변이를 생성(변경 1의 path-put 적용).
- 신규 단위 테스트(BodyShapeExtractor 전용): `nestedField_flattensToDotPath`,
  `nestedDepth_cappedAtMax`, `cyclicNested_perPathGuard`(`record Node(Node parent, String name)` →
  깊이 내 `parent.…name` 전개 + cycle 차단), `siblingSameType_bothExpanded`.

**happy 합성 호환 (역전파 v3).** `SampleInputSynthesizer`는 **리터럴 키 유지**한다(FormBodySynthesizer가
dotted 이름→리터럴 form 키로 위임하므로 중첩하면 form이 깨진다). FK 휴리스틱만 **점이 있는 이름에
미적용**(`a.b`는 FK 컬럼 아님 — carve-out 유지). JSON happy 바디의 중첩은 **runner에서 scope**:
`run()`이 non-form JSON object 바디(`!form && baseInput instanceof ObjectNode`)에 한해
`JsonPaths.nestDottedKeys(body)`(점 포함 최상위 키 → 중첩 객체로 이동, 순수 변환)를 적용한다. mutableFields는
JSON-flattened shape의 dot-path 필드이고, 변이는 `JsonPaths.putPath`로 이미 중첩 생성. AC-2가 nested happy
body `{"address":{"city":…}}`를 검증.

### 변경 3 — 배열 원소 변이 어댑터 (최상위 컬렉션 #1 해결)

`InputMutator`에 컨테이너-무관 적용 헬퍼 추가(반환 `JsonNode`):

```java
public static JsonNode applyToBody(JsonNode body, Mutation m) {
    if (body instanceof ObjectNode obj) return m.apply().apply(obj);
    if (body instanceof ArrayNode arr && !arr.isEmpty()
            && arr.get(0) instanceof ObjectNode) {        // element[0] 대표만 변이
        m.apply().apply((ObjectNode) arr.get(0));         // arr는 이미 깊은 복사본
    }
    return body;                                          // scalar 배열 등은 원본
}
```

- **element[0]만 변이**(다중 원소·원소별 변이 비목표). seed가 항상 1-element라 실무상 유일 원소.
  비-ObjectNode 원소(`List<String>`)는 가드로 건너뜀.
- `InputMutator.copy()` 반환형을 `ObjectNode` → **`JsonNode`** 로 변경(ArrayNode seed deepCopy가
  ObjectNode 캐스팅에서 CCE 나는 것을 방지).
- 두 explorer 갱신:
  - `HeuristicExplorer`: `instanceof ObjectNode` 가드 제거. 변이 루프를 객체·배열 공통으로 돌리고
    적용을 `InputMutator.applyToBody(InputMutator.copy(base), m)`로. **배열 바디일 때만** empty-array
    구조 변이 1종(`[]`) 추가 — 컬렉션 가드(`list.isEmpty()` 등) arm 도달.
  - `CoverageGuidedFuzzer`: line 33 early-return 제거. line 46-47의
    `mutation.apply().apply(copy(seed.body()))`를 `InputMutator.applyToBody(copy(seed.body()), mutation)`로.
    시드 큐는 ArrayNode seed 허용(`KnownCoverage.markTried`는 JsonNode 동치 기반 → 배열 동작).

### 변경 4 — 조인 가드 추출 + `InputMutator.joinGuards`

**추출 — `ConstraintExtractor` (추가 파싱 없이 기존 순회 재사용).**
`extractComparisons`가 이미 모든 `CtBinaryOperator`를 순회한다(line 141). 별도 Launcher 빌드를 6번째로
추가하지 않고, **같은 순회에서** 양변이 모두 field-ref(리터럴·enum 아님)이고 op가 관계 연산자면
`JoinGuard`도 수집한다. STRING은 `extractStringEqualities` 순회에서 `a.equals(b)`(target·arg 모두
field-ref, 어느 쪽도 `stringLiteral` 아님 — 리터럴이면 기존 STRING_EQ가 처리하므로 skip)일 때 수집.

```java
public record JoinGuard(String classFqn, String method, int line,
                        String leftRef, String op, String rightRef, JoinKind kind) {}
public enum JoinKind { NUMERIC, STRING }
```

`EndpointExplorationRunner`는 기존 `extractComparisons(...)` 호출 지점에서 join guard도 함께 받도록
한다(단일 모델 빌드 유지). 정렬·dedupe는 기존 Comparison 패턴.

**변이 — `InputMutator.joinGuards(fields, guards)`:**
- 두 필드(`leftRef`,`rightRef`)가 **모두** `mutableFields`(flat 이름)에 있을 때만(부분이면 skip —
  joint/interField 동일 정책). 매칭은 flat 이름 정확 일치(nested 조인은 범위 외).
- **NUMERIC 가드당 3개**: `a==b`(0,0), `a<b`(0,1), `a>b`(1,0). 동시세팅 atomic(한 변이가 두 필드 모두
  put). **정당성**: 세 관계 `{a<b, a==b, a>b}`는 수직선 순서를 분할하므로 `<,<=,>,>=,==,!=` **어떤
  연산자든** true·false 양 arm을 모두 덮는다(연산자별 분기 로직 불필요). 정수 0/1은 모든
  `NUMERIC_TYPES`(int/long/double/float/BigDecimal)에서 `0<1` 유효. **한계**(문서화): 필드에
  `@Positive` 등 단일필드 제약이 있으면 probe 0이 그 검증에서 먼저 걸러져 가드까지 못 갈 수 있음 —
  허용 한계.
- **STRING 가드당 2개**: `a==b`("x","x"), `a≠b`("x","y") — `equals`/부정 양 arm.
- 이름에 `(left,op,right,arm)` 포함 → dedupe 충돌 방지. path-put(변경 1)·배열 어댑터(변경 3) 자동 적용.
- `forTarget`에 `all.addAll(joinGuards(...))`를 joint 다음(고신호 그룹)에 추가. `EndpointTarget`에
  `List<JoinGuard> joinGuards` 필드 추가, **canonical 생성자와 기존 5-arg·6-arg 편의 생성자 모두**에
  `List.of()` 디폴트를 전달하도록 갱신(컴파일 보존).

### 데이터 흐름

```
SUT 소스 ─ConstraintExtractor.extractComparisons(단일 빌드)─▶ List<Comparison> + List<JoinGuard> ─┐
SUT 타입 ─BodyShapeExtractor(재귀 dot-path 평탄화)──────────▶ BodyShape(dot-path fields) ────────┤
                                                                                                  ├▶ EndpointTarget
HeuristicExplorer / CoverageGuidedFuzzer:                                                          │
   InputMutator.applyToBody(copy(seed), m)  ◀── forTarget(target): joinGuards + 기존 변이 ─────────┘
     ├ ObjectNode → m 직접 (putPath가 중첩 리프 도달)
     └ ArrayNode  → element[0] ObjectNode에 m
   + 배열 바디면 empty-array 구조 변이
```

### 격리·테스트성
- `putPath`/`removePath`/`applyToBody`/`joinGuards`/평탄화는 순수(시간·랜덤 없음) → 결정적 단위 검증.
- 변이 빌더 시그니처 불변 → 기존 `InputMutatorTest` 회귀 유효. `copy()` 반환형 변경은 호출부 2곳만 영향.

## E2E/수용 기준

최고 가능 수준 = **out-of-process 빌드→생성된 테스트 GREEN** E2E(기존 e2e 하니스). 각 AC는 다음
단계(`requirements-spec`)에서 REQ-ID + Given-When-Then + @DisplayName 태그로 승격된다.

**Fixture (구체).**
- AC-1: **기존** `samples/order-service/.../OrderBatchController.batch`(`@RequestBody
  List<CreateOrderRequest>`, 원소 검증 `userId blank` / `amount<=0` / `type blank`). 신규 fixture 불필요.
- AC-2: **신규 최소 핸들러** `samples/order-service`에 추가 — `POST /api/orders/ship`,
  `@RequestBody ShipRequest`(`record ShipRequest(String userId, Address address)`; `Address`는 기존
  `getCity/getStreet` POJO), 가드 `if (req.address()==null || req.address().getCity()==null ||
  req.address().getCity().isBlank()) → 400`. dot-path `null-address.city`/`remove-address.city` 변이가
  400 arm을 연다. (값-가드가 아니라 **존재/blank 가드** → fieldRef 체인 불요.)
- AC-3(숫자): **기존** 단위 fixture `BoundsController.handle`의 `if (req.amount() > req.score())`. 추출
  단위 테스트로 검증하고, 현재 "무시"를 단언하는 `ConstraintExtractorComparisonsTest`를 **JoinGuard
  추출을 단언하도록 갱신**(주석 "리터럴 없음 → 무시"도 정정).
- AC-3(문자열): **신규** 단위 fixture `sample-src/.../bounds/StringJoinController.java` — `String a,b`를
  `a.equals(b)`로 비교(양변 field-ref, 리터럴 없음). in-repo에 field-to-field `equals` 픽스처가 없어
  신규 추가. JoinGuard(STRING) 추출 + 2-arm 변이 단위 검증.

**수용 기준.**
- **AC-1(배열)**: `OrderBatchController.batch` 탐색에서 원소 필드 변이(예: `null-userId`,
  `zero-amount`)가 happy 외 ≥1개 신규 분기(`continue` arm)/응답차(`created` 변화)를 만든다.
- **AC-2(중첩)**: `/api/orders/ship`에서 (a) happy body가 `{"address":{"city":…}}` 중첩 JSON으로
  합성되고 2xx, (b) `null-address.city`/`remove-address.city` 변이가 400 arm을 커버.
- **AC-3(조인)**: `BoundsController`의 `amount > score`에서 JoinGuard 추출 → `a<b/a==b/a>b` 변이가
  생성된다(단위). E2E 가능 SUT가 있으면 양 arm 응답차 커버(없으면 단위로 충분, 그 사실 명시).
- **AC-4(회귀·정량)**: 기존 `e2e` 전체 GREEN + 대표 엔드포인트(예: `OrderController.create`,
  `BookingController`)의 covered-branch 집합이 변경 전의 **superset**(축소 없음). 전 모듈 단위/통합 GREEN.

## 리스크
- **느린 빌드/예산**: 배열·중첩·조인으로 변이 수 증가. → element[0]·깊이3·empty-array 1종·joinGuard
  2~3종으로 상한. 예산 회계(markTried/tryConsume) 불변이라 폭주 시 자연 종료.
- **중첩 평탄화 happy 합성 호환**: dot-path를 synthesizeObject가 putPath로 처리(변경 2) — AC-2(a)로 가드.
- **`copy()` 반환형 변경**: 호출부 `HeuristicExplorer:26`, `CoverageGuidedFuzzer:47` 2곳만 — 둘 다
  applyToBody 경유로 통일.
- **워크트리 머지 충돌**: feat-tia-index/feat-success-oracle 모두 중첩 평탄화·조인 가드 미보유(확인
  완료) → 본 작업이 최초. 머지 reconcile는 별도 관심사.

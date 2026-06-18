# 컬렉션 @RequestBody (List<DTO> 등) body shape 지원 — 설계

- 일자: 2026-06-18
- 브랜치: feat-list-dto-body-shape
- 리뷰: 3-모델(Sonnet/Gemini/GPT) needs_revision 반영(v2).

## 문제

`@RequestBody List<DTO>`(및 `Set`/`Collection`/배열/`List<scalar>`) 컬렉션 바디 엔드포인트가 탐색에서
**누락**된다. 네 곳이 객체-전용이기 때문이다.

1. **인덱서** — `EndpointIndexer.extractParams`가 `parameter.getType().getQualifiedName()`을 bodyType으로
   쓰는데, `List<DTO>`면 `"java.util.List"`(원소 타입 유실)를 돌려준다. `extractBodyShape`가 noClasspath
   모델에서 `java.util.List`를 못 찾아 shape를 못 만든다 → `bodyShapes` 미등록.
2. **skip** — `BuilderCli`가 `shape == null && !GET && path param 없음`이면 skip한다.
3. **합성** — `SampleInputSynthesizer`는 `createObjectNode()`로 단일 JSON 객체만 만든다(배열 불가).
4. **생성기(도구 2)** — `Generator`는 `FixtureComposer.bodyFormat`을 우선 쓰고 비었을 때만
   `jsonBodyFromInput`을 호출하는데, `jsonBodyFromInput`은 `sampleInput`이 `ObjectNode`가 아니면 `"{}"`를
   반환한다. 둘 다 배열을 처리 못 한다.

`BodyShape`는 `(javaType, List<BodyField>)`로 배열/컬렉션 개념이 없다.

## 범위

- **포함**: `@RequestBody`/Kafka `@KafkaListener`/WS 페이로드의 컬렉션 — `java.util.List`/`Set`/`Collection`/
  `Iterable<E>`, 배열 `E[]`. 원소 `E`는 DTO(객체) 또는 scalar(String/숫자/불리언/시간/**enum**).
- **생성 깊이**: **happy-path만**. 컬렉션 바디 = **유효 원소 1개짜리 배열**. 컬렉션 바디는 변이/음수-검증/
  by-id 탐색을 타지 않는다(happy 1회 호출).
- **제외(비목표)**: 컬렉션 원소별 음수-검증 arm, 빈 배열 `[]` arm, 다중 원소, 중첩 컬렉션/맵 바디,
  컬렉션 바디 + PATH param 조합(런타임 가드만 두고 합성은 안 함).

## 설계

### BodyShape 표현 (접근 1 — 필드명 javaType 유지)

`io.graphrag.builder.index.BodyShape`(빌더 내부 타입, graph.json 미직렬화)에 `collection` 플래그를 추가.
**기존 필드명 `javaType`를 유지**한다(현 호출부 `shape.javaType()` 보존).

```java
public record BodyShape(String javaType, List<BodyField> fields, boolean collection) {
    // 객체 바디 편의 생성자(기존 호출부 호환): collection=false
    public BodyShape(String javaType, List<BodyField> fields) {
        this(javaType, fields, false);
    }
}
```

원소 타입은 `javaType`에 담아 scalar/DTO를 `fields`로 구분한다(별도 elementType 필드 불필요):

- **객체 바디**: `collection=false`, `javaType`=DTO FQN, `fields`=DTO 필드.
- **컬렉션-of-DTO**: `collection=true`, `javaType`=**원소 DTO FQN**, `fields`=원소 DTO 필드.
- **컬렉션-of-scalar(enum 포함)**: `collection=true`, `javaType`=**원소 scalar/enum FQN**, `fields`=빈 리스트.

합성은 `collection && fields.isEmpty()`이면 scalar 배열, `collection && !fields.isEmpty()`이면 DTO 객체
배열로 분기한다. (필드 0개 DTO를 컬렉션 원소로 쓰는 희귀 케이스는 scalar로 취급 — 허용.)

**맵 키 vs javaType 분리**: `bodyShapes` 맵 키와 `EndpointParam.javaType`은 **원소 인코딩 키**를 쓰고,
`BodyShape.javaType`(record 필드)은 **원소 FQN**을 쓴다. ⇒ 서로 다른 `List<DTO>`가 `"java.util.List"`로
충돌하지 않고, `BuilderCli`의 `ValidationConstraintExtractor.extract(sutSrc, shape.javaType())`는 원소 DTO
FQN으로 정상 동작(원소 검증 제약을 happy 값에 반영).

```java
// 컬렉션: "java.util.List<com.x.Dto>", 배열: "com.x.Dto[]", 객체: "com.x.Dto"
static String BodyShapeExtractor.bodyTypeKey(CtTypeReference<?> type);
```

### (A) 인덱서 — 원소 타입 추출 + 키 + 일원화

공유 `BodyShapeExtractor`에 타입-참조 진입점을 추가(제네릭/배열은 `CtTypeReference`가 있어야 봄).

```java
public static Optional<BodyShape> extractFromType(CtModel model, CtTypeReference<?> type);
```

판정 순서(정확성):
1. **배열** `E[]`(`CtArrayTypeReference`) → 원소 = `getComponentType()`, collection=true.
2. **컬렉션** qualifiedName ∈ {`java.util.List`,`Set`,`Collection`,`Iterable`} 이고 `getActualTypeArguments()`가
   1개 → 원소 = 그 인자, collection=true. (인자 없는 raw `List` → 원소 불명 → `Optional.empty()` = 현행 유지.)
3. 원소 타입 분류:
   - 모델에서 원소 타입을 찾고 그것이 **`CtEnum`** 이면 → **scalar(enum)** (`fields=[]`, `javaType`=enum FQN).
   - 모델에서 찾고 enum이 아니면(클래스/record) → **DTO** (필드 추출, `javaType`=DTO FQN).
   - 모델에 없고 **scalar FQN 집합**(아래)에 속하면 → **scalar** (`fields=[]`, `javaType`=원소 FQN).
   - 그 외 → `Optional.empty()`.
4. 비컬렉션이면 기존 객체 추출(collection=false).

**scalar FQN 집합**: `SampleInputSynthesizer`가 인식하는 타입 — `INT_TYPES`(Integer/int/Long/long/Short/
short) ∪ `FLOAT_TYPES`(Double/double/Float/float/BigDecimal) ∪ {`java.lang.Boolean`,`boolean`,
`java.lang.String`} ∪ `java.time.*`(LocalDate/LocalDateTime/LocalTime/Instant/OffsetDateTime/ZonedDateTime)
∪ enumConstants에 항목이 있는 타입. 발산 방지를 위해 이 집합을 `SampleInputSynthesizer`의 상수에서 공유
(package-visible 노출)하거나 그곳에 위임한다.

**중복 제거 + 일원화**: `EndpointIndexer`의 private `extractBodyShape`/`findNested`를 제거하고 공유
`BodyShapeExtractor`로 일원화. 각 인덱서의 변경:

| 인덱서 | 현재 | 변경 |
|---|---|---|
| `EndpointIndexer.extractParams` (BODY/FORM) | `bodyType=getQualifiedName()`; private extractBodyShape | `bodyTypeKey(type)` 키 + `extractFromType(model, parameter.getType())` |
| `KafkaListenerIndexer` (payload) | `getQualifiedName()` + `BodyShapeExtractor.extract(model, qn)` | `bodyTypeKey(type)` + `extractFromType(model, type)` |
| `WsEndpointIndexer` (payload) | 동일 패턴 | 동일 |

(`EndpointParam.javaType`은 PATH/QUERY 제외용 이름 매칭 외엔 실제 클래스명으로 안 쓰임 — 구현 시 grep 재확인.)

### (B) 합성 — 배열 생성 + 파급 가드

`SampleInputSynthesizer`:
- `putScalar`의 타입별 값 로직을 `scalarValue(javaType, cons)` 헬퍼로 추출(객체 필드/배열 원소 공용,
  enum은 enumConstants 사용).
- `synthesize`가 `shape.collection()`이면:
  - DTO 원소: 기존 객체 합성(필드 채움 + FK seed)을 1회 수행 → `ObjectNode`를 `ArrayNode`에 담음.
  - scalar/enum 원소: `scalarValue(shape.javaType(), cons)` 1개를 `ArrayNode`에 담음.
- `SynthesizedInput.body` 타입을 `ObjectNode` → **`JsonNode`**(ObjectNode 또는 ArrayNode)로 넓힘.

**ObjectNode 전제 파급 — 변경 전파 체크리스트**(컬렉션 바디는 happy-only이므로 변이/음수 경로는 가드로 skip):

| 위치 | 처리 |
|---|---|
| `EndpointTarget.baseInput` (record 필드, ObjectNode) | `JsonNode`로 넓힘 |
| `EndpointExplorationRunner` happyInput `ObjectNode baseInput = happy.body()` | `JsonNode`로 받음 |
| happyInput **merge**(path+body `setAll`) | `body instanceof ArrayNode`면 merge skip하고 ArrayNode 그대로(컬렉션+path는 비목표) |
| `HeuristicExplorer` / `CoverageGuidedFuzzer` / `InputMutator` (`ObjectNode base/body`) | `base instanceof ObjectNode` 아니면 필드 변이 **skip**(컬렉션=변이 없음 → happy 1회만) |
| 음수-검증 패스(`exploreNegativeValidationVariants`, `NegativeValidationSynthesizer`) | `shape.collection()`이면 **skip**(비목표; ArrayNode crash 방지) |
| `bodyValues()` (bind origin 분류) | 입력이 ArrayNode면 **각 원소 ObjectNode를 unwrap**해 필드값 수집(아니면 bind가 LITERAL 오분류) |
| `KafkaCaptureRunner` `ObjectNode payload = happy.body()` | `JsonNode`로 받아 배열이면 그대로 레코드 값으로 직렬화; key 추출은 ArrayNode 가드 |
| `WsCaptureRunner` `List<ObjectNode> payloads` | `JsonNode` 수용; 컬렉션 페이로드 happy-only |

구현 시 위 목록을 grep(`ObjectNode .*= .*body\(\)` 등)으로 전수 확인한다.

### (C) 생성기(도구 2) — 배열 body 방출

`Generator`는 `fixture.bodyFormat()`을 우선 쓰고 비었을 때만 `jsonBodyFromInput`을 탄다. 둘 다 처리:
- `FixtureComposer`: `sampleInput.isArray()`이면 bodyFormat을 비워 fallback을 타게 하거나 배열을 직접
  bodyFormat으로 직렬화. (현재 `sampleInput.fields()`로 `{...}`를 만들어 배열에선 `{}`가 됨 → 수정.)
- `Generator.jsonBodyFromInput`: `sampleInput`이 `ArrayNode`면 그대로 직렬화(컬렉션 바디엔 제외할
  path/query body-field 없음). `ObjectNode` 가정인 response 필드 단언(`knownByField`)·`requestPath`
  path-var 치환은 ArrayNode면 skip.

### (D) E2E 수용 테스트 (우선 작성, red→green)

order-service에 컬렉션 바디 엔드포인트 추가:
- `POST /api/orders/batch` — `@RequestBody List<CreateOrderRequest>` → 각 원소로 order 저장, 생성 수 반환. (DTO 컬렉션)
- `POST /api/orders/by-ids` — `@RequestBody List<String> userIds` → 각 id 조회/집계 반환. (scalar 컬렉션)

수용 기준:
1. 빌더가 `post-api-orders-batch`를 **skip하지 않는다**.
2. 그 엔드포인트 `BodyShape.collection()==true`이고 합성 `sampleInput`이 **JSON 배열**(원소 1개)이다.
3. 컬렉션-of-DTO happy가 2xx로 탐색되고 INSERT SQL이 캡처되며, 그 SQL에 **`BindingOrigin.API_PARAM`**
   바인딩이 ≥1개(원소 필드 값이 요청 바디로 귀속됨 — `bodyValues` ArrayNode unwrap 검증).
4. 생성된 RestAssured 테스트가 **배열 body를 POST**한다(빈 `{}` 아님). 검증: (a) 최소 — 빌더 통합테스트가
   path 존재 + sampleInput ArrayNode + (3) bind를 assert; (b) 완전 — `run-e2e.sh`가 생성 테스트를 그린으로 실행.
5. scalar 컬렉션(`by-ids`)도 1·2·4 동등(SQL 있으면 3 포함).
6. 기존 회귀(객체 바디, Kafka/WS, OTEL 캡처) green 유지.

**Kafka/WS 컬렉션**: 공유 `BodyShapeExtractor`/`SampleInputSynthesizer` 수정으로 자동 적용되며,
`KafkaCaptureRunner`/`WsCaptureRunner`가 배열 페이로드를 수용한다(위 체크리스트). 검증은 단위 테스트
(extractor 컬렉션 판정 + 합성 배열 + 러너 배열 페이로드)로 한다. 별도 Kafka/WS 컬렉션 SUT 엔드포인트
E2E는 order-service에 저비용으로 추가 가능하면 포함, 아니면 후속 증분으로 명시 분리(HTTP가 외부-루프 E2E).

## 영향 범위 / 위험

- `BodyShape` 시그니처 확장 → 기존 `new BodyShape(qn, fields)`는 편의 ctor로 호환. 필드명 `javaType` 유지로
  `shape.javaType()` 호출부 무변경.
- `SynthesizedInput.body` `ObjectNode→JsonNode` → 위 체크리스트의 모든 ObjectNode 소비자 조정(컴파일러가
  대부분 잡고, 런타임 가드는 `instanceof ObjectNode`/`shape.collection()`).
- `EndpointParam.javaType`/`bodyShapes` 키 = 인코딩 키 → 키로 실제 클래스명을 쓰는 곳 없는지 grep.
- 컬렉션-of-scalar에서 `ValidationConstraintExtractor.extract(shape.javaType())`는 scalar/enum FQN이라 빈
  맵 반환(무해). 컬렉션-of-DTO는 원소 DTO FQN이라 정상.
- enum 컬렉션은 (A)-3에서 `CtEnum`으로 scalar 처리, 합성은 enumConstants 첫 상수.

## Definition of Done

- [ ] E2E 수용 1~6 green (order-service batch/by-ids + 생성 테스트).
- [ ] 단위: `BodyShapeExtractor.extractFromType`(List/Set/Collection/배열/raw/scalar/enum/DTO), `bodyTypeKey`
  비충돌, `SampleInputSynthesizer` 배열 합성(DTO/scalar/enum) + `scalarValue`, ObjectNode 가드(변이/음수 skip),
  `bodyValues` ArrayNode unwrap, `Generator`/`FixtureComposer` 배열 body 방출, Kafka/WS 러너 배열 페이로드.
- [ ] 전체 회귀(`./gradlew test` + `run-e2e.sh`) green.
- [ ] docs 갱신(03-graph-rag-builder 캡처/한계, 해당 시 04-test-generator).
- [ ] PR 전 spec-compliance + 코드 품질 리뷰 트리아지.

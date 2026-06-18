# 컬렉션 @RequestBody (List<DTO> 등) body shape 지원 — 설계

- 일자: 2026-06-18
- 브랜치: feat-list-dto-body-shape

## 문제

`@RequestBody List<DTO>`(및 `Set`/`Collection`/배열/`List<scalar>`) 컬렉션 바디 엔드포인트가 현재
탐색에서 **누락**된다. 세 곳이 객체-전용이기 때문이다.

1. **인덱서** — `EndpointIndexer.extractParams`가 `parameter.getType().getQualifiedName()`을 bodyType으로
   쓰는데, `List<DTO>`면 `"java.util.List"`를 돌려준다(제네릭 원소 타입 유실). `extractBodyShape`가
   noClasspath 모델에서 `java.util.List`를 못 찾아 shape를 못 만든다 → `bodyShapes`에 미등록.
2. **skip** — `BuilderCli`가 `shape == null && !GET && path param 없음`이면 엔드포인트를 skip한다
   (`"skip … (no @RequestBody shape and no path param)"`). 위 1 때문에 컬렉션 바디 POST/PUT가 skip된다.
3. **합성** — `SampleInputSynthesizer`는 `createObjectNode()`로 **단일 JSON 객체만** 만든다. 배열 불가.
4. **생성기(도구 2)** — `Generator.jsonBodyFromInput`이 `sampleInput`이 `ObjectNode`가 아니면 `"{}"`를
   반환한다. 배열 sampleInput을 request body로 방출하지 못한다.

`BodyShape`는 `(qualifiedName, List<BodyField>)`로 배열/컬렉션 개념이 없다.

## 범위

- **포함**: `@RequestBody`/Kafka `@KafkaListener`/WS 페이로드의 컬렉션 — `java.util.List`/`Set`/`Collection`/
  `Iterable<E>`, 배열 `E[]`. 원소 `E`는 DTO(객체) 또는 scalar(String/숫자/불리언/시간/enum).
- **생성 깊이**: **happy-path만**. 컬렉션 바디는 **유효 원소 1개짜리 배열**로 합성.
- **제외(비목표)**: 컬렉션 원소별 음수-검증 arm(`@Valid List<@Valid DTO>` 위반 변종), 빈 배열 `[]` arm,
  다중 원소, 중첩 컬렉션(`List<List<..>>`)·맵 바디. 이후 증분으로 남긴다.

## 설계

### BodyShape 표현 (접근 1)

`io.graphrag.builder.index.BodyShape`(빌더 내부 타입, graph.json 미직렬화)에 컬렉션 표식을 추가한다.

```java
public record BodyShape(String qualifiedName, List<BodyField> fields,
                        boolean collection, String elementScalarType) {
    // 객체 바디 편의 생성자(기존 호출부 호환): collection=false, elementScalarType=null
    public BodyShape(String qualifiedName, List<BodyField> fields) {
        this(qualifiedName, fields, false, null);
    }
}
```

- **객체 바디**: `collection=false`, `fields`=객체 필드, `elementScalarType=null` (기존과 동일).
- **컬렉션-of-DTO**: `collection=true`, `qualifiedName`=원소 DTO FQN, `fields`=원소 DTO 필드, `elementScalarType=null`.
- **컬렉션-of-scalar**: `collection=true`, `fields`=빈 리스트, `elementScalarType`=원소 scalar FQN(예: `java.lang.String`).

### (A) 인덱서 — 원소 타입 추출 + 키 + 일원화

공유 `BodyShapeExtractor`에 타입-참조 기반 진입점을 추가한다(원소 추출은 `CtTypeReference`가 있어야
제네릭/배열을 볼 수 있다 — 문자열 FQN으로는 불가).

```java
// 컬렉션/배열을 감지해 원소 shape로 환원. 객체면 기존 동작.
public static Optional<BodyShape> extractFromType(CtModel model, CtTypeReference<?> type);
```

- **배열** `E[]`: `type instanceof CtArrayTypeReference` → 원소 = `getComponentType()`.
- **컬렉션** `List/Set/Collection/Iterable<E>`: qualifiedName이 그 집합에 속하고 `getActualTypeArguments()`가
  1개면 원소 = 그 인자. (raw `List`(인자 없음)는 원소 불명 → 객체 폴백 없이 shape 없음 = 현행 유지.)
- 원소 FQN으로 DTO 필드 추출 시도: 모델에 그 타입이 있으면 `collection-of-DTO`, 없고 scalar로 인식되면
  `collection-of-scalar`(elementScalarType=원소 FQN). 둘 다 아니면 `Optional.empty()`.
- 비컬렉션이면 기존 객체 추출(`collection=false`).

`EndpointIndexer`의 **중복 private `extractBodyShape`/`findNested`를 제거**하고 공유 `BodyShapeExtractor`로
일원화한다(현재 두 벌 존재). `EndpointIndexer.extractParams`(BODY param), `KafkaListenerIndexer`,
`WsEndpointIndexer`가 모두 `extractFromType(model, parameter.getType())`를 호출한다.

**키 충돌 해결**: `bodyShapes` 맵 키와 `EndpointParam.javaType`을 원소-인코딩 형태로 만든다.

```java
// 컬렉션: "java.util.List<com.x.Dto>", 배열: "com.x.Dto[]", 객체: "com.x.Dto"
static String bodyTypeKey(CtTypeReference<?> type);
```

서로 다른 `List<DTO>` 엔드포인트가 `"java.util.List"`로 충돌하지 않고, `bodyShapeFor`가 동일 키로
shape를 해소한다. (`EndpointParam.javaType`은 PATH/QUERY 제외용 이름 매칭 외엔 실제 클래스명으로 쓰이지
않음 — 구현 시 grep으로 재확인한다.)

### (B) 합성 — 배열 생성

`SampleInputSynthesizer.synthesize`가 `shape.collection()`이면 happy 원소 1개를 배열로 감싼다.

- **컬렉션-of-DTO**: 기존 객체 합성(필드 채움 + FK seed 수집)을 1회 수행해 그 `ObjectNode`를 `ArrayNode`에
  담는다. seeds는 그대로 반환.
- **컬렉션-of-scalar**: `scalarValue(elementScalarType, 제약)`로 단일 scalar를 만들어 `ArrayNode`에 담는다.
  (현 `putScalar`의 타입별 값 로직을 `scalarValue(javaType, cons)` 헬퍼로 추출해 객체 필드/배열 원소가 공용.)
- 반환 `SynthesizedInput.body`의 타입을 `ObjectNode`에서 `JsonNode`(ObjectNode 또는 ArrayNode)로 넓힌다.
  `SynthesizedInput` 및 호출부(HTTP/Kafka/WS 러너)가 `JsonNode`를 수용하도록 조정.

HTTP/Kafka/WS가 같은 `SampleInputSynthesizer`를 쓰므로 한 번에 적용된다.

### (C) 생성기(도구 2) — 배열 body 방출

`Generator.jsonBodyFromInput`이 `sampleInput`이 `ArrayNode`면 그대로 직렬화해 body로 쓴다(현 `"{}"` 반환
버그 수정). `ObjectNode` 가정 지점을 가드한다:

- request body: ArrayNode → 배열 JSON 직렬화(컬렉션 바디엔 제외할 path/query body-field 없음).
- response 필드 단언(`knownByField`, line 277)·`requestPath` path-var 치환: 입력이 ObjectNode일 때만 적용,
  ArrayNode면 skip(컬렉션 바디 엔드포인트는 보통 path-var 없음).

### (D) E2E 수용 테스트 (우선 작성, red→green)

order-service(in-repo 샘플 SUT)에 컬렉션 바디 엔드포인트를 추가한다.

- `POST /api/orders/batch` — `@RequestBody List<CreateOrderRequest>` → 각 원소로 order 저장, 생성 수 반환.
  (컬렉션-of-DTO)
- `POST /api/orders/by-ids` — `@RequestBody List<String> userIds` → 각 id로 조회/집계 반환. (컬렉션-of-scalar)

수용 기준(`BuilderE2eTest` 확장 또는 신규 통합 테스트 + 생성 e2e):

1. 빌더가 `post-api-orders-batch`를 **skip하지 않는다**(이전: shape null → skip).
2. 그 엔드포인트의 `BodyShape`가 `collection=true`로 캡처되고, 합성 `sampleInput`이 **JSON 배열**이다.
3. 컬렉션-of-DTO happy 경로가 2xx로 탐색되고 INSERT SQL이 캡처된다(원소 필드 bind 귀속).
4. 생성된 RestAssured 테스트가 **배열 body를 POST**하고 통과한다(빈 `{}` 아님).
5. scalar 컬렉션도 1~4와 동등(SQL 없으면 2xx/적정 status까지).
6. 기존 회귀(객체 바디 엔드포인트, Kafka/WS) green 유지.

## 영향 범위 / 위험

- `BodyShape` 시그니처 확장 → 기존 `new BodyShape(qn, fields)` 호출부는 편의 ctor로 호환.
- `SynthesizedInput.body` 타입 `ObjectNode→JsonNode` → 호출부(러너들, 테스트) 조정 필요(컴파일러가 잡음).
- `EndpointParam.javaType`/`bodyShapes` 키 형식 변경 → 키로 실제 클래스명을 쓰는 곳이 없는지 grep 확인.
- 생성기 배열 body → graph.json `sampleInput`이 배열일 수 있음(스키마는 `JsonNode`라 무변경).

## Definition of Done

- [ ] E2E 수용 1~6 green (order-service batch/by-ids + 생성 테스트).
- [ ] 단위: `BodyShapeExtractor.extractFromType`(List/Set/Collection/배열/scalar/raw), `SampleInputSynthesizer`
  배열 합성(DTO/scalar), `Generator` 배열 body 방출, 키 비충돌.
- [ ] 전체 회귀(`./gradlew test` + `run-e2e.sh`) green.
- [ ] docs 갱신(03-graph-rag-builder 캡처/한계, 해당 시 04-test-generator).
- [ ] PR 전 spec-compliance + 코드 품질 리뷰 트리아지.

# Task 3 Report — Map<String,V> body support + List<scalar> guard

## Status
DONE

## 구현 내용

### BodyShapeExtractor.java

`extractFromTypeFlattened` 메서드에 `Map<K,V>` 타입 감지 로직을 추가했다.
새로운 private helper `extractMapShape`를 도입해 다음 규칙을 적용한다:

- 타입이 `java.util.Map`이 아니면 `null` 반환(호출자가 기존 처리 경로로 진행)
- `java.util.Map`이지만 타입 인자 수가 2개가 아니면 `Optional.empty()` 반환
- 키 타입이 `java.lang.String`이 아니면 `Optional.empty()` 반환 (non-String key 미지원)
- 키 타입이 `java.lang.String`이면 value 타입을 가리키는 단일 synthetic 필드 `"sampleKey"` 1개짜리 `BodyShape` 반환

### SampleInputSynthesizer.java

변경 없음. 기존 `synthesizeObject` 경로가 `sampleKey` 필드를 자연스럽게 처리한다:
- `sampleKey`는 `"Id"` suffix가 없으므로 FK 로직 건너뜀
- `putScalar`가 value javaType에 맞는 결정적 값을 `{"sampleKey": <value>}` 형태로 합성

## Map 표현 설계 결정

`Map<String,V>` 바디를 **단일 synthetic 필드 `sampleKey`**로 모델링한 이유:

1. **기존 DTO 합성 경로 재사용** — SampleInputSynthesizer의 `synthesizeObject`는 이미 필드 목록을 JSON 오브젝트로 합성한다. `{"sampleKey": <value-type-scalar>}` 형태가 실제 `Map<String,V>` 바디의 happy-path 요청과 동일한 구조다.
2. **YAGNI** — Map entry 수 변이, key 목록 커스텀, 중첩 Map 등은 현재 요구사항 범위 밖. 1-entry happy 바디만 필요.
3. **non-String 키 배제** — JSON 오브젝트의 키는 항상 문자열이어야 하므로, `Map<Integer,V>` 등은 기술적으로 직렬화가 가능하지만 Spring이 `@RequestBody`로 받을 때 실질적 사용 패턴이 아님. 미지원 명시 반환(`Optional.empty()`)이 "endpoint silently skipped" 대비 진단 가능성이 높다.

## 테스트 요약

추가된 테스트 3개:

| 테스트 | 위치 | 결과 |
|---|---|---|
| `mapBody_stringKey` | BodyShapeExtractorTest | GREEN (새 구현으로 통과) |
| `mapBody_nonStringKey_empty` | BodyShapeExtractorTest | GREEN (기존 동작과 일치, 빈 반환) |
| `scalarList_alreadyWorks` | SampleInputSynthesizerTest | GREEN (기존 구현 이미 지원, REQ-004 guard) |

## 우려 사항

없음. 변경 범위가 `BodyShapeExtractor.extractFromTypeFlattened` 진입부로 국한되어 있고, `extractFromType`(form 커맨드 경로)에는 의도적으로 Map 처리를 추가하지 않았다 — form binding에서 `Map<>` 바디가 실제로 사용되는 케이스가 확인되지 않았기 때문.

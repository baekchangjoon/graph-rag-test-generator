# 28 — 폼 / ParamMap 엔드포인트 테스트 생성 (followup)

test-generator는 현재 **폼 커맨드 객체**(`@ModelAttribute` / form-urlencoded, `ParamKind.FORM`)와
**동적 파라미터 맵**(`@RequestParam Map` / `MultiValueMap`) 엔드포인트의 테스트를 생성하지 않는다.
구체적으로 `Generator.java`가 FORM 파라미터를 가진 엔드포인트를 거부한다:

```java
// test-generator/.../Generator.java (거부 게이트)
if (endpoint.params().stream().anyMatch(p -> p.kind() == io.graphrag.model.ParamKind.FORM)) {
    return new GenerationResult(List.of(),
            List.of("form endpoint not generated (coverage-only): " + endpoint.id()),
            new io.graphrag.model.ParallelSafetyReport(List.of(), List.of()));
}
```

그리고 요청 템플릿이 `application/json` + `.body()`를 하드코딩한다:

```mustache
{{! test-generator/src/main/resources/templates/test-class.mustache }}
            .contentType("application/json")
{{^readPath}}            .body({{{bodyExpr}}})
{{/readPath}}        .when()
            .{{httpMethodLower}}("{{{requestPath}}}")
```

이 문서는 지원에 필요한 작업을 세 갈래(A/B/C)로 정리한다. 각 항목은 착수 시 각자
spec → requirements-spec → plan 으로 확장한다(라인 번호는 변동 가능하므로 조건/심볼로 식별).

---

## A. form-urlencoded 폼 커맨드 객체 생성 — 가장 가까움, 우선

### 현황
- **빌더는 완전 지원한다.** `EndpointIndexer`가 `@ModelAttribute` 또는 필드 보유 POJO를
  `ParamKind.FORM` 커맨드 객체로 추출하고, `EndpointExplorationRunner`가
  `application/x-www-form-urlencoded`로 전송(폼 분기에서 Content-Type 설정),
  `FormBodySynthesizer`가 중첩 필드를 dot-path 스칼라로 평면화한다. 따라서 `graph.json`에는
  폼 엔드포인트의 `ExploredPath`(평면 폼 필드 sampleInput)·expected status·SQL seed·branch가
  이미 들어 있다.
- **막힌 곳은 test-generator 한 곳뿐이다** — 위 거부 게이트와 JSON 하드코딩 템플릿.

### 지원하려면 필요한 것 (생성기/템플릿 국한, 빌더 무변경)
1. `Generator`의 FORM 거부 게이트 제거 → 폼 분기로 전환.
2. `buildScenarioMethod`: `boolean form` 도출 후, 폼이면 `bodyExpr` 대신 `formFields`(name→valueExpr)
   생성 — `path.sampleInput()`의 평면 폼 필드를 순회하며 `fixture.substitutions()`에 있으면 런타임
   변수 표현식(시드 PK 등 와이어링), 없으면 리터럴. **값 합성은 JSON 바디와 동일한
   `ComposedFixture`/`FixtureComposer`를 그대로 재사용**하고 직렬화만 분기한다.
3. `ScenarioMethod` 레코드에 `boolean form`, `List<FormField(name, valueExpr)>` 추가 +
   `ms.put("form"/"formFields", ...)`.
4. `test-class.mustache`: 폼 분기 추가.
   ```mustache
   {{#form}}            .contentType("application/x-www-form-urlencoded")
   {{#formFields}}            .formParam("{{name}}", {{{valueExpr}}})
   {{/formFields}}{{/form}}{{^form}}            .contentType("application/json")
   {{^readPath}}            .body({{{bodyExpr}}})
   {{/readPath}}{{/form}}
   ```

### 통합 지점
- `Generator.java`: 거부 게이트, `bodyExpr` 산출부, `ScenarioMethod` 생성부, `ms.put` 컨텍스트.
- `test-class.mustache`: contentType/body 블록.
- 재사용: `compose/FixtureComposer`(값·시드·단언·정리), `model/ExploredPath.sampleInput()`(평면 폼 필드).

### 엣지/주의
- 중첩 필드 = Spring 표준 dot-path 키(`address.city`)로 `.formParam`.
- 다중값(List) = 동일 키 `.formParam` 반복.
- negative-validation/auth 변형은 기존 JSON과 동일하게 default flow에서 필터(happy 위주 생성).
- 필드 0개면 contentType만 폼으로.

### E2E / 완료 정의
- outer: 기존 폼 샘플 SUT(`samples/order-service/.../OrderWebController.java`,
  `RefFormController.java` 등)로 build → generate → 생성 테스트 실행, form-urlencoded POST가
  2xx로 통과.
- inner: `GeneratorTest`에 폼 렌더링 단위 테스트(contentType·formParam·중첩 dot-path·치환 와이어링).

### 리스크
낮음 — 변경이 생성기/템플릿에 국한되고 빌더는 무변경. 값 로직을 JSON과 공유하므로 drift 위험도 작다.

---

## B. 멀티파트(multipart/form-data, 파일 업로드)

### 현황
- **빌더가 명시적으로 범위 밖으로 처리한다.** `EndpointExplorationRunner`의 폼 인코딩은 평면 스칼라만
  보내고 비스칼라/멀티파트 필드는 drop한다("nested/multipart out of scope" 디버그 로그). 즉 파일
  파트 합성·`multipart/form-data` 전송·캡처 경로가 없다.
- 따라서 (A)와 달리 **빌더 + 생성기 양쪽 확장**이 필요하다.

### 지원하려면 필요한 것
- **빌더:** `MultipartFile`/`@RequestPart` 파라미터 인식, 파일 파트 더미 콘텐츠 합성(결정적 바이트),
  `multipart/form-data` 전송, 멀티파트 요청 캡처(파트 메타데이터를 graph.json에 표현).
- **생성기:** RestAssured `.multiPart(...)` 렌더, 파일 픽스처(결정적 임시 파일/바이트, 테스트
  종료 시 정리), 텍스트 파트와 파일 파트 혼합 처리.
- **모델:** 멀티파트 파트(파일 vs 텍스트, content-type, 파일명)를 표현하는 스키마 확장.

### 리스크 / 의존
- 빌더·생성기·모델 3계층 변경으로 (A)보다 범위·리스크가 크다.
- 파일 픽스처의 결정성과 자원 정리(임시 파일 누수) 주의.
- (A) 완료 후 착수 권장.

---

## C. 동적 `@RequestParam Map` / `MultiValueMap` — 정적 한계 (경우 2)

### 현황
- `EndpointIndexer`는 `@RequestParam`을 `ParamKind.QUERY`로 잡지만, 파라미터 타입이 `Map`/
  `MultiValueMap`이면 **합성할 키(필드명)가 정적으로 드러나지 않는다.** 정적 분석만으로는 빈 맵이나
  추정 키밖에 만들 수 없다 — 이는 [22-static-discovery-limits](../../22-static-discovery-limits.md)
  계열의 도달 한계다.

### 지원하려면 필요한 것
- **런타임 키 관측:** 빌더가 실제 사용된 파라미터 키를 폼/쿼리 바인딩 로그·SQL 바인딩에서 역추출,
  또는 핸들러 본문에서 `map.get("...")` 리터럴 키를 정적 추출(부분적), 또는
- **외부 계약 기반:** OpenAPI/문서에서 파라미터 키 도출, 또는 사용자 제공 힌트.
- **모델:** 동적 맵 파라미터를 graph.json 스키마에 표현(키 집합 출처/신뢰도 포함).

### 리스크
- 본질적으로 정적 미도달 → 런타임 발견 또는 외부 계약 의존이 필요해 범위가 크다.
- [22-static-discovery-limits](../../22-static-discovery-limits.md)에 "동적 파라미터 맵" 한계로도 교차
  기록할 것.

---

## 권장 순서

1. **A(form-urlencoded)** — 빌더 캡처와 1:1로 맞고 생성기/템플릿 변경에 국한, ROI 최고. 우선 착수.
2. **B(멀티파트)** — A 이후, 빌더+생성기+모델 확장.
3. **C(동적 Map)** — 독립적, 런타임 발견/외부 계약 설계 선행 필요. 정적 한계 문서와 연계.

각 항목은 착수 시 brainstorming → requirements-spec(REQ + E2E) → writing-plans 순으로 확장한다.

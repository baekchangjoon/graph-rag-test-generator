# @Controller 폼 핸들러 인덱싱 (form-urlencoded 탐색)

작성일: 2026-06-15 · 브랜치: `worktree-feat-controller-forms` (main 기준)
근거: petclinic 미커버 **최대 덩어리** — 빌더가 `@RestController`(REST/JSON)만 인덱싱해 `@Controller` Thymeleaf
폼 흐름(`PetController`/`OwnerController`/`VisitController` + `PetValidator`/`PetTypeFormatter`)이 미진입.
#1/#2의 HTTP 폼 버전 — 새 진입점 종류(form-urlencoded). **3-모델 리뷰 후 확정(§8).**

## 1. 문제
`EndpointIndexer`는 `findAnnotation(type, REST_CONTROLLER)` 없으면 skip(line 64). `@Controller` 폼 핸들러
(`processCreationForm(Owner owner, @Valid Pet pet, BindingResult result)` → 폼 필드 바인딩 + 검증 + `redirect:/...`)는
미인덱싱 → 폼 바인딩/검증 분기 미탐색. 폼은 **application/x-www-form-urlencoded**(JSON 아님).

## 2. 접근 — @Controller 폼 핸들러를 form-urlencoded 엔드포인트로 인덱싱·탐색
- **인덱서**: `@Controller`(신규 상수 `CONTROLLER`)도 처리. `@RestController`면 기존 JSON 경로(불변). `@Controller`-only면
  폼 경로: 매핑 메서드의 **커맨드-객체 파라미터**(=@RequestBody/@PathVariable/@RequestParam 미부착 POJO이고
  `extractBodyShape`로 **SUT 클래스 필드가 해석되는** 것 — `BindingResult`/`Model` 등 프레임워크 타입은 shape 없음→
  자동 제외) 또는 명시 `@ModelAttribute`를 **새 `ParamKind.FORM`**으로 추가 + bodyShape 등록. FORM 파라미터가 하나도
  없으면(뷰-표시 GET 등) 해당 핸들러 skip(템플릿 없으면 의미 분기 없음). 다중 커맨드 객체는 필드 union.
- **모델**: `ParamKind`에 `FORM` 추가(Endpoint 레코드 변경 없음 — invoker가 FORM 파라미터 유무로 form 모드 판정).
- **합성**: FORM shape를 기존 `SampleInputSynthesizer`로(제약-aware happy) 합성(내부 JSON). 비-FORM 경로 불변.
- **httpInvoker(doSend)**: 엔드포인트에 FORM 파라미터가 있으면 `Content-Type: application/x-www-form-urlencoded` +
  body=폼-인코딩(`field=urlencode(value)&...`, 평면 필드만). JSON 분기는 불변. HttpClient는 기본 리다이렉트 미추종 →
  `redirect:/...`는 302로 관측(성공), 검증 실패는 재-렌더(뷰 없으면 4xx/5xx) 또는 redirect arm.
- **탐색 불변**: 인덱싱·form-encoding만 추가하면 **기존 explorer(happy + fuzz + 제약-aware)** 가 폼 핸들러를 구동해
  바인딩/검증 분기를 커버. 추가 탐색 로직 없음.

## 3. in-repo 벤치마크 (order-service)
order-service는 `spring-boot-starter-web`(MVC)만 — 템플릿 엔진 없음. **redirect-기반 @Controller 폼 핸들러** 추가
(뷰 불요): 커맨드 객체 + 명령형 가드 + 양 arm 모두 redirect(302).
```java
@Controller @RequestMapping("/web/orders")
class OrderWebController {
  record OrderForm(String customer, Integer quantity) {}
  @PostMapping String submit(OrderForm form, BindingResult result) {
    if (form.quantity()==null || form.quantity()<1 || form.quantity()>100) return "redirect:/web/orders/error";
    return "redirect:/web/orders/ok";  // 둘 다 302 — 템플릿 불요
  }
}
```
빌더가 이를 form-urlencoded로 인덱싱·탐색 → quantity 가드 양 arm(유효→ok, 무효→error) 커버.

## 4. E2E/수용 기준 (먼저 작성, 바깥 루프 — Docker 필요)
1. **인덱싱(결정적)**: `asset.endpoints()`에 `post-web-orders`(또는 해당 id)가 있고, `customer`/`quantity` **FORM**
   파라미터를 가진다. 되돌리면(@RestController-only 인덱싱) 그 엔드포인트 없음 → FAIL.
2. **form-urlencoded 탐색**: `post-web-orders`가 happy(302 redirect /web/orders/ok)와 검증-실패(302 /web/orders/error)
   path를 갖는다(양 arm). 캡처된 요청이 form-encoded(JSON 아님)임을 확인.
3. **무회귀**: 기존 BuilderE2eTest 단언(REST JSON 201/404/400/409, Kafka, WS, state-guard, inter-field, negative-auth) 불변.
   기존 REST 엔드포인트 인덱싱·JSON body 불변(@RestController 경로 무변경).
4. **생성**: 폼 path는 정상 ExploredPath이므로 생성기가 form-urlencoded 테스트를 생성(또는 현 생성기가 form 미지원이면
   happy만/스킵 — §8에서 결정). run-e2e 무회귀.
5. **전 SUT 회귀**: petclinic + tainted-spring MVC 6개 스윕 — @RestController-only SUT 무영향, @Controller 폼 보유
   SUT(petclinic)는 폼 분기 커버 **추가**(PetController/OwnerController/VisitController + PetValidator/PetTypeFormatter).

## 5. Double-loop TDD 순서
1. **바깥 먼저(RED)**: §4-1/4-2 단언을 `BuilderE2eTest`에 추가 — RED.
2. **inner #1 인덱서(단위, RED→GREEN)**: `EndpointIndexerTest`(또는 신규) — @Controller 폼 핸들러 픽스처 → FORM 파라미터
   인덱싱, BindingResult/Model 제외, @RestController JSON 불변.
3. **inner #2 form 합성/전송(단위)**: form-urlencoded 인코딩 헬퍼 단위(평면 필드, urlencode). doSend의 form 분기.
4. **inner #3 배선**: 인덱서 @Controller + FORM, doSend form-encoded. 기존 빌더 단위/통합 불변.
5. **단위 회귀(no Docker)** → **바깥 GREEN(Docker)**: BuilderE2eTest 폼 인덱싱·탐색 통과.
6. **PR 게이트**: 회귀 green + docs/24·docs/03 갱신 → spec-compliance 리뷰 → code-quality 리뷰 → triage.

## 5b. 생성기 영향 (미정 — §8 리뷰로 확정)
폼 ExploredPath는 generateSingle이 JSON body로 생성하려 할 수 있음(form 미지원). 옵션: (a) 생성기가 FORM 엔드포인트에
form-urlencoded 테스트 생성(스코프 큼), (b) 폼 path는 커버리지 전용으로 생성 제외(negative-auth처럼 마킹). 리뷰 후 결정.

## 6. 범위 / 비범위
- **범위**: @Controller 폼-제출(POST) 핸들러 인덱싱 + form-urlencoded 합성·전송 → 폼 바인딩/검증 분기 커버. redirect 기반(뷰 불요) in-repo 벤치마크.
- **비범위**: HTML 뷰 렌더링 단언(템플릿 의존)·뷰-표시 GET 핸들러(분기 없음)·multipart/파일 업로드·중첩 폼 객체(평면 필드만). 폼 negative-test 생성은 §8 결정.

## 7. 관련 파일
- 수정: `index/EndpointIndexer.java`(@Controller + FORM param), `model/ParamKind.java`(FORM), `run/EndpointExplorationRunner.java`(doSend form-encoded + bodyShapeFor/happyInput FORM 취급), `cli/BuilderCli.java`(bodyShapeFor FORM), `samples/order-service`(@Controller 벤치마크).
- 테스트: `EndpointIndexer` 단위(폼 픽스처), `BuilderE2eTest`(수용).
- 문서: `docs/24`·`docs/03`.

## 8. 3-모델 리뷰 triage (Opus/Sonnet/Haiku)
세 모델 needs_revision — 접근은 sound이나 **ParamKind.FORM이 여러 소비 사이트를 조용히 깨뜨림**을 정확히 지적.
반영(구현 시 이 사이트 전부 처리):
- **(critical) FORM 소비 사이트 전수**: ① `BuilderCli.bodyShapeFor`(현재 kind==BODY만)도 **FORM shape 선택** →
  이게 되면 skip-guard(`shape==null && !GET && !hasPathParam → skip`)·`mutableFields`(=shape.fields())·
  `happyInput` 비-GET 분기가 자동 동작(shape!=null). ② `bodyOnly`(현재 비-BODY 필드 strip)가 **FORM 필드 보존**.
  ③ `doSend`가 FORM 파라미터 유무로 `application/x-www-form-urlencoded` + 폼-인코딩 분기(JSON 헤더 교체). (Opus I1/I2, Sonnet I1/I2/I3/I4)
- **(critical) 생성기 = skip으로 확정**(§5b): FORM 엔드포인트는 `Generator.generate` 진입부에서 **생성 제외**(빈 결과
  + warning) — generateSingle의 JSON body 가정이 폼에 깨진 테스트를 내는 것 방지(negative-auth와 동일 정신). 커버리지 전용. (Opus I1, Sonnet I6)
- **(important) order-service 벤치마크 도달성**: SecurityConfig CSRF **disabled 확인**, `/web/orders`는 authRequired →
  explorer가 valid 토큰 주입 → 컨트롤러 도달. 양 arm을 **Location**(/ok vs /error)으로 구분 단언. (Opus I4)
- **(important) 단일 커맨드 객체로 스코프**: 다중 커맨드 객체 union은 **비범위**(드묾; petclinic 핵심은 단일). 첫 FORM 파라미터만. (Opus I5, Sonnet I9, Haiku I6)
- **(important) 평면 스칼라 필드만**: String/숫자/날짜(asText) form-encode. entity-Formatter(PetType id) 필드는 best-effort/비범위. (Sonnet I7)
- **(important) extractBodyShape 해석**: noClasspath FQN 미해석 위험 → simple-name 폴백 추가(findAnnotation 패턴). 벤치마크 OrderForm은 nested record라 해석됨. (Opus I3)
- **(important) @Controller GET-by-id 미회귀**: 스코프는 **FORM 파라미터 있는 @Controller 핸들러만** 인덱싱 —
  기존 @RestController read-path 무변경. @Controller GET-by-id read는 비범위(별도). (Opus I7)
- **반영(문서)**: E2E 단언 form-encoded 확인은 httpCalls 캡처의 Content-Type/body로(§9). RED 테스트 구체화는 구현 시 코드로.
- **거부/보류**: 다중 커맨드 객체·entity-Formatter·뷰 렌더링 단언·form 테스트 생성은 비범위(§6). petclinic 폼은 외부 스윕에서 커버 추가(단언은 order-service 한정).

## 9. 구현 중 확정/변경 (코드와 문서 동기)
- **arm 구분 = branchesTaken + status(302)** (계획의 "Location"에서 변경): `/web/orders` 양 arm은 모두 302
  redirect(/ok vs /error)이고 `ExploredPath`는 Location 헤더를 캡처하지 않으며 Java `HttpClient` 기본
  redirect 정책이 NEVER라 302가 그대로 기록된다. 따라서 수용 단언은 **valid-token path(=negative-auth 제외)가
  분기 집합이 다른 ≥2개 + 모두 302**로 확정. ok arm은 quantity가 [1,100]으로 폼 바인딩됐을 때만 도달하므로
  ≥2 distinct 분기 집합 존재 자체가 form-urlencoded 정합을 증명한다(JSON 전송이면 quantity=null → error arm만).
- **authRequired = negative-auth 403 path 동반**: `/web/orders`는 authRequired라 무효-토큰 negative-auth
  403 path도 생긴다 → 단언은 valid path만 필터.
- **(신규, petclinic 스윕에서 발견) 클래스-레벨 path 변수 크래시 → 방어적 URL 빌드**: petclinic `@RequestMapping("/owners/{ownerId}")`
  + `@ModelAttribute findOwner(@PathVariable ownerId)` 패턴은 `{ownerId}`가 핸들러 파라미터가 아니라 치환이 안 돼
  `URI.create`가 깨졌다(`IllegalArgumentException`). `buildPathAndQuery`가 매칭 안 된 `{...}`를 센티널("0")로
  치환하도록 수정(`EndpointExplorationRunner`, defense-in-depth). 단위 가드 `EndpointExplorationRunnerUrlTest`.
  한계는 `docs/03 한계`에 명시(부모 미시드 → not-found arm만 커버).
- **회귀/커버리지(스윕)**: order-service e2e 53/53 GREEN(폼은 커버리지 전용·생성 제외라 생성 수 무변). petclinic
  47.0%→51.8% APP-AGGREGATE(119/253→131/253), 엔드포인트 17→24(+7 @Controller 폼), @RestController 엔드포인트
  path/branch 카운트 무변(회귀 0), 크래시 0. 그 외 MSA(auth-user/diary/mindgraph/community/analytics/notification)는
  non-rest `@Controller` 0개 → 인덱싱 바이트-동일(영향 없음, 정적 확인).

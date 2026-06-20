# graph-rag 도구 개선 제안 — tainted-spring MSA Method 1 적용에서 드러난 한계점

> 작성일: 2026-06-20 · 대상 레포: `graph-rag-test-generator` (이하 graph-rag)
> 작성 배경: tainted-spring MSA 8개 서비스에 graph-rag(Method 1, 블랙박스 REST 테스트
> 생성)를 적용하며 발견한 도구 한계를 graph-rag 측에서 수정할 수 있도록 정리한 제안 문서.
> 성격: 개선 제안(RFC) — 구현 착수 전 방향·근거·수용 기준을 못 박기 위한 문서.
> 리뷰: 3-벤더 design-doc 리뷰(Claude Sonnet · Gemini · Cursor)를 거쳐 소스 인용·근본
> 원인 진단을 실코드로 정정한 개정본 (+ 우선순위·G4 진단을 실코드 재대조로 2차 정정 — 부록 C).

---

## 1. 문서 개요

### 1.1 목적
graph-rag를 실제 이기종 MSA(8개 Spring 서비스)에 적용한 결과, **도구 자체의 기능
공백(tool gap)**으로 일부 서비스에서 REST 표면을 탐색·생성하지 못하거나 생성 테스트가
재현 시 실패하는 사례가 드러났다. 본 문서는 그 한계를 **증상 → 근본 원인(소스 위치) →
개선 제안 → 수용 기준** 순으로 정리해 graph-rag 백로그/구현의 입력으로 삼는다.

### 1.2 범위
- **포함:** Method 1(graph-rag-builder의 정적 인덱싱·탐색, test-generator의 합성) 단계에서
  발견된 도구 한계 5종(G1~G5)과 각 개선 제안.
- **불포함:** tainted-spring 서비스 측 코드 변경(이미 별도 캠페인으로 테스트 보강·머지
  완료). 본 문서는 graph-rag **도구**만 다룬다.

### 1.3 적용 결과 요약(근거 데이터)
8개 서비스에 Method 1을 적용한 실측 결과:

| 서비스 | 라우팅 방식 | 발견 엔드포인트 | 생성/통과 | 비고 |
|---|---|---|---|---|
| diary | `@RestController` | 5 | 11 생성 / 8 통과·3 격리 | G3·flaky·path-var |
| analytics | `@RestController` | 2 | 4 통과 | 한계 없음 |
| community | `@RestController` | 5 | 25 통과 | 한계 없음 |
| notification | `@RestController` | 1(+Kafka 2) | 4 통과 | G5a(Redis read-state) |
| auth-user | `@RestController` | 5 | 7 통과 | 소셜 IdP 외부 의존 |
| mindgraph | `@RestController` | 2 | 5 생성 / 4 통과·1 격리 | G4(stale-seed) |
| **counseling** | **WebFlux 함수형(`RouterFunctions`)** | **0** | Kafka 2만 | **G1** |
| **bff-gateway** | **선언형 Gateway 라우트** + 일부 `@RestController` | **2**(composite만) | 2 통과 | **G2** |

핵심 관찰을 두 축으로 구분한다:
- **인덱싱(발견) 축:** `@RestController` 기반 6개 서비스는 REST 엔드포인트 **발견·생성에
  성공**했고, **함수형/선언형 라우팅을 쓰는 2개 서비스(counseling·bff-gateway)에서 REST
  표면 발견이 0 또는 부분**에 그쳤다. — G1·G2 (본 제안의 핵심 동기).
- **재현(합성·실행) 축:** 발견에 성공한 서비스 중에서도 diary(3건)·mindgraph(1건)는
  생성 테스트가 재현 시 불일치해 **격리**가 발생했다. — G3·G4·G5 (재현 정합성 문제).

즉 "6개 깨끗이 통과"가 아니라 "**6개는 인덱싱에 성공했으나 diary·mindgraph에는 재현 격리가
남았다**"가 정확한 서술이다.

**표본·일반화 한계(C5):** 위 수치는 **단일 캠페인(8개 서비스, 대부분 작성자 운영 fork)** 실측이다.
따라서 §7의 "심각도"는 graph-rag 전체 대상 모집단이 아니라 **이 캠페인 내 심각도**이며, 특히 G1·G2의
High는 **함수형/선언형 라우팅을 채택한 SUT에 한정된 조건부 심각도**다(그 스타일이 graph-rag 대상
모집단에서 차지하는 비중은 미측정). MVC 어노테이션 스타일 SUT에는 기존 인덱서가 견고하다는 사실과
함께 읽어야 한다 — 본 RFC는 "그 스타일을 쓰는 SUT를 지원 범위에 넣는다"는 범위 확장 제안이지,
모든 SUT에서 High라는 주장이 아니다.

---

## 2. 현황 — EndpointIndexer의 현재 동작

graph-rag-builder의 정적 인덱싱은 `EndpointIndexer`(Spoon noClasspath 기반)가 담당한다.
핵심 로직(`graph-rag-builder/src/main/java/io/graphrag/builder/index/EndpointIndexer.java`):

- `index(...)`는 Spoon으로 SUT 소스를 파싱(`setNoClasspath(true)`, `setComplianceLevel(17)`)
  한 뒤 `model.getAllTypes()`를 순회한다. (L54~66)
- 각 타입에 대해 **클래스-레벨 어노테이션** `@RestController` 또는 `@Controller`가 있는지
  본다. 없으면 `continue`로 건너뛴다. (L67~73)
- 컨트롤러 타입의 각 **메서드**에서 `MAPPING_TO_METHOD` 맵(L40~47)에 정의된 **5개 verb
  매핑 어노테이션** `@GetMapping/@PostMapping/@PutMapping/@DeleteMapping/@PatchMapping`만
  순회해 찾는다. 없으면 `continue`. 있으면 path·param·body를 추출해 `Endpoint`로 인덱싱.
  (L77~90)
- **주의(정정):** `@RequestMapping`(`REQUEST_MAPPING` 상수 L31)은 **클래스-레벨 basePath
  추출에만** 쓰인다(L74). 메서드-레벨 `@RequestMapping(method=…)` 핸들러는 인덱싱하지
  않는다(소규모 부수 gap — §3 비고 참조).

즉 **엔드포인트의 정의를 "verb 매핑 어노테이션이 붙은 컨트롤러 메서드"로만 가정**한다. 이
가정은 Spring MVC 어노테이션 스타일에는 정확하지만, 아래 두 라우팅 스타일은 구조가 전혀
달라 인덱서의 시야에 들어오지 않는다.

> 선례: 본 repo는 이미 어노테이션 외 표면을 별도 인덱서로 다룬다 —
> `WsEndpointIndexer`(WebSocket/STOMP config invocation), `KafkaListenerIndexer`(@KafkaListener),
> `MapperXmlIndexer`, `ResponseDtoIndexer`가 `BuilderCli`에 배선되어 있다(BuilderCli.java
> L162~168). 함수형/선언형 라우팅도 **동일 패턴(별도 indexer + BuilderCli 배선)**으로
> 확장 가능하다(§5 P1·P2 참조).

---

## 3. 발견된 한계점

각 항목은 **증상 → 영향 서비스 → 근본 원인(소스 위치) → 현재 회피**로 기술한다.
우선순위는 §7 표를 단일 기준(authoritative)으로 한다.

### G1. WebFlux 함수형 라우팅(`RouterFunctions`) 미발견 — 심각도 High
- **증상:** REST 엔드포인트가 하나도 발견되지 않음(0개). 생성 가능한 블랙박스 REST
  테스트가 전무.
- **영향 서비스:** counseling (`POST /internal/counseling/sessions`,
  `POST /internal/counseling/sessions/{id}/messages` 등 전부 미발견).
- **근본 원인:** 함수형 라우팅은 `@RestController`/`@*Mapping`이 없다. 라우트가
  `@Bean`이 반환하는 `RouterFunction<ServerResponse>` 안에서
  `RouterFunctions.route().POST(path, handler)...build()` **메서드 호출 체인**으로
  선언된다. `EndpointIndexer`는 클래스-레벨 `@RestController`/`@Controller`가 없으면
  타입 자체를 `continue`로 건너뛰므로(EndpointIndexer.java L67~73), 이 라우트 정의는
  파싱 트리에 있어도 **검사 대상에 포함되지 않는다.**
- **현재 회피:** 구조적 회피책 없음. `--manual-paths`(BuilderCli.mergeManualPaths, docs/22)는
  존재하는 escape hatch이나, 캡처·SQL 근거 없이 path·요청·상태를 수기로 주입해야 하므로
  **자동 커버리지의 무-fabrication 원칙에는 부적합**하다(정답은 구조적 인덱싱 — P1).

### G2. 선언형 Spring Cloud Gateway 라우트 미발견 — 심각도 High
- **증상:** 게이트웨이 프록시 표면이 0개 발견. `@RestController`로 작성된 일부
  엔드포인트(예: `CompositeController`)만 부분 발견.
- **영향 서비스:** bff-gateway (6개 다운스트림으로 포워딩하는 모든 `/api/v1/**` 프록시
  라우트 미발견; `CompositeController`의 2개 엔드포인트만 발견).
- **근본 원인:** 게이트웨이 라우트는 `@Bean` 메서드 안에서
  `RouteLocatorBuilder.routes().route(...).uri(...).build()` DSL로 선언된다. G1과 동일하게
  어노테이션 메서드가 아니므로 인덱서가 보지 못한다(구조적으로 G1과 같은 부류 —
  "메서드 호출 체인으로 선언된 라우트").
- **현재 회피:** 없음. (프록시 라우트는 다운스트림 위임이라 graph-rag의 단일-SUT
  분석 모델과도 결이 다름 — §5 P2 참고.)

### G3. Kafka 페이로드의 서버 생성(비결정) 필드 미스트립 — 심각도 Medium
- **증상:** 생성된 Kafka 검증 테스트(`JSONAssert`)가 캡처 시점의 `eventId`(UUID)·
  `occurredAt`(timestamp)를 **리터럴로 하드코딩**해, 재실행 시 SUT가 새로 생성한 값과
  불일치하여 실패.
- **영향 서비스:** diary (`DiaryPostTest.s201_1` 격리). Kafka를 발행하며 페이로드에
  per-request UUID/timestamp를 싣는 모든 서비스가 잠재 대상.
- **근본 원인(정정):** test-generator에 비결정 필드 처리 메커니즘은 **이미 존재**한다 —
  `Generator.deterministicPayload(...)`(Generator.java L463)가 Kafka emit payload 합성
  시(L190 `payloadJson`) 호출되어, `fixture.substitutions()`(입력 유래 값)와
  `fixture.nonDeterministicValues()`(주로 INSERT SQL의 DB 시퀀스 PK 리터럴)에 해당하는
  값을 제거한다(L473). **문제는 이 분류 집합이 좁다는 것**: Kafka 페이로드의
  per-request **서버 생성 UUID/timestamp(`eventId`·`occurredAt`)는 입력 유래도 DB PK도
  아니라** 두 집합 어디에도 들지 않아 스트리핑되지 않고 어설션에 리터럴로 굳는다. (UUID/
  ISO-8601 같은 패턴 기반 서버-생성 값 감지가 이 경로에 적용되지 않음.)
- **현재 회피:** 해당 테스트만 `quarantine/`로 격리하고 KNOWN-LIMITATIONS에 기록.

### G4. 탐색 상태의 재현 실패(stale-seed) — 심각도 Medium
- **증상:** 탐색 시점의 DB 상태에 의존하는 경로가, 재현 DB(빈 상태)에는 그 상태가 없어
  다른 상태코드를 반환(예: 500-vs-404).
- **영향 서비스:** mindgraph (`GraphByDiaryTest.s500_1` 격리).
- **근본 원인(실코드 재대조 확정):** 실 격리(`s500_1`)의 원인은 아래 **(b) 단일 모드**다.
  당초 (a)로 지목했던 "비-GET 시드 미재현"은 코드 대조 결과 **이미 처리된 경로**임이 확인됐다.
  - **(a) 비-GET by-id 시드 — 이미 구현됨(열린 버그 아님).** `EndpointExplorationRunner.java:169`
    주석 *"(Bug: 비-GET 시드 미재현)"*은 **그 줄이 해소하는** 버그를 라벨한 것이다 — 같은 줄의
    `seedResource = readPath || hasPathParam`(L170)이 비-GET by-id를 시드하고, `attachSeeds`의
    비-GET 분기(L595~640)가 path별 고유 PK로 시드를 복제해 `requiredSeedIds`에 부착한다. 즉
    엔드포인트-under-test 자신의 by-id 리소스는 빈 DB에서 재현된다. **진짜 미해결 gap은 다른
    것**: `insertSeeds`는 엔드포인트-under-test의 `happy.seeds()`만 시드하므로, **다른 엔드포인트
    탐색의 부수 효과(POST 등)로 만들어진 cross-endpoint 리소스 의존**은 연결하지 못한다. 그러나
    이는 L169 주석이 가리키는 모드가 아니며, **본 캠페인 8개 서비스에서 실제로 관측되지 않았다**
    (s500_1은 (b)다).
  - **(b) 탐색-시점 DB 오염 + GET 비-2xx 경로 시드 미부착 — s500_1의 실제 원인.** `s500_1`은
    **GET** `/internal/graphs/diary/{diaryId}`(`GraphController.byDiary` → `getGraphByDiaryId`,
    SELECT-only) 경로이며, 탐색 중 throwaway DB에 유효하지 않은 JSON 행이 시드돼 500이 캡처됐다.
    `attachSeeds`의 GET 분기(L578~593)는 시드를 **첫 2xx path에만** 부착하는데, 이 경로엔 2xx가
    없어 `requiredSeedIds=[]`로 남는다 → 재현(빈 DB) 시 그 오염 상태가 없어 결정적 404가 나온다.
    캡처 상태와 재현 상태의 불일치이며, (a)의 by-id 시드 메커니즘과는 무관하다.
- **현재 회피:** 해당 테스트 격리 + 기록.

### G5. 소규모 정합성 한계 (read-state / happy-auth / empty-path-var) — 심각도 Low~Medium
세 가지 소규모 한계를 묶는다(개별로는 영향이 국소적).
- **G5a. 상태 의존 read-path:** Kafka 소비 또는 선행 쓰기로만 생기는 상태를 읽는 GET은
  탐색이 결정적으로 시드하지 못해 빈/404 경로만 커버. (notification·mindgraph의 read
  엔드포인트 — 200 양성 경로 미도달.) G4와 인접하나 트리거가 Kafka/외부라는 점이 다름.
- **G5b. happy-auth 캡처 한계:** 인증이 `WebFilter`로 적용되는 경우, 어노테이션 인덱서가
  `authRequired`를 false로 판단(필터 기반 보호를 못 봄)해 탐색의 happy 프로브가 토큰을
  붙이지 않아 보존된 대표 경로가 401이 됨. (bff-gateway `CompositeController` — 생성 테스트가
  401 보안계약을 검증; 200 happy-path는 미생성. Bearer+verify 스텁으로 200 동작은 수동
  실증됨.)
- **G5c. empty-path-var(double-slash):** `GET /…//content`(빈 path 변수)에 대해 탐색 캡처
  404 vs RestAssured 재현 400의 HTTP 클라이언트 경로 인코딩 불일치. (diary
  `DiaryGetContentTest.s404_2` 격리.)
- **(비고) 메서드-레벨 `@RequestMapping`:** §2의 미지원 항목 — verb 매핑 어노테이션 없이
  `@RequestMapping(method=RequestMethod.X)`만 쓰는 핸들러도 현재 미인덱싱. 본 캠페인 8개
  서비스엔 해당 사례가 없었으나, G1/G2와 함께 "인덱서 라우트 인식 범위" 항목으로 둔다.

---

## 4. 사유 — 왜 이런 공백이 생겼나(근본 원인 종합)

- **인덱싱 모델의 가정:** `EndpointIndexer`는 "엔드포인트 = verb 매핑 어노테이션이 붙은
  컨트롤러 메서드"라는 단일 가정 위에 서 있다(§2). 이는 graph-rag가 검증해 온 샘플
  (`samples/order-service` 등)과 다수 SUT가 Spring MVC 어노테이션 스타일이었기 때문이며,
  실제로 그 스타일에는 견고하다. 함수형(G1)·선언형(G2) 라우팅은 **라우트가 메서드 호출
  체인으로 표현**되어 정적 분석 대상의 형태 자체가 다르다.
- **노이즈-회피 설계의 부작용:** noClasspath Spoon은 타입 해석이 불완전하므로, 인덱서는
  안전하게 "확실히 컨트롤러인 것"만 본다(meta-annotation 해석을 신뢰하지 않고
  `@RestController`/`@Controller`를 직접 검사 — EndpointIndexer.java L68~70 주석). 이
  보수성이 함수형/선언형 라우트를 자연히 배제한다.
- **합성 단계의 분류 집합이 좁음(G3):** 비결정 스트리핑 메커니즘은 있으나(`deterministicPayload`),
  대상이 입력 유래·DB PK로 한정되어 **서버 생성 UUID/timestamp 패턴**을 포괄하지 못한다.
- **재현 시드의 GET 비-2xx 편향·탐색 오염(G4):** 실 격리(s500_1)의 원인은 **GET 비-2xx 경로의
  시드 미부착 + 탐색-시점 DB 오염**이다 — `attachSeeds`가 GET 시드를 첫 2xx path에만 붙이므로
  (L578~593), 2xx가 없는 오염-500 경로는 `requiredSeedIds=[]`로 남아 재현 시 결정적 404가 된다.
  (당초 (a)로 지목한 비-GET by-id 시드 미재현은 L170+L595~640에서 이미 처리됨 — §3 G4 참조.)

요약: G1·G2는 **인덱서의 라우트 인식 범위 확장** 문제, G3는 **합성 시 서버-생성 필드 분류
확장** 문제, G4·G5a는 **탐색 상태의 재현(시드 replay·오염 회피)** 문제, G5b는 **필터 기반
인증 인식**, G5c는 **경로 인코딩 정규화** 문제로 분류된다.

---

## 5. 개선 제안

각 제안은 **무엇을 / 어디에 / 난이도·리스크**를 명시한다. 우선순위는 §7 표를 단일 기준으로
한다. 모든 제안은 graph-rag의 무-LLM·결정적 합성·무-fabrication 원칙을 유지한다.

### P1 — `RouterFunction` 인덱싱 추가 (G1 대응)
- **무엇을:** 함수형 라우팅 인식기를 추가한다. `@Bean`/임의 메서드가 `RouterFunction`을
  반환하고 그 본문이 `RouterFunctions.route()` 체인일 때, 체인의
  `.GET/.POST/.PUT/.DELETE/.PATCH(path, handler)` 호출(`CtInvocation`)을 정적으로 순회해
  (HTTP 메서드, path, handler 참조)를 `Endpoint`로 매핑한다. handler가
  `HandlerFunction`/메서드 참조면 그 메서드 body에서 `ServerRequest.bodyToMono(T.class)` /
  `pathVariable("…")` 호출을 역추적해 body-shape·path-var를 best-effort 추출한다.
- **어디에:** **기존 선례와 동일 패턴** — `WsEndpointIndexer`/`KafkaListenerIndexer`처럼
  **별도 `RouterFunctionIndexer`를 신설해 `BuilderCli`(L162~170 인근)에 배선**한다. 산출
  `Endpoint`는 기존 모델을 재사용하고 **`IndexResult.endpoints()`에 concat**해 기존 explore 루프·
  `EndpointSelector`가 그대로 재사용한다(endpoint id 네이밍 호환 유지 — Cursor 리뷰).
- **난이도/리스크:** 중. Spoon noClasspath에서 메서드-참조 handler의 시그니처 해석이
  불완전할 수 있음 → 그 경우 path·method까지만 인덱싱하고 입력 합성은 best-effort로 두되,
  **발견 자체는 보장**(폴백 시 KNOWN-LIMITATIONS에 부분 추출 명시).
- **검증:** §6 E2E-1(counseling 재생성).

### P2 — 선언형 Gateway 라우트 인덱싱 (G2 대응)
- **무엇을:** `RouteLocatorBuilder.routes().route(...).uri(target)...build()` DSL을 정적
  순회해 (매칭 predicate의 path, 대상 uri)를 추출한다. **경로 변환 필터(`StripPrefix`,
  `RewritePath`, `SetPath` 등)를 함께 파싱**해야 인덱싱 path와 실제 다운스트림 도달 path가
  어긋나 404가 나는 것을 막는다 — 미지원 필터 감지 시 해당 라우트는 제외하거나 경고
  로그를 남긴다. 게이트웨이 라우트는 **다운스트림 프록시**이므로 두 모드를 둔다:
  (a) **얕은 모드**(권장 1차): 라우트 존재·매칭 path·포워딩 대상만 인덱싱해 "프록시 계약"
  스모크 테스트(상태코드/헤더 전파) 생성; (b) **깊은 모드**: 다운스트림을 `--external-stubs`로
  스텁해 end-to-end 검증(현재도 stub 배선 존재).
- **어디에:** 별도 `GatewayRouteIndexer` 신설 + `BuilderCli` 배선(P1과 동일 패턴). 산출 라우트도
  `IndexResult.endpoints()`에 merge해 기존 explore·selector 재사용.
- **난이도/리스크:** 중~상. 게이트웨이 의미론(필터·rewrite·predicate 조합)을 정적으로
  완전 복원하기 어렵다 → 얕은 모드 + 지원 필터 화이트리스트로 범위를 의도적으로 제한하고
  미지원 부분을 KNOWN-LIMITATIONS에 명시.
- **검증:** E2E-2(bff-gateway 재생성에서 프록시 라우트가 1개 이상 발견).

### P3 — 서버 생성 필드 스트리핑을 Kafka payload로 확장 (G3 대응)
- **기존 자산(출발점, 리뷰 반영):** HTTP 응답 경로에는 이미 서버-생성 감지기
  `FixtureComposer.looksServerGenerated()`(UUID_RE/TIMESTAMP_RE — FixtureComposer.java L274~281,
  L232에서 concrete 단언 판정에 사용)가 있다. G3는 **Kafka payload 경로(`deterministicPayload`)만
  이 parity에서 빠져 있다**는 문제다 → 본 제안의 1차 목표는 **HTTP↔Kafka parity**(`looksServerGenerated`
  재사용)다.
- **무엇을:** **입력 유래·상관(correlation) 식별자는 계속 구체값으로 단언**해야 하므로, 무엇을
  비결정으로 뺄지는 보수적으로 정한다.
  - **불변(공통):** `fixture.substitutions()`에 든 값(=입력 유래)은 패턴이 UUID/timestamp처럼 보여도
    **절대 비결정으로 빼지 않는다**(입력→출력 상관 단언 보존). 광범위한 `*Id` 스트리핑 **금지** —
    `tenantId`/`categoryId` 같은 입력 유래·라우팅 상관 ID를 `notNullValue()`로 바꾸면 오라클이 약화돼
    잘못된 ID emit 회귀를 가린다.
  - **(1차) 휴리스틱 — `looksServerGenerated` 재사용.** `deterministicPayload`에서 필드 값이
    `looksServerGenerated`(UUID/ISO-8601)이고 substitutions에 없으면 비결정으로 본다. 단, **제거가
    아니라 형식/패턴 단언으로 대체**(아래 템플릿 변경 필요)해 "형태는 검증"한다. (`looksServerGenerated`는
    현재 `FixtureComposer` 내 `private static`이므로 공유 유틸로 추출하거나 package-accessible로
    승격해 `Generator`에서 재사용 — 정규식 중복 금지.)
  - **(보강) 캡처-2회 diff.** "실제로 값이 바뀌는 필드"만 비결정으로 지목해 패턴 휴리스틱의
    거짓양성을 보완. **쓰기 경로 부작용 처리(정정 — Gemini 리뷰):** SUT는 별도 프로세스에서 자기
    트랜잭션을 커밋하므로 **러너의 JDBC 롤백으로는 SUT가 쓴 행을 되돌릴 수 없다.** 대신 1차 발행이
    만든 행을 **캡처된 INSERT의 역(DELETE)으로 정리한 뒤**(기존 `deleteSeeds`/`Seeds.delete` 패턴 —
    EndpointExplorationRunner L484~487) 2차 발행한다. 역연산 불가 부작용(외부 호출 등)은 diff를 빼고
    (1차) 휴리스틱만 쓴다.
- **어디에 (정정 — 리뷰 반영, 템플릿 누락 보완):**
  1. `test-generator/.../Generator.java` `deterministicPayload`: 비결정 분류 + (제거가 아니라)
     스트리핑된 필드별 **패턴-단언 슬롯** 산출.
  2. **`test-generator/.../templates/test-class.mustache`의 `kafkaEmits` 블록(L45~53)** + `kafkaEmits`
     모델(`buildScenarioMethod`): 현재는 `JSONAssert.assertEquals("{{{payloadJson}}}", record.value(),
     false)` 단일 리터럴 비교만 한다(주의 — `kafka-test-class.mustache`는 @KafkaListener consumer 전용이라
     **REST-path emit과 무관**; Cursor 리뷰 정정) → 서버-생성 필드는 JSONAssert `Customization`(per-field
     regex matcher) 또는 별도 필드별 단언 슬롯(예: `serverGeneratedAssertions`)으로 형식 검증하도록
     모델·템플릿 확장.
  3. (diff 채택 시) `graph-rag-builder` 캡처 단계: dual-invoke 지점 + traceId pairing으로 diff 산출,
     결과를 `ComposedFixture.nonDeterministicValues`(또는 `CapturedEventEmit`)에 기록.
- **난이도/리스크:** 중. 템플릿/모델 확장 + (diff 채택 시) 캡처-정리 경계 제어 필요. 거짓 스트리핑
  위험은 substitutions 보호 + `looksServerGenerated` 한정으로 차단.
- **검증:** E2E-3(§6 — 컴파일 통과 + Kafka 단언에 서버-생성 리터럴 부재 + 입력 유래 필드는 구체값
  단언 유지 + 서버-생성 필드는 패턴 matcher로 단언).

### P4 — 탐색 상태의 결정적 재현 (G4 대응)
- **지배 불변식(C6):** 생성 테스트의 expected status는 *빈 DB + 그 테스트가 선언한 requiredSeeds*
  만으로 결정적으로 재현 가능해야 한다. 탐색이 어떤 비-2xx 경로를 이 불변식대로 재현 가능하게 만들
  수 없으면 **그 경로의 테스트를 emit하지 않는다**(허위 500 박제 금지). 즉 목표는 "500 재현"도
  "404 강제"도 아니라 "**재현 가능한 상태만 테스트로 승격**"이다.
- **무엇을:** 위 불변식을 만족시키는 **주(主) 작업은 (b)**다 — 탐색-시점 DB 오염(유효하지 않은
  시드 등)이 GET 비-2xx 대표 경로의 캡처 상태를 좌우하지 않도록, ① 오염 상태를 만들지 않는
  결정적 입력으로 합성하거나(권장), ② 재현 가능한 시드 의존을 `requiredSeedIds`에 명시하거나,
  ③ ①·②가 불가능하면 그 경로의 테스트를 생성하지 않는다(현재 `attachSeeds` GET 분기는 2xx 없는
  경로에 시드를 붙이지 않는다 — L578~593). **부차 작업 (a)**: 엔드포인트-under-test 자신의 by-id 시드는 이미
  재현되므로(L170+`attachSeeds` L595~640), 남은 건 **cross-endpoint 부수 효과 리소스 의존**의
  연결인데 — 본 캠페인에서 미관측이고 의존 순서 추적이 필요해 비용이 크다. 별도 후속 항목으로 둔다.
- **억제 레이어(정정 — Cursor 리뷰):** C6 불변식의 "테스트 미생성"은 명시적 게이트가 필요하다 —
  현재 `Generator.generate`는 `negative-auth`/`negative-validation`만 skip(L77~79)하고 나머지
  `ExploredPath`는 전부 emit한다. 억제는 **builder에서**(재현 검증 실패한 비-2xx·`requiredSeedIds=[]`
  경로를 `ExploredPath`로 기록하지 않음) 수행하는 것을 1차로 한다(generator filter는 대안).
- **버그 vs 오염 구분·가시성(정정 — Gemini 리뷰):** 판정 기준은 "버그냐"가 아니라 **"재현 가능하냐"**다 —
  빈 DB + 선언 시드로 재현되는 500은 SUT 진짜 버그라도 emit한다. 재현 불가로 **드롭한 경로는 반드시
  로그로 표면화**(억제 카운트 + path)해 진짜 버그가 조용히 사라지지 않게 한다(KNOWN-LIMITATIONS 기록).
- **어디에:** `graph-rag-builder/.../run/EndpointExplorationRunner.java`(탐색 입력 합성·시드 부착·
  비재현 경로 억제, 특히 `attachSeeds` GET 분기) + 재현 하니스 배선 + 드롭 로그.
- **난이도/리스크:** 중상. (b)의 "오염 상태 비-생성"은 탐색 입력의 결정성 보장이 필요하고, (a)의
  cross-endpoint는 의존 순서(쓰기→읽기) 추적이 필요하다. **당초의 "기존 필드만 채우면 되는 값싼
  작업" 평가는 철회**한다(그 모드는 이미 구현돼 있었음 — §3 G4-(a)).
- **검증:** E2E-4(mindgraph 재생성에서 `GraphByDiaryTest.s500_1`의 캡처 상태가 재현 시
  결정적으로 일치 → 격리 해소).

### P5 — 소규모 정합성 개선 (G5 + 메서드-레벨 @RequestMapping)
- **G5b(happy-auth):** 인덱서가 `WebFilter`/`SecurityWebFilterChain` 기반 인증을 인지하도록
  보강하거나, 탐색의 happy 프로브가 `--auth-*` 토큰을 보호 추정 경로에 기본 부착하도록
  옵션화.
- **G5a(상태 의존 read-path):** P4의 시드 replay를 Kafka 트리거 상태로 확장(소비 이벤트를
  재현 전 발행). 난이도 상 — 별도 항목으로 분리 권장.
- **G5c(empty-path-var):** 빈 path 변수/double-slash의 캡처-재현 경로 인코딩을 일치시킨다.
- **메서드-레벨 `@RequestMapping`:** `MAPPING_TO_METHOD` 순회에 `@RequestMapping(method=…)`
  처리를 추가(소규모, EndpointIndexer 국소 수정).

---

## 6. 영향 및 검증(E2E/수용 기준)

본 제안의 "완료"는 단위 변경이 아니라 재생성 회귀 검증되어야 한다. **수용 게이트는 두 층이며,
CI-강제 가능한 내부 fixture가 1차(primary)다(C4).**
- **1차(primary) — graph-rag repo 내부 회귀 fixture(CI 강제).** 각 제안마다 graph-rag repo 안에
  최소 SUT fixture를 추가한다: P1=함수형 라우팅(`RouterFunctions`) 미니 컨트롤러, P2=`RouteLocatorBuilder`
  게이트웨이 라우트, P3=per-request UUID/timestamp를 emit하는 Kafka 발행 엔드포인트, P4=오염-가능
  GET 경로. 기존 `e2e/`(order-service) 패턴을 따라 `./gradlew check` + `e2e/run-e2e.sh`에서 돌려,
  **외부 레포 없이 graph-rag 개발자·CI만으로 재현**한다. fixture는 `e2e/` 하위(또는 builder
  integration test)에 두고, `e2e/run-e2e.sh`에 인덱서-fixture 스텝을 추가하거나 별도
  `run-indexer-fixtures-e2e.sh`로 wiring하며 `.github/workflows/ci.yml`에 묶는다(Cursor 리뷰).
  이것이 PR 머지의 하한 게이트다.
- **2차(confirmatory) — 외부 tainted-spring 8개 레포**(`github.com/baekchangjoon/tainted-spring-*`)
  재생성. 각 레포 `graphrag-blackbox/README.md` 절차(tool1/tool2/`:e2e`)로 실 MSA에서 확인한다.
  CI에 묶지 않고 릴리스 전·주기 확인용으로 둔다(라이브 SUT 그린 실증).

아래 E2E-1~4는 **내부 fixture와 외부 레포 양쪽에서 동일 기준**으로 본다(현재값은 외부 캠페인 실측):

- **E2E-1 (P1, counseling):** 재생성 시 함수형 라우트의 2개 REST 엔드포인트
  (`POST /internal/counseling/sessions`, `.../{id}/messages`)가 **발견되고** 생성 테스트가
  라이브 SUT에 green. (현재: REST 0개.)
- **E2E-2 (P2, bff-gateway):** 재생성 시 게이트웨이 프록시 라우트가 **1개 이상 발견**되고
  얕은 모드 스모크 테스트가 green. (현재: 프록시 0개.)
- **E2E-3 (P3, diary):** 재생성 시 `DiaryPostTest`의 Kafka 검증이 **격리 없이** 재실행
  통과(서버-생성 필드가 형식 검사로 합성). (현재: 1건 격리.)
- **E2E-4 (P4, mindgraph):** 재생성 시 `GraphByDiaryTest.s500_1`의 캡처 상태가 재현 시
  **결정적으로 일치**(탐색이 오염 상태를 만들지 않아 결정적 404로 합성, 또는 비-2xx 경로에
  재현 가능한 시드 의존을 부착) → 격리 해소. (현재: 1건 격리. 주의: 이 엔드포인트엔 200 양성
  경로가 없어 `attachSeeds`가 시드를 붙이지 않으므로, "200 통과"가 아니라 "캡처-재현 상태
  일치"가 기준이다.)
- **E2E-5 (P5/G5c, diary):** 재생성 시 빈 path 변수(double-slash) 경로의 캡처 status와
  RestAssured 재현 status가 **일치**(현재: 캡처 404 vs 재현 400 불일치). 내부 fixture로 CI 강제.
  (Sonnet 리뷰 — G5c는 E2E-3과 별개 동작이라 전용 기준을 둔다.)
- **E2E-회귀 (전 항목):** 8개 서비스 전체 재생성 결과가 회귀하지 않을 것 —
  analytics·community·notification·auth-user는 **격리 0 유지**, diary·mindgraph는 해당
  E2E 완료 후 격리 해소, counseling·bff-gateway는 P1/P2로 발견 수 증가(회귀 없음). 더불어
  graph-rag 자체 `./gradlew check` + `e2e/run-e2e.sh`(샘플 order-service 53 테스트) green
  유지. 이것이 변경의 하한 게이트다.
- **CI 게이트 예외(명시 — Sonnet 리뷰):** G5b(happy-auth/`WebFilter` 감지)는 구조 복잡성으로 내부
  fixture 대신 **수동 실증으로 대체하며, §6 1차 CI 하한 게이트 대상에서 명시적으로 제외**한다(부록 B
  반영). 그 외 P1~P4·G5a/G5c는 내부 fixture로 CI 강제한다.

**내부 fixture 판정 기준(명시적 pass/fail, CI 강제):** E2E-1: 함수형 fixture graph.json endpoint
수 ≥ 2; E2E-2: 게이트웨이 fixture 프록시 라우트 수 ≥ 1; E2E-3: 생성 테스트가 `javax.tools.JavaCompiler`
컴파일 통과 + Kafka 단언에 서버-생성 리터럴 부재 + 입력 유래 필드는 구체값 단언 유지; E2E-4: 생성
테스트의 expected status가 빈 DB + 선언된 requiredSeeds만으로 결정적으로 재현(오염 의존 0), 또는 그
경로가 재현 불가로 판정돼 테스트가 생성되지 않음. 실 라이브 SUT 그린은 2차(외부 레포)에서 확인한다.

각 제안은 graph-rag의 단위 TDD(red→green)로 구현하고, 위 E2E를 외부 루프(수용 테스트)로
둔다.

---

## 7. 권고 우선순위 및 로드맵 편입 (단일 기준)

> 본 표가 우선순위의 단일 authoritative 기준이다(§5 본문은 순위를 명시하지 않는다).

| 제안 | 대응 한계 | 심각도 | 난이도 | 권고 순위 | 근거 |
|---|---|---|---|---|---|
| **P1** | G1 함수형 라우팅 | High | 중 | **1** | 서비스 1개의 REST 표면 0→전부 회복; 기존 indexer 패턴 재사용 |
| **P3** | G3 Kafka 서버-생성 필드 | Medium | 중 | **2** | per-request UUID/ts를 emit하는 모든 서비스 잠재 영향(breadth); 기존 `deterministicPayload` 확장 |
| **P2** | G2 게이트웨이 라우트 | High | 중상 | **3** | 가치 크나 프록시 의미론·필터 복원이 어려움(얕은 모드 한정) |
| **P4** | G4 stale-seed | Medium | 중상 | **4** | 실 격리(s500_1)는 (b) 탐색-오염 모드 — 결정적 합성 필요(값싼 필드-채움 아님); 당초 1순위 근거는 실코드 대조로 철회(§3 G4) |
| **P5** | G5 + @RequestMapping | Low~Med | 가변 | **5** | 점진 개선, 국소 |

- **재배치 근거(2차 정정):** 순위는 구현 비용이 아니라 **사용자 가치(심각도 × 영향 폭)를 1차
  기준**으로 매긴다. P4를 당초 1순위로 둔 "이미 Bug 명시·값싼 수정" 논거는 실코드 대조 결과
  무효이며(해당 비-GET by-id 모드는 L170+`attachSeeds` L595~640에서 이미 구현), 실 격리 원인 (b)는
  결정적 합성이 필요한 중상 난이도라 **4순위**로 내린다. High·전(全)표면 회복인 **P1을 1순위**로
  올린다. (이 정정은 §3 G4·§5 P4와 일관.)
- graph-rag `docs/09-implementation-roadmap.md`에는 현재 G1·G2(함수형/선언형 라우팅)·
  G3·G4가 **로드맵 항목으로 부재**하다 → 본 5개 제안을 신규 백로그 항목으로 편입 권고.
- 본 문서·제안 역시 graph-rag의 자체 개발 규율(설계 리뷰 → 요구사항명세 → plan → 단위
  TDD → `:e2e` 회귀)을 따라 착수할 것을 권한다.

---

## 부록 A — 근거 소스 위치(인용, 실코드 대조 완료)
- `graph-rag-builder/.../index/EndpointIndexer.java`
  - 어노테이션 상수: L29~37(`REQUEST_MAPPING` L31은 클래스 basePath 전용) / verb 매핑 맵
    (5개): L40~47 / Spoon 설정 `noClasspath`(L57)·`complianceLevel(17)`(L59): L57~59 / 타입·컨트롤러
    게이트: L67~73 / 보수적 어노테이션 해석 주석: L68~70, L188~196 / 메서드 매핑 순회:
    L77~90 / 클래스 basePath 추출: L74
- `graph-rag-builder/.../cli/BuilderCli.java:162~170` — `EndpointIndexer`·`WsEndpointIndexer`·
  `KafkaListenerIndexer`·`MapperXmlIndexer`·`ResponseDtoIndexer`(L170) 배선(별도 indexer 패턴 선례
  — P1/P2 참조)
- `graph-rag-builder/.../run/EndpointExplorationRunner.java` — `seedResource = readPath ||
  hasPathParam`(L170) + `attachSeeds` 비-GET by-id 분기(L595~640): 비-GET by-id 시드를 path별
  고유 PK로 복제·부착 → L169 주석이 라벨한 "비-GET 시드 미재현"은 **이 경로가 해소**(열린 버그
  아님) / `attachSeeds` GET 분기(L578~593): 시드를 **첫 2xx path에만** 부착 → 2xx 없는 GET
  오염-500 경로는 `requiredSeedIds=[]` (G4-(b) 근거)
- `shared-model/.../model/ExploredPath.java:20,30,38` — `requiredSeedIds` 필드(G4 모델 근거)
- `test-generator/.../generator/Generator.java` — `deterministicPayload`(L463; Kafka payload
  적용 L190), 비결정 값 분류(`fixture.substitutions()`/`nonDeterministicValues()` L453,473)
  (G3 근거 — 메커니즘 존재하나 분류 집합이 좁음)
- `test-generator/.../generator/compose/ComposedFixture.java:19` — `nonDeterministicValues`
- 미발견 라우팅 indexer 부재 확인: `find graph-rag-builder/src/main -name "*Indexer*"` →
  `EndpointIndexer/KafkaListenerIndexer/ResponseDtoIndexer/WsEndpointIndexer/MapperXmlIndexer`
  (RouterFunction/Gateway 인덱서 없음)
- `docs/22-static-discovery-limits.md:166` — `--manual-paths`(BuilderCli.mergeManualPaths)
  escape hatch (G1 회피 논의 근거)
- 서비스별 한계 상세: 각 레포 `graphrag-blackbox/KNOWN-LIMITATIONS.md`
  (diary·notification·mindgraph·counseling·bff-gateway)

## 부록 B — 한계 ↔ 영향 서비스 ↔ 제안 ↔ 검증 추적표
| 한계 | 영향 서비스 | 제안 | E2E |
|---|---|---|---|
| G1 함수형 라우팅 미발견 | counseling | P1 | E2E-1 |
| G2 게이트웨이 라우트 미발견 | bff-gateway | P2 | E2E-2 |
| G3 Kafka 서버-생성 필드 | diary | P3 | E2E-3 |
| G4 탐색 상태 재현 실패 | mindgraph | P4 | E2E-4 |
| G5a 상태 의존 read-path | notification·mindgraph | P5(+P4 확장) | graph.json 비교 |
| G5b happy-auth 캡처 | bff-gateway | P5 | 수동 실증 (CI 게이트 제외 — §6) |
| G5c empty-path-var | diary | P5 | E2E-5 |
| 메서드-레벨 @RequestMapping | (캠페인 내 사례 없음) | P5 | 컴파일/단위 |

---

## 부록 C — 3-벤더 design-doc 리뷰 반영 내역
본 개정본은 Claude Sonnet·Gemini·Cursor 3개 리뷰어의 findings를 실코드 대조 후 반영했다.
- **수용(정정):** G3 "메커니즘 부재" → "메커니즘 존재하나 Kafka 서버-생성 필드 미포함"
  (Sonnet·Cursor 합치); §2 메서드-레벨 `@RequestMapping` 오기 정정(Sonnet·Cursor); G4를
  (a) 비-GET 시드 버그 + (b) 탐색 오염·비-2xx 시드 미부착으로 분리, E2E-4를 "200 통과" →
  "캡처-재현 상태 일치"로 정정(Cursor); §1.3 "6개 깨끗이 통과" 과장 정정(Cursor); §5↔§7
  우선순위 이중 표기 제거(Cursor); E2E-회귀 8서비스 명시(Cursor); P1/P2에 기존
  `Ws/KafkaListenerIndexer` 패턴 참조 추가(Cursor); P1 handler body-type 역추적 폴백
  (Gemini); P2 경로 변환 필터 파싱 범위 명시(Gemini); P3 2회-diff의 쓰기 경로 부작용
  리스크·GET 한정(Sonnet·Gemini); E2E fallback에 pass/fail 임계·Java 컴파일 검증 추가
  (Sonnet·Gemini); G1 `--manual-paths`를 docs/22와 정렬(Cursor).
- **부분 수용:** P5 read-path Kafka 확장은 난이도 상으로 분리 유지(Gemini/Cursor의 즉시
  통합 제안 대비 점진 접근 선택).

### C-2차. 실코드 재대조 정정 (2026-06-20, mindgraph SUT + `attachSeeds` 코드 추적)
- **C1 — §7 우선순위 가치-우선 재배치:** 순위 기준을 "구현 비용"에서 "심각도 × 영향 폭"으로
  전환. P4 1→4, P1 2→1, P3 2, P2 3. 근거: P4의 당초 1순위 논거("이미 Bug 명시·값싼 수정")가
  C2로 무효화됨.
- **C2 — G4-(a) "비-GET 시드 미재현" 정정:** `EndpointExplorationRunner` L170(`seedResource =
  readPath || hasPathParam`)과 `attachSeeds` 비-GET 분기(L595~640)를 대조한 결과, 해당 모드는
  **이미 구현된 경로**이고 L169 주석은 그 줄이 *해소하는* 버그를 라벨한 것임을 확인. 실 격리
  `s500_1`은 `GraphController.byDiary`(GET·SELECT-only)로, `attachSeeds` GET 분기(L578~593)가
  2xx 없는 경로에 시드를 안 붙여 `requiredSeedIds=[]`가 되는 **(b) 단일 원인**으로 재확정.
  cross-endpoint 부수 효과 의존은 실재하나 L169가 가리키는 모드가 아니고 캠페인 미관측 →
  고비용 후속으로 분리. P4 난이도 중→중상, "값싼 작업" 평가 철회.
- **C3~C6 정정(3차):** C3 — P3를 "diff를 throwaway/롤백으로 쓰기 경로까지 적용(우선) + 휴리스틱
  화이트리스트 축소 + `substitutions` 보호"로 재작성(거짓 스트리핑·오라클 약화 차단). C4 — §6 수용
  게이트를 "내부 fixture primary(CI 강제) + 외부 레포 confirmatory"로 뒤집고 판정 임계 명시. C5 —
  §1.3에 표본 1개·조건부 심각도 한계 명시. C6 — G4-(b)의 "500 재현 vs 404 강제" 모호성을 "재현
  가능한 상태만 테스트로 승격" 지배 불변식으로 해소(P4·E2E-4 반영).

### C-4차. 3-벤더 재리뷰 반영 (2026-06-20, Sonnet·Gemini·Cursor — C1~C6 개정본 대상)
- **P3:** (Gemini, critical) SUT가 별도 프로세스에서 커밋 → 러너 JDBC 롤백 불가 → **캡처 INSERT의
  역(DELETE) 정리**로 정정; (Cursor) 기존 `FixtureComposer.looksServerGenerated` 재사용(HTTP↔Kafka
  parity); (Sonnet·Cursor) 패턴-단언은 `kafka-test-class.mustache` + `kafkaEmits` 모델 확장이 필요 →
  "어디에"에 템플릿/모델 추가.
- **P4:** (Cursor) 억제 게이트를 builder `ExploredPath` 미기록으로 명시; (Gemini) 판정 기준은
  재현가능성이며 드롭 경로는 로그로 표면화(진짜 버그 은폐 방지).
- **P1/P2:** (Cursor) 산출 `Endpoint`를 `IndexResult.endpoints()`에 merge해 explore·selector 재사용 명시.
- **§6/부록 B:** (Sonnet) G5b를 CI 게이트에서 명시 제외; (Sonnet) G5c에 전용 **E2E-5** 신설; (Cursor)
  내부 fixture 위치·`run-e2e.sh`/CI wiring 명시.
- **부록 A:** (Sonnet·Cursor) `complianceLevel` L59 / `BuilderCli` L162~170(+`ResponseDtoIndexer`) 정정.
- **반려(이미 반영됨):** Gemini I3(P1 메서드-참조 해석 폴백)·I4(P2 필터 화이트리스트)는 각 제안의
  난이도/리스크에 이미 기술 — 중복이라 미추가.

# 외부 stub 응답 body 충실도 (REQ-012, egress 4순위) — 설계

- 작성일: 2026-06-24
- 브랜치: `feat-egress-stub-body-fidelity` (base = origin/main `6ca8cdd5`)
- 선행: PR #95(트레이싱 기반 egress 발견, REQ-001~011), PR #101(REQ-015 status-무관 stub register)
- 후속 정의 출처: `docs/superpowers/requirements/2026-06-24-egress-span-capture-requirements.md`의 REQ-012(🔵 Won't)

---

## 1. 문제

`io.graphrag.builder.run.EndpointExplorationRunner`는 외부 HTTP 호출을 두 경로로 stub 한다.

1. **redirect 경로** (`httpExchanges`): SUT의 외부 base URL이 임베디드 recorder
   (`io.graphrag.builder.env.HttpCaptureServer`)로 향하면, recorder가 응답을 serve 하고
   `drainNewExchanges()`가 그 `responseBody`를 캡처한다.
2. **span 경로** (`egressCalls`): recorder를 거치지 않고 OTEL/Brave CLIENT span으로만 발견된
   호출. `captureHttpCalls`의 egress 루프가 `EgressStubComposer.compose`로 **단일 형상-시드
   body**(`ShapeJsonSynthesizer`)를 합성해 `CapturedHttpCall(SYNTHESIZED)`로 기록한다.

### 1.1 기존 변형 fuzzing의 한계 (코드 확인)

`runResponseVariantLoops`는 `stubSynthesizer.isRegistered(method, path)`인 site에서만 돈다
(`EndpointExplorationRunner` L1936). 그 안에서 `exploreResponseVariants`는 소비 코드 기대값
(`ResponseStringLiteralExtractor` equals-family 리터럴 + enum 상수)으로 **값-충실 변형 body**를
만들어 SUT를 재invoke하고 **누적 커버리지(cumulativeCoverage)에 새 arm을 OR-병합**한다(L1959~).
그러나:

- 각 변형은 `CapturedHttpCall(SYNTHESIZED, variantBody)`로 기록되되, 그 변형 path는
  **"생성 제외 마커"**(L1972, `"response-variant"`)로 **생성 테스트에서 제외**된다 — 값-충실 변형 body가
  단언하는 테스트로 환류되지 않는다(커버리지 집계 전용).
- **span-only 발견 호출**은 recorder를 안 거쳐 `isRegistered == false` → 변형 루프 자체에서 제외 →
  `ShapeJsonSynthesizer`의 최솟값 규칙(enum→선언순 첫 상수, String→`"sample-<field>"`)으로 만든
  **임의 placeholder body** 하나만 갖는다.

귀결: 외부 응답 값으로 갈리는 SUT 분기(예: order-service의 `"EMBARGOED".equals(stock.region())`→422,
`switch(stock.mode())` BACKORDER→409)가 **생성 테스트에 단언으로 반영되지 않고**, span-only 호출의
stub은 소비 코드가 기대하는 값이 아닌 placeholder를 반환한다.

### 1.2 정직한 두 제약 (사용자 승인 완료)

- **(a) 실측 CAPTURED 티어는 보통 이 하니스에서 합성 품질이다.** recorder는 실제 외부가 도달 가능할
  때만 proxy/record로 "실측" body를 본다. 테스트 생성 맥락에는 보통 실제 외부가 없어 recorder가 합성
  stub을 serve 하므로 redirect 경로의 body도 합성 품질이다. 실측 body는 운영자 사전 녹화/실 외부 도달
  가능 시에만 real이며 **본 작업 통제 밖**. 본 작업이 올릴 수 있는 레버는 **기대값 기반 합성**이다.
- **(b) 단언(assertion)은 SUT 응답 관측을 요구하고, 관측은 구동(re-invoke)을, 구동은 인터셉트
  지점(recorder = redirect-capable)을 요구한다.** 따라서 **순수 span-only(recorder 미경유) 호출은
  구동·관측 불가 → 분기 단언 불가**. 그런 호출엔 생성 테스트 stub의 **body만 기대값-충실**하게 하고,
  미구동 분기는 침묵 저하가 아니라 loud로 노출한다.

---

## 2. 목표와 충실도 사다리

발견된 외부 호출 stub의 응답 body 충실도를 형상-시드에서 **소비 코드 기대값/에러 계약 기반**으로
끌어올리고(REQ-012 핵심), redirect-capable 호출에 대해서는 그 값-충실 변형이 **단언하는 생성
테스트**로 환류되게 한다. 충실도 우선순위:

```
실측 캡처 (CAPTURED, 기존 redirect+real-external 경로 — 본 작업 미수정)
  > 기대값/계약 합성 (CONTRACT, 신규)
  > 형상-시드 (SYNTHESIZED, 기존 fallback 유지)
```

신규 **CONTRACT** 티어 = 소비 코드가 응답에서 기대하는 값을 시드한 body. 값 출처:

1. **String 응답 필드** → `io.graphrag.builder.index.ResponseStringLiteralExtractor`가 추출한 소비
   코드의 equals-family 비교 리터럴(`stringLiteralsByDto`, 이미 인덱싱). 예: `region`→`"EMBARGOED"`.
2. **enum 응답 필드** → `enumConstants` 상수 목록. (주의: happy body의 enum 값은 `ShapeJsonSynthesizer`와
   동일하게 선언순 첫 상수이므로 happy만으로는 SYNTHESIZED와 동일 — enum의 충실도 기여는 **변형**
   (비-첫 상수, 예 `BACKORDER`)에서 나온다.)
3. **에러 envelope** → `ClassifierConfig`의 `semanticStatusField`/`errorDetailField`/`errorDetailContains`
   (graph `GraphAsset` 동일 디스크립터)로 에러 envelope body 합성. SUT가 외부 응답에서 envelope을
   검사하는 경우에만 적용(order-service처럼 값-구동 분기인 SUT엔 미적용 — 정상 부재).

**CONTRACT가 SYNTHESIZED보다 충실한 지점**은 (1) String equals-family 리터럴, (3) 에러 envelope이며,
(2) enum은 변형 값에서 기여한다. 위 세 출처가 모두 비면 형상-시드(SYNTHESIZED)로 정직하게 폴백한다.

> 명칭 주의: graph 자산 필드명은 `semanticStatusField`(과거 "errorContractStatusField"로 칭하던 것).
> test-generator `GraphRagClient.errorContractStatusField()`는 `GraphAsset.semanticStatusField()`의
> 별칭 접근자다. builder 측(`EgressStubComposer`)은 `ClassifierConfig`에서 디스크립터를 받는다.

---

## 3. 재사용 (대부분 기존 자산)

| 자산 | 역할 | 위치 |
|---|---|---|
| `ResponseStringLiteralExtractor` | 소비 코드 equals-family 응답 리터럴 추출(`stringLiteralsByDto`) | `builder.index` |
| `ResponseFieldVariantGenerator` | 후보 값 맵 → 결정적 변형 plan | `builder.run` |
| `exploreResponseVariants` / `ExternalStubSynthesizer.registerVariant` | trace-id 격리 변형 stub 구동(redirect) | `builder.run` |
| `EgressStubComposer` | span 경로 body 합성(현재 shape-seed only) | `builder.run` |
| `ShapeJsonSynthesizer` | 형상→minimal JSON, enum/리터럴 값 규칙 | `builder.run` |
| `HttpMockComposer#stubBody` | `CapturedHttpCall` → 생성 테스트 stub 코드 방출 | `test-generator.compose` |
| `CapturedHttpCall` (`responseBody`/`responseStatus`/`responseProvenance`/`consumedFields`) | 환류 자료구조 | `shared-model` |
| `ClassifierConfig` / `GraphAsset` (`semanticStatusField`/`errorDetailField`/`errorDetailContains`) | 에러 envelope 디스크립터 | `builder.oracle` / `shared-model` |

---

## 4. 변경 (net-new)

### 4.1 `EgressStubComposer` — 기대값 기반 body 합성 (span 경로)
- 현재 시그니처: `static Outcome compose(EgressCall, List<ExternalCallSite>, ShapeJsonSynthesizer)`.
- 변경 시그니처(목표): `static Outcome compose(EgressCall e, List<ExternalCallSite> callSites,
  ShapeJsonSynthesizer shapes, Map<String, Map<String, List<String>>> stringLiteralsByDto,
  ErrorContractDescriptor errorContract)`. (`ErrorContractDescriptor` = `ClassifierConfig`에서 뽑은
  `{semanticStatusField, errorDetailField, errorDetailContains}` 불변 값 객체; null이면 envelope 생략.)
- 동작: 매칭된 `ExternalCallSite.responseShape`의 필드에 대해
  - String 필드 → `stringLiteralsByDto[dtoFqn][field]`의 리터럴이 있으면 그 값(첫 리터럴, 결정적)을,
  - enum 필드 → `ShapeJsonSynthesizer` 규칙(첫 상수),
  - 그 외 → `ShapeJsonSynthesizer` 형상-시드 값
  을 채운 happy body를 합성한다. 리터럴 출처가 하나라도 적용되면 provenance `CONTRACT`, 아니면
  기존 형상-시드 `SYNTHESIZED`. 형상 해소 불가는 빈-body + loud-fail(REQ-015 규칙 유지).
- 호출부 `EndpointExplorationRunner.captureHttpCalls`(L2122)는 runner 필드 `stringLiteralsByDto`(L195
  이미 존재)와 신규 runner 필드 `errorContract`를 전달한다.
- **`errorContract` 주입 경로(배선)**: 현재 `BuilderCli`(L927 부근)는 `config.classifierConfig()
  .toClassifier()`로 `ClassifierConfig`를 `ResponseClassifier`로 변환해 runner에 넘기므로, 원시
  디스크립터(`semanticStatusField`/`errorDetailField`/`errorDetailContains`)가 runner에 닿지 않는다.
  해소: `ErrorContractDescriptor`(이 세 필드의 불변 값 객체)를 `ClassifierConfig`에서 파생해
  `EndpointExplorationRunner` canonical 생성자에 **신규 파라미터**로 추가하고, `BuilderCli`가
  `toClassifier()`와 함께 같은 `ClassifierConfig`에서 만들어 전달한다(null 허용 = envelope 미적용).
- 순수 함수성 유지(상태·로깅 없음).

### 4.2 `CapturedHttpCall.Provenance`에 `CONTRACT` 추가
- 현재 `{CAPTURED, SYNTHESIZED}` → `{CAPTURED, SYNTHESIZED, CONTRACT}`.
- 의미: CAPTURED=실측 / CONTRACT=기대값·계약 합성 / SYNTHESIZED=형상-시드.
- 적용 범위: (i) §4.1 span 경로 기대값 합성, (ii) §4.3 redirect per-variant 단언 테스트의 변형 body.
  기존 cumulative 변형 path(생성 제외)의 provenance(SYNTHESIZED)는 변경하지 않는다(surgical).
- 후방 호환: 레거시 JSON에 `responseProvenance` 없으면 CAPTURED 기본(기존 규칙 유지). 신규 값
  추가는 같은 빌드가 생성·소비하는 단일-버전 전제이며, 구 바이너리가 신규 JSON을 읽는 forward-compat는
  비요구(낮은 위험으로 명시). 직렬화·후방호환 테스트 갱신.

### 4.3 redirect per-variant 단언 테스트 (additive)
- 목표: redirect-capable 호출에서 값-충실 변형(happy + 에러분기 각 1)을 **단언하는 생성 테스트**로
  환류한다. 기존 cumulative 변형 path(생성 제외, L1984)는 **건드리지 않고 별도로 추가**한다.
- 메커니즘: `exploreResponseVariants`/`VariantInvoker`를 확장해, 변형 invoke 시 커버리지 delta뿐
  아니라 **SUT의 HTTP 응답 status**도 캡처한다. 구체:
  - `VariantInvoker.invoke()` 반환형을 `ExecutionDataStore` → 신규 record `VariantOutcome(
    ExecutionDataStore coverage, int sutStatus)`로 변경. 실 구현 `sendVariantAndDumpDelta`(현재
    `http.send(...)` 응답을 버림, L2083)가 `response.statusCode()`를 캡처해 함께 반환.
  - blast-radius(동시 갱신 대상 테스트): `EnumVariantReExploreTest`, `EnumVariantNoneModeTest`,
    `StringLiteralVariantReExploreTest`, `StringLiteralVariantNoneModeTest`(스텁 invoker가
    `VariantOutcome` 반환하도록).
  - 새 arm을 연 변형(`KeptVariant`)마다 그 변형 stub body(`CONTRACT`)를 가진 `CapturedHttpCall`과,
    **단언 가능한 `ExploredPath`**(현재 cumulative path의 `expectedStatus=0`(L1985)과 달리 관측
    `sutStatus`를 `expectedStatus`로, `"response-variant"` 생성-제외 마커 없이)를 환류한다.
- 결과: 생성기는 happy(예 201) + 값-변형(예 region=EMBARGOED→422, mode=BACKORDER→409)을 **각각
  별개 생성 테스트**로 방출하며, 각 테스트는 해당 외부 stub body + 관측된 SUT status를 단언한다.
- 변형 수는 기존 `RESPONSE_VARIANT_BUDGET`(32) 및 `ResponseFieldVariantGenerator` 결정적 절단을
  따른다. budget 절단은 기존 loud(`response-variant-budget-truncated`) 유지.

### 4.4 span-only CONTRACT body (단언 없음)
- span-only 발견 호출(§4.1)은 CONTRACT 값-충실 happy body를 갖되, 구동·관측 불가하므로 분기 단언
  테스트는 만들지 않는다(제약 b). 생성 테스트엔 그 호출의 단일 stub(값-충실 body)만 등록된다.

### 4.5 에러 envelope 합성기 + driven 변형 소비
- `ErrorContractDescriptor`(비-null) → 에러 envelope JSON 합성(`ErrorEnvelopeSynthesizer`). 형태:
  `{ "<errorWhenPresent[i]>": "ERROR", "<semanticStatusField>": "ERROR", "<errorDetailField>":
  "<errorDetailContains|"">" }` (있는 필드만). 외부 stub status는 200 + envelope body 기본.
- **소비(dangling 금지)**: errorContract가 non-null이면 `runResponseVariantLoops`의
  `buildVariantCandidates`가 envelope 합성값의 필드를 driven 변형 후보로 주입한다 → 기존 변형
  파이프라인(`exploreResponseVariants`→`buildEgressAssertionPaths`)이 SUT를 구동·관측하고
  `egress-assertion` CONTRACT path가 그 envelope CONTRACT `CapturedHttpCall`을 **참조**한다.
  디스크립터 null이면 후보 미주입(loud 없음 — 정상 부재).
- **실증(REQ-F012-018)**: `samples/error-envelope-service`에 외부 egress 호출 + 외부 응답 envelope
  검사 분기를 추가하고 `--error-when-present errorCode`로 인덱싱해 envelope 티어를 실 SUT E2E로 검증.

### 4.6 생성 테스트 다중 stub 충돌 회피 (`HttpMockComposer`)
- 동일 (method, path)에 happy·에러 변형 stub을 **같은 scope 블록에 동시 등록하면 WireMock에서 서로
  shadow** 된다. 따라서 변형은 §4.3대로 **각각 별개 ExploredPath → 별개 생성 테스트(시나리오)**로
  방출하고, 한 테스트의 scope에는 그 시나리오의 stub 하나만 등록한다. `HttpMockComposer.compose`의
  호출당 단일 stub 방출 구조는 유지된다(변형은 path 레벨에서 분리).

### 4.7 정직한 한계 가시화 (loud)
- span-only 호출에서 외부-응답 분기가 존재하나 구동 불가한 경우, 기존 loud 채널
  (`externalLoudFails`)에 reason(예: `egress-branch-undriven`)으로 노출한다.
- **기대값 출처가 단순 부재**(리터럴·envelope 없음)인 경우는 정상 — loud 없이 `SYNTHESIZED` 폴백.
  형상 해소 불가·callSite 미매칭만 기존 loud-fail 유지. (§4.1·§4.7·§7 fallback 일관.)

---

## 5. 데이터 흐름

```
정적 인덱스: ExternalCallSite(responseShape) + ResponseStringLiteralExtractor(stringLiteralsByDto)
            + enumConstants + ClassifierConfig(semanticStatusField/errorDetailField/errorDetailContains)
   │
   ├─[span 경로] EgressStubComposer.compose(EgressCall, callSites, shapes, stringLiteralsByDto, errorContract)
   │     → happy CONTRACT body(리터럴/enum/형상) / 출처 부재 시 SYNTHESIZED
   │     → captureHttpCalls → CapturedHttpCall(responseBody, provenance, consumedFields)
   │     (mergeDedup: redirect 우선 — 본 작업 미변경)
   │
   └─[redirect 경로] exploreResponseVariants(변형 stub 구동 + SUT status 관측)
         → KeptVariant마다 CapturedHttpCall(CONTRACT, 변형 body) + 단언 가능 ExploredPath(관측 status)
   │
   ▼
graph 환류 → HttpMockComposer.compose
   │  path별 단일 stub 방출(변형은 별개 시나리오 테스트로 분리; shadow 회피)
   ▼
생성 테스트: scope.http().stub(...).respondJson(status, <기대값 body>).register() + SUT status 단언
```

---

## 6. 컴포넌트 경계와 테스트성

- **`EgressStubComposer`**: 입력=(`EgressCall`, `callSites`, `shapes`, `stringLiteralsByDto`,
  `errorContract`), 출력=`Outcome`. 순수 함수 — String 리터럴/enum/envelope/형상-폴백/해소불가 각
  케이스 unit 테스트.
- **에러 envelope 합성기**: 입력=`ErrorContractDescriptor`, 출력=JSON. 순수 함수 unit 테스트.
- **`exploreResponseVariants` 확장**: SUT status 관측 + 단언 ExploredPath 환류. `VariantInvoker`
  스텁으로 status 주입해 unit 검증.
- **`HttpMockComposer`**: CONTRACT body 포함 `CapturedHttpCall` → stub 코드. 문자열 단언.
- **`Provenance`**: shared-model 계약 — 후방 호환 직렬화 테스트.

---

## 7. E2E / 수용 (out-of-process, 계층화)

리뷰가 드러낸 제약(b)에 맞춰 **검증 층을 명시**한다.

- **otel(redirect-capable, 단언 층)**: `samples/order-service` (`InventoryClient` →
  GET `/inventory/stock`; `external.inventory.url`을 recorder로 redirect). 빌더가 값-충실 변형을
  구동·관측해, 생성 테스트가 **(i)** happy(예 mode=STANDARD·region≠EMBARGOED → 201), **(ii)**
  `region="EMBARGOED"` → 422, **(iii)** `mode="BACKORDER"` → 409 를 **각각 별개 테스트로 단언**하고,
  각 외부 stub이 placeholder가 아닌 그 값(`"EMBARGOED"`/`"BACKORDER"`) body를 반환함을 검증한다.
  (위 셋은 최소 요구이며, `mode="EXPRESS_ONLY"` 등 새 arm을 여는 다른 kept 변형도 budget 내에서
  함께 방출될 수 있다.) graph JSON에서 해당 `httpCalls[].responseProvenance == CONTRACT` 및 body 값도 단언.
- **span-only(body 충실도 층)**: 외부 호출이 recorder를 거치지 않는 발견 경로에서, 생성 테스트 stub
  body가 CONTRACT 값-충실(예 String 리터럴 반영)이고 provenance=CONTRACT임을 검증. 외부-응답 분기는
  단언하지 않으며, 분기 미구동이 loud(`egress-branch-undriven`)로 노출됨을 검증.
- **sleuth(교차 모드 + 정직한 abstain 층)**: `samples/legacy-tram/order-web`의 외부 호출은
  `postForEntity(..., Void.class)`로 응답 body가 없다(responseShape 부재). 따라서 이 샘플은 **sleuth
  모드에서 egress 발견이 동작하고, body 충실도 합성이 (대상 부재 시) CONTRACT를 거짓 생성하지 않고
  정직히 abstain**함을 검증한다(잘못된 body 미부여 + 발견·status 기록 유지). CONTRACT body 값 로직은
  모드-독립(builder 합성)이라 otel 층이 1차 검증, sleuth 층은 교차-모드·abstain을 검증한다.
  - (대안: legacy-tram에 응답 DTO를 갖는 외부 호출이 있으면 sleuth CONTRACT body도 직접 검증 —
    요구사항명세 단계에서 샘플 재확인 후 확정.)
- **envelope(에러 계약 층, REQ-F012-018)**: `samples/error-envelope-service`에 외부 egress 호출 +
  외부 응답 envelope(`errorCode`) 검사 분기를 추가하고 `--error-when-present errorCode`로 build(recorder
  redirect). envelope 값 변형이 SUT의 envelope 분기를 구동해 관측 status로 단언하는 `egress-assertion`
  생성 테스트가 방출되고, 그 외부 stub `responseProvenance == CONTRACT`이며 dead(미참조) CONTRACT call이
  없음을 검증한다. (envelope 티어를 synthetic이 아닌 실 SUT로 실증.)
- 모든 E2E는 자기 스코프(고유 project/label/PID)만 teardown, 잔존 0 검증(전역 규칙).

---

## 8. 범위 경계

- 발견(REQ-001~011)·귀속·dedup(redirect 우선, `EgressCallMapper.mergeDedup`)·형상-시드 등록
  (REQ-015)·기존 cumulative 변형 path(생성 제외)는 재설계하지 않는다. 본 작업은 **body 충실도** +
  **redirect 변형의 단언 테스트 additive 환류**.
- 새 프록시·새 캡처 경로를 도입하지 않는다(제약 a 수용).
- `responseProvenance` 의미는 유지하고 `CONTRACT`만 추가한다.
- 코드네임 위생: 사내 식별자는 코드·문서·커밋·PR에 남기지 않고 일반화 표현만 사용한다.

---

## 9. 미해결/연기

- 실측 CAPTURED 티어 강화(실 외부 proxy/record, 운영자 사전 녹화)는 본 작업 밖(제약 a).
- span-only 구동 불가 분기의 SUT 응답 관측(투명 body-캡처 프록시)은 연기.
- 외부 의존 고유 에러 계약의 독립 인덱싱(SUT 계약 재사용 휴리스틱 대체)은 연기.
- sleuth 모드 CONTRACT body 직접 검증용 응답-DTO 외부 호출 샘플은 요구사항명세에서 확정(부재 시
  abstain 검증으로 한정).

# 외부 stub 응답 body 충실도 (REQ-012, egress 4순위) — 설계

- 작성일: 2026-06-24
- 브랜치: `feat-egress-stub-body-fidelity` (base = origin/main `6ca8cdd5`)
- 선행: PR #95(트레이싱 기반 egress 발견, REQ-001~011), PR #101(REQ-015 status-무관 stub register)
- 후속 정의 출처: `docs/superpowers/requirements/2026-06-24-egress-span-capture-requirements.md`의 REQ-012(🔵 Won't), `docs/superpowers/followups/2026-06-24-egress-followups-session-prompts.md`

---

## 1. 문제

`io.graphrag.builder.run.EndpointExplorationRunner`는 외부 HTTP 호출을 두 경로로 stub 한다.

1. **redirect 경로** (`httpExchanges`): SUT의 외부 base URL이 임베디드 recorder
   (`io.graphrag.builder.env.HttpCaptureServer`)로 향하면, recorder가 응답을 serve 하고
   `drainNewExchanges()`가 그 `responseBody`를 캡처한다. 같은 site는 별도로
   `runResponseVariantLoops`가 소비 코드 기대값으로 응답 변형 fuzzing을 구동한다(REQ-008).
2. **span 경로** (`egressCalls`): recorder를 거치지 않고 OTEL/Brave CLIENT span으로만 발견된
   호출. `captureHttpCalls`의 egress 루프가 `EgressStubComposer.compose`로 **단일 형상-시드
   body**(`ShapeJsonSynthesizer`)를 합성해 `CapturedHttpCall(SYNTHESIZED)`로 기록한다.

`runResponseVariantLoops`는 `stubSynthesizer.isRegistered(method, path)`인 site에서만 돈다
(`EndpointExplorationRunner` 현행 코드). span-only 발견 호출은 recorder를 안 거쳐
`isRegistered == false`이므로 **응답 변형 fuzzing에서 제외**되고, `ShapeJsonSynthesizer`의 최솟값
규칙(enum → 선언순 첫 상수, String → `"sample-<field>"`)으로 만든 **임의 placeholder body** 하나만
갖는다.

귀결:

- 생성 테스트의 외부 stub이 소비 코드가 실제로 기대하는 값(예: `FulfillmentMode.EXPRESS`,
  에러 envelope의 판별자 값)이 아니라 placeholder를 반환한다.
- 외부 응답으로 갈리는 SUT 분기를 못 열고, placeholder 데이터에 대해 단언하게 된다.

### 1.1 정직한 두 제약 (사용자 승인 완료)

- **(a) 실측 CAPTURED 티어는 보통 이 하니스에서 합성 품질이다.** recorder는 실제 외부 서비스가
  도달 가능할 때만 proxy/record로 "실측" body를 본다. 테스트 생성 맥락에는 보통 실제 외부가 없어
  recorder가 합성 stub을 serve 하므로, redirect 경로의 body도 합성 품질이다. 따라서 "실측 body"는
  운영자 사전 녹화/실 외부 도달 가능 시에만 real이며 **본 작업의 통제 밖**이다. 본 작업이 실제로
  올릴 수 있는 충실도 레버는 **기대값 기반 합성**이다.
- **(b) 순수 span-only(recorder 미경유) 호출은 SUT 분기를 구동·관측할 수 없다.** 인터셉트 지점이
  없어 SUT를 변형 응답으로 재invoke해 분기 arm을 관측·단언할 수 없다. 그런 호출에 대해 본 작업은
  **생성 테스트 stub의 body를 기대값-충실하게** 만들 뿐이고, 구동·관측이 가능한(=recorder 경유)
  분기만 단언한다. 미구동 분기는 침묵 저하가 아니라 loud로 노출한다.

---

## 2. 목표와 충실도 사다리

발견된 외부 호출 stub의 응답 body 충실도를 형상-시드에서 **소비 코드 기대값/에러 계약 기반**으로
끌어올린다. 충실도 우선순위:

```
실측 캡처 (CAPTURED, 기존 redirect+real-external 경로 — 본 작업 미수정)
  > 기대값/계약 합성 (CONTRACT, 신규)
  > 형상-시드 (SYNTHESIZED, 기존 fallback 유지)
```

신규 **CONTRACT** 티어 = 소비 코드가 응답에서 기대하는 값을 시드한 body. 값 출처(우선순위순):

1. **enum 응답 필드** → `enumConstants`의 상수 목록(형상-시드의 "첫 상수"를 넘어 분기를 여는 값 포함).
2. **String 응답 필드** → `io.graphrag.builder.index.ResponseStringLiteralExtractor`가 추출한
   소비 코드의 equals-family 비교 리터럴(이미 인덱싱; `stringLiteralsByDto`).
3. **에러 envelope** → 인덱싱된 `errorContractStatusField`/`errorDetailField`/`errorDetailContains`
   (graph `GraphAsset`)로 에러 응답 body 합성. (해당 디스크립터는 SUT 자기 응답 분류용으로 인덱싱돼
   있으나, 같은 생태계 외부 의존이 동일 envelope 관례를 쓴다는 휴리스틱으로 외부 에러 body 템플릿에
   재사용한다 — 외부 의존의 에러 계약을 별도로 인덱싱하지 않는다.)

기대값 출처가 하나도 없으면 형상-시드(SYNTHESIZED)로 정직하게 폴백한다.

---

## 3. 재사용 (대부분 기존 자산)

| 자산 | 역할 | 위치 |
|---|---|---|
| `ResponseStringLiteralExtractor` | 소비 코드 equals-family 응답 리터럴 추출 | `builder.index` |
| `ResponseFieldVariantGenerator` | 후보 값 맵 → 결정적 변형 plan(단일→2-way, budget 절단) | `builder.run` |
| `ExternalStubSynthesizer.registerVariant` | trace-id 격리 변형 stub 런타임 등록(redirect 경로) | `builder.run` |
| `EgressStubComposer` | span 경로 body 합성(현재 shape-seed only) | `builder.run` |
| `ShapeJsonSynthesizer` | 형상→minimal JSON, enum/리터럴 값 규칙 | `builder.run` |
| `HttpMockComposer#stubBody` | `CapturedHttpCall` → 생성 테스트 stub 코드 방출 | `test-generator.compose` |
| `CapturedHttpCall` (`responseBody`/`responseProvenance`/`consumedFields`) | 환류 자료구조 | `shared-model` |
| `errorContract*` (`GraphAsset`/`ClassifierConfig`) | 에러 envelope 디스크립터 | `shared-model`/`builder.oracle` |

---

## 4. 변경 (net-new)

### 4.1 `EgressStubComposer` — 기대값 기반 body 합성
- 현재: `CallSiteMatcher.match` → `responseShape` → `ShapeJsonSynthesizer.synthesizeBody`(형상-시드).
- 변경: 매칭된 `ExternalCallSite`의 `responseShape` 필드에 대해, 소비 기대값을 우선 적용한 body를
  합성한다.
  - enum 필드 → 상수(분기를 여는 값 우선; happy=첫 상수, 변형=나머지).
  - String 필드 → `stringLiteralsByDto`의 추출 리터럴.
  - error envelope → `errorContract*` 디스크립터로 에러 body 합성.
- 합성 성공 → provenance `CONTRACT`. 기대값 출처 없음 → 기존 `ShapeJsonSynthesizer` 형상-시드
  (`SYNTHESIZED`). 형상 해소 불가 → 빈-body + loud-fail(기존 규칙 유지).
- 순수 함수성 유지(상태·로깅 없음; 수집·로깅은 `captureHttpCalls`).

### 4.2 `CapturedHttpCall.Provenance`에 `CONTRACT` 추가
- 현재 `{CAPTURED, SYNTHESIZED}` → `{CAPTURED, SYNTHESIZED, CONTRACT}`.
- 의미: CAPTURED=실측 / CONTRACT=기대값·계약 합성 / SYNTHESIZED=형상-시드.
- shared-model 직렬화·후방 호환(레거시 JSON에 `responseProvenance` 없으면 CAPTURED 기본) 테스트 갱신.

### 4.3 생성 테스트 변형 방출 (`HttpMockComposer`)
- 외부-응답 분기 호출에 happy stub + 에러계약 stub을 **둘 다** 방출한다(호출당 다중
  `scope.http().stub(...).respondJson(...).register()`).
- 단, **구동·관측 가능한 분기만 단언**한다(제약 b). recorder 경유로 SUT 응답을 관측한 변형은
  값-충실 stub + 단언. span-only로 관측 불가한 변형은 값-충실 stub만 두고 미구동을 loud로 표시.
- 변형 식별·dedupe는 `ResponseFieldVariantGenerator`의 결정적 label 규칙을 따른다.

### 4.4 에러 계약 envelope 합성기
- `errorContract*` 디스크립터 → 에러 응답 JSON(예: `{ "<statusField>": "<code>", "<detailField>":
  "<contains>" }`). 디스크립터 없으면 에러 변형 생략(loud 없음 — 정상 부재).

### 4.5 정직한 한계 가시화 (loud)
- span-only·구동 불가 분기, 기대값 출처 없음, 형상 해소 불가는 기존 loud-fail 채널
  (`externalLoudFails`)과 provenance로 노출한다. 침묵 저하 금지(프로젝트 원칙).

---

## 5. 데이터 흐름

```
정적 인덱스: ExternalCallSite(responseShape) + ResponseStringLiteralExtractor(리터럴)
            + enumConstants + errorContract* (GraphAsset)
   │
   ▼
EgressStubComposer.compose(EgressCall, callSites, 기대값 출처)
   │  happy body(CONTRACT) + (에러계약 있으면) 에러 body(CONTRACT)  / 없으면 shape-seed(SYNTHESIZED)
   ▼
captureHttpCalls → CapturedHttpCall(responseBody, responseProvenance, consumedFields)
   │  mergeDedup: redirect(existing) 우선 — 본 작업 미변경
   ▼
graph 환류 → HttpMockComposer.compose
   │  호출당 happy + 에러계약 stub 방출(구동 가능 분기만 단언)
   ▼
생성 테스트: scope.http().stub(...).respondJson(status, <기대값 body>).register()
```

---

## 6. 컴포넌트 경계와 테스트성

- **`EgressStubComposer`**: 입력=(`EgressCall`, `callSites`, 기대값 출처), 출력=`Outcome`(body,
  provenance, loudFail). 순수 함수 — 인-프로세스 unit 테스트로 enum/리터럴/에러계약/폴백/해소불가 각
  케이스 검증.
- **에러 envelope 합성기**: 입력=디스크립터, 출력=JSON. 순수 함수 unit 테스트.
- **`HttpMockComposer`**: 입력=`List<CapturedHttpCall>`(CONTRACT 포함), 출력=stub 코드 문자열.
  다중 변형 방출·단언 게이팅을 문자열 단언으로 검증.
- **`Provenance`**: shared-model 계약 — 후방 호환 직렬화 테스트.

---

## 7. E2E / 수용 (out-of-process)

- **otel**: `samples/order-service` (`InventoryClient` → GET `/inventory/stock`,
  응답 `FulfillmentMode` enum 분기). redirect 없이 발견된 호출의 생성 테스트 stub이 placeholder가
  아닌 기대값(enum 상수) body를 반환하고, 그 값으로 갈리는 분기·단언이 반영됨을 검증. happy +
  에러계약 각 1.
- **sleuth**: `samples/legacy-tram/order-web` (`RestTemplate` → POST `/reservations`).
  동일 검증(기대값/계약 body).
- **fallback**: 기대값 출처가 없는 호출은 형상-시드 유지 + loud 노출을 명시 검증.
- 모든 E2E는 자기 스코프(고유 project/label/PID)만 teardown, 잔존 0 검증(전역 규칙).

---

## 8. 범위 경계

- 발견(REQ-001~011)·귀속·dedup(redirect 우선, `EgressCallMapper.mergeDedup`)·형상-시드 등록
  (REQ-015)은 재설계·재구현하지 않는다. 본 작업은 **body 충실도**만.
- 새 프록시·새 캡처 경로를 도입하지 않는다(제약 a 수용).
- `responseProvenance` 의미는 유지하고 `CONTRACT`만 추가한다.
- 코드네임 위생: 사내 식별자는 코드·문서·커밋·PR에 남기지 않고 일반화 표현만 사용한다.

---

## 9. 미해결/연기

- 실측 CAPTURED 티어 강화(실 외부 proxy/record, 운영자 사전 녹화)는 본 작업 밖(제약 a).
- span-only 구동 불가 분기의 SUT 응답 관측(투명 body-캡처 프록시, Q1 option b)은 연기.
- 외부 의존 고유 에러 계약의 독립 인덱싱(SUT 계약 재사용 휴리스틱 대체)은 연기.

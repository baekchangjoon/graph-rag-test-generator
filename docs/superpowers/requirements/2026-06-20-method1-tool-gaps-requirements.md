# graph-rag Method 1 도구 개선(P1~P5) 요구사항명세
> 출처(design spec): docs/2026-06-20-method1-tainted-spring-tool-gaps.md (RFC — C1~C6 + 3-벤더 재리뷰 반영본)
> 구현 repo: graph-rag-fable / branch: feat-method1-tool-gaps
> 완료 정의(DoD): 커버리지 대상 요구사항(Must + 미연기 Should)이 모두 ≥1개의 통과 수용 테스트를 가짐 — 대상 매트릭스 전부 🟢
> 수용 게이트(RFC §6/C4): **내부 fixture(CI 강제)가 1차**, 외부 tainted-spring 8개 레포 재생성이 2차(confirmatory)

---

## 범위 메모

- 본 명세는 RFC의 5개 제안(P1~P5)을 in-scope로 둔다(사용자 결정: P1~P5 전체 구현).
- 우선순위 순서는 RFC §7 단일 기준을 따른다: **P1 → P3 → P2 → P4 → P5**.
- 명시적 연기 항목(🔵, 분모 제외): P2 깊은 모드(REQ-008), P4 cross-endpoint 의존(REQ-016),
  P5 G5b happy-auth(REQ-019 — RFC §6에서 CI 게이트 명시 제외), P5 G5a Kafka-트리거 read-path(REQ-020).
- 모든 신규 합성은 graph-rag의 무-LLM·결정적·무-fabrication 원칙을 유지한다(REQ-021, 횡단 제약).

---

## 요구사항 목록

### P1 — RouterFunction(WebFlux 함수형) 인덱싱 (G1)

### REQ-001 — 함수형 라우트 정적 발견
- 유형: Functional
- 우선순위: Must
- 설명: `@Bean`/임의 메서드가 반환하는 `RouterFunction` 본문의 `RouterFunctions.route()` 체인에서
  `.GET/.POST/.PUT/.DELETE/.PATCH(path, handler)` 호출(`CtInvocation`)을 정적 순회해 (HTTP method, path)를 `Endpoint`로 인덱싱한다.
- 수용기준:
  - Given 함수형 라우팅(`RouterFunctions.route()...build()`)으로 라우트를 선언한 SUT,
    When 인덱싱을 실행하면, Then 각 라우트가 정확한 (HTTP method, path)로 `Endpoint` 목록에 나타난다.
  - Given counseling류 fixture(`POST /internal/counseling/sessions`, `POST /internal/counseling/sessions/{id}/messages`),
    When 인덱싱, Then 두 REST 엔드포인트가 모두 발견된다(현재: 0개).
- 검증 레벨: E2E black-box (내부 함수형 fixture)

### REQ-002 — 함수형 handler body/path-var best-effort 추출
- 유형: Functional
- 우선순위: Must (body-bearing POST 함수형 라우트가 explore skip 게이트를 통과하려면 필요 — Cursor I2)
- 설명: handler가 `HandlerFunction`/메서드 참조면 그 메서드 body에서 `ServerRequest.bodyToMono(T.class)`/
  `pathVariable("…")` 호출을 역추적해 body-shape·path-var를 추출한다. **제약:** `BuilderCli.explore`는
  `shape == null && !GET && !hasPathParam`이면 skip하므로(L573~575), path param 없는 POST/PUT 함수형
  라우트는 body-shape가 있어야 탐색·생성에 도달한다. 역추적 실패 시 REQ-003의 synthetic 최소 shape로 폴백.
- 수용기준:
  - Given handler가 `bodyToMono(Dto.class)`·`pathVariable("id")`를 사용, When 인덱싱,
    Then 해당 `Endpoint`의 body-shape에 `Dto` 필드가, params에 PATH 변수 `id`가 채워진다.
- 검증 레벨: integration

### REQ-003 — 시그니처 해석 실패 시 발견 보장(폴백)
- 유형: Functional
- 우선순위: Must
- 설명: Spoon noClasspath에서 메서드-참조 handler 시그니처 해석이 실패해도 path·method 인덱싱은 보장한다.
  해석 실패 시 body-bearing 라우트에는 explore 도달을 위한 synthetic 최소 body-shape를 부여한다(REQ-002 제약 해소).
- 수용기준:
  - Given handler가 해석 불가한 메서드 참조, When 인덱싱, Then 그 라우트의 (method, path)는 여전히 발견되고,
    body/path-var 미추출 사실이 **인덱싱 단계 산출물에 기록된다**. (기록 위치: `IndexResult` side-channel
    `indexingWarnings` 또는 `exploration-report.json` 확장 — `validationWarnings`는 explore 단계 `ExploredPath`
    필드라 인덱서-only 실패엔 부적합. Cursor I8.)
- 검증 레벨: integration

### REQ-004 — 산출 Endpoint를 기존 파이프라인에 merge
- 유형: Functional
- 우선순위: Must
- 설명: `RouterFunctionIndexer` 산출 `Endpoint`를 `IndexResult.endpoints()`에 concat해 기존 explore 루프·`EndpointSelector`가 그대로 재사용한다(별도 `BuilderCli` 배선, endpoint id 네이밍 호환). `IndexResult`는 불변 record이므로 `endpoints`뿐 아니라 산출 `bodyShapes`(putAll)·`validBodyEndpointIds`(addAll)도 함께 병합한 새 인스턴스를 만든다(Sonnet R5).
- 수용기준:
  - Given 함수형 fixture 인덱싱, When 그래프를 빌드, Then 발견된 함수형 엔드포인트가 graph.json의 endpoints에 포함되고 탐색·생성 단계가 그를 소비한다.
- 검증 레벨: E2E black-box

### P3 — Kafka 서버-생성 필드 스트리핑 (G3)

### REQ-009 — 서버-생성 패턴 비결정 분류(HTTP↔Kafka parity)
- 유형: Functional
- 우선순위: Must
- 설명: `Generator.deterministicPayload`에서 Kafka payload 필드 값이 `looksServerGenerated`(UUID/ISO-8601)이고 `fixture.substitutions()`에 없으면 비결정으로 분류한다. **구현 전제(Sonnet I2/Cursor I7):** `looksServerGenerated`는 현재 `FixtureComposer`의 `private static`이므로 공유 유틸(`ServerGeneratedDetector` 등)로 추출하거나 package-accessible로 승격해 재사용한다(UUID_RE/TIMESTAMP_RE 중복 금지 — 단일 소스).
- 수용기준:
  - Given Kafka payload에 서버-생성 `eventId`(UUID)·`occurredAt`(ISO-8601)가 실림,
    When 테스트 생성, Then 두 필드가 비결정으로 분류된다.
- 검증 레벨: integration

### REQ-010 — 입력 유래·상관 ID 보존(과잉 스트리핑 금지)
- 유형: Functional
- 우선순위: Must
- 설명: `fixture.substitutions()`에 든 값(=입력 유래)은 패턴이 UUID/timestamp처럼 보여도 절대 비결정으로 빼지 않는다. 광범위한 `*Id` 스트리핑을 금지한다.
- 수용기준:
  - Given 입력 유래 ID(`tenantId` 등)가 Kafka payload에 실림, When 테스트 생성,
    Then 그 필드는 제거·matcher가 아니라 **구체값으로 단언**된다.
- 검증 레벨: E2E black-box

### REQ-011 — 서버-생성 필드를 패턴 matcher로 단언(템플릿/모델 확장)
- 유형: Functional
- 우선순위: Must
- 설명: 비결정으로 분류된 서버-생성 필드를 제거가 아니라 패턴 단언으로 대체한다. **대상 템플릿(Cursor I1 정정):**
  REST-path Kafka emit 단언은 `templates/test-class.mustache`의 `kafkaEmits` 블록(L45~53,
  `JSONAssert.assertEquals("{{{payloadJson}}}", record.value(), false)`)이다 — `kafka-test-class.mustache`는
  @KafkaListener consumer 전용이라 무관. 단언 방식은 JSONAssert `Customization`(per-field regex matcher) 또는
  필드별 단언 슬롯(`serverGeneratedAssertions`)으로 고정하고, `kafkaEmits` 모델(`buildScenarioMethod`)에 슬롯을 추가한다.
- 수용기준:
  - Given `eventId`(UUID)·`occurredAt`(ISO-8601) emit, When 테스트 생성, Then 생성 코드가 리터럴이 아니라 UUID/ISO-8601 matcher로 단언하며, 재실행 시 격리 없이 통과한다(현재: `DiaryPostTest.s201_1` 1건 격리).
- 검증 레벨: E2E black-box

### REQ-012 — 캡처-2회 diff(쓰기 경로, INSERT 역연산 정리)
- 유형: Functional
- 우선순위: Should
- 설명: 패턴 휴리스틱의 거짓양성을 보완하기 위해 동일 입력 2회 발행 diff로 "실제로 변하는 필드"를 검출한다. 쓰기 경로에서는 SUT가 별도 프로세스에서 커밋하므로 러너 롤백이 불가 — 1차 발행 행을 캡처된 INSERT의 역(DELETE)으로 정리한 뒤 2차 발행한다(`deleteSeeds`/`Seeds.delete` 패턴 재사용). 역연산 불가 부작용은 diff를 적용하지 않는다. **Touch 클래스(Cursor I9):** `EndpointExplorationRunner`(dual-invoke + traceId pairing), `KafkaCaptureReceiver`(2차 drain), 결과는 `ComposedFixture.nonDeterministicValues`(또는 `CapturedEventEmit`)에 기록.
- 수용기준:
  - Given 쓰기(POST) Kafka 엔드포인트, When 2회-diff 캡처, Then 유니크 제약 충돌 없이 2차 발행이 성공하고, 두 발행에서 값이 바뀐 필드만 비결정으로 검출된다.
- 검증 레벨: integration

### P2 — 선언형 Gateway 라우트 인덱싱 (G2)

### REQ-005 — 게이트웨이 프록시 라우트 정적 발견(얕은 모드)
- 유형: Functional
- 우선순위: Must
- 설명: `RouteLocatorBuilder.routes().route(...).uri(target)...build()` DSL을 정적 순회해 (매칭 predicate path, 대상 uri)를 추출한다. 산출 라우트도 `IndexResult.endpoints()`에 merge한다. **제약(Cursor I3):** shared-model `Endpoint` record에는 다운스트림 target URI 필드가 없으므로, 라우트 메타데이터(target uri) 저장 방식을 정한다 — `Endpoint` 확장 필드 추가 또는 graph.json 별도 섹션(`gatewayRoutes`). 어느 쪽이든 generator가 스모크 생성 시 소비 가능해야 한다.
- 수용기준:
  - Given 선언형 게이트웨이 라우트 SUT(bff-gateway류), When 인덱싱, Then 프록시 라우트가 **≥1개** (path, target)로 발견된다(현재: 0개).
- 검증 레벨: E2E black-box (내부 게이트웨이 fixture)

### REQ-006 — 경로 변환 필터 파싱·미지원 필터 처리
- 유형: Functional
- 우선순위: Must
- 설명: `StripPrefix`/`RewritePath`/`SetPath` 등 경로 변환 필터를 파싱해 인덱싱 path와 실제 다운스트림 도달 path를 일치시킨다. 미지원 필터/predicate 감지 시 해당 라우트를 제외하거나 경고 로그를 남긴다(화이트리스트).
- 수용기준:
  - Given `StripPrefix=1` 필터를 가진 라우트, When 인덱싱, Then 변환 적용 후의 정확한 path가 산출된다.
  - Given 미지원 커스텀 필터/SpEL predicate를 가진 라우트, When 인덱싱, Then 그 라우트는 제외되고 경고가 로그에 남는다.
- 검증 레벨: integration

### REQ-007 — 얕은 모드 프록시 계약 스모크 테스트 생성
- 유형: Functional
- 우선순위: Must
- 설명: 발견된 게이트웨이 라우트에 대해 라우트 존재·매칭 path·포워딩 대상 기반의 "프록시 계약" 스모크 테스트(상태코드/헤더 전파)를 생성한다.
- 수용기준:
  - Given WireMock/`--external-stubs`로 다운스트림을 스텁한 게이트웨이 SUT, When 프록시 라우트 path로 요청,
    Then 생성된 스모크 테스트가 (a) HTTP 응답 상태코드가 스텁 반환 상태코드와 일치함을 단언하고, (b) 전파 대상
    헤더 존재를 단언하며, (c) 재실행 시 green이다. (빈 단언 테스트는 green으로 인정하지 않음 — Sonnet I4/Cursor I3.)
- 검증 레벨: E2E black-box

### REQ-008 — 게이트웨이 깊은 모드(end-to-end via external-stubs) [연기]
- 유형: Functional
- 우선순위: Could
- 설명: 다운스트림을 `--external-stubs`로 스텁해 end-to-end 검증하는 깊은 모드.
- 수용기준:
  - Given 다운스트림 스텁, When 깊은 모드 생성, Then 게이트웨이를 통한 end-to-end 흐름이 검증된다.
- 검증 레벨: E2E black-box
- 상태: 🔵 이번 범위 제외(얕은 모드 우선; 분모 제외)

### P4 — 탐색 상태의 결정적 재현 (G4)

### REQ-013 — 지배 불변식: 재현 가능한 상태만 테스트로 승격
- 유형: Functional
- 우선순위: Must
- 설명: 생성 테스트의 expected status는 *빈 DB + 그 테스트가 선언한 requiredSeeds*만으로 결정적으로 재현 가능해야 한다.
- 수용기준:
  - Given 생성된 임의의 테스트, When 빈 DB + 선언 requiredSeeds만으로 재현, Then 캡처된 expected status와 일치한다(오염 의존 0).
  - Given mindgraph류 `s500_1` 경로(GET, 탐색-오염 500), When 재생성, Then 캡처-재현 상태가 결정적으로 일치하거나(시드 부착) 그 경로가 재현 불가로 판정돼 테스트가 생성되지 않는다 → 격리 해소.
- 검증 레벨: E2E black-box (내부 오염-가능 GET fixture)

### REQ-014 — 비재현 비-2xx 경로 억제(builder)
- 유형: Functional
- 우선순위: Must
- 설명: C6 불변식의 "테스트 미생성"을 builder에서 명시적으로 수행한다 — 재현 검증 실패한 비-2xx·`requiredSeedIds=[]` 경로는 `ExploredPath`로 기록하지 않는다(generator filter는 대안).
- 수용기준:
  - Given 탐색-오염으로만 발생한 500 GET 경로, When 빌드, Then 그 경로의 `ExploredPath`가 그래프에 기록되지 않아 허위 500 테스트가 생성되지 않는다.
- 검증 레벨: integration

### REQ-015 — 드롭 경로 가시성(로그 + KNOWN-LIMITATIONS)
- 유형: Non-functional (observability)
- 우선순위: Must
- 설명: 판정 기준은 "버그냐"가 아니라 "재현 가능하냐"다. 재현 불가로 드롭한 경로는 억제 카운트 + path를 로그로 표면화하고, **builder 산출물에 기록**해 진짜 버그가 조용히 사라지지 않게 한다. **기록 위치(Sonnet I3/Cursor I5):** KNOWN-LIMITATIONS.md는 외부 레포 개념이므로, repo 내 기록은 `exploration-report.json`에 `droppedPaths` 필드를 추가하는 것으로 한다.
- 수용기준:
  - Given 비재현 경로 드롭 발생, When 빌드, Then 드롭된 경로 수와 각 path가 로그 + `exploration-report.json`의 `droppedPaths`에 남는다.
- 검증 레벨: integration

### REQ-016 — cross-endpoint 부수 효과 리소스 의존 연결 [연기]
- 유형: Functional
- 우선순위: Could
- 설명: 다른 엔드포인트 탐색의 부수 효과(POST 등)로 만들어진 리소스를 후속 의존 경로의 시드로 연결한다(미관측·고비용).
- 수용기준:
  - Given 엔드포인트 A의 POST가 만든 리소스에 의존하는 엔드포인트 B, When 재현, Then B가 빈 DB에서 시드 replay로 통과한다.
- 검증 레벨: E2E black-box
- 상태: 🔵 이번 범위 제외(캠페인 미관측·의존 순서 추적 고비용; 분모 제외)

### P5 — 소규모 정합성 개선 (G5 + 메서드-레벨 @RequestMapping)

### REQ-017 — 메서드-레벨 @RequestMapping(method=…) 인덱싱
- 유형: Functional
- 우선순위: Should
- 설명: verb 매핑 어노테이션 없이 `@RequestMapping(method=RequestMethod.X)`만 쓰는 핸들러를 `MAPPING_TO_METHOD` 순회에 포함해 인덱싱한다(EndpointIndexer 국소 수정).
- 수용기준:
  - Given `@RequestMapping(method = RequestMethod.POST)`만 쓰는 핸들러, When 인덱싱, Then POST 엔드포인트로 발견된다.
- 검증 레벨: integration

### REQ-018 — G5c empty-path-var(double-slash) 캡처-재현 인코딩 일치
- 유형: Functional
- 우선순위: Should
- 설명: 빈 path 변수/double-slash(`/…//content`)의 캡처 경로 인코딩과 RestAssured 재현 인코딩을 일치시킨다. **수정 대상(Cursor I10):** `EndpointExplorationRunner.buildPathAndQuery`(캡처 측 sentinel)와 `Generator.resolveLiteralPath`(재현 측 sentinel)의 empty-vs-missing path 변수 처리 정책을 일치시킨다.
- 수용기준:
  - Given 빈 path 변수 경로, When 캡처·재현, Then 캡처 status와 재현 status가 일치한다(현재: 캡처 404 vs 재현 400 불일치 — `DiaryGetContentTest.s404_2` 격리).
- 검증 레벨: E2E black-box (E2E-5)

### REQ-019 — G5b happy-auth(WebFilter) 인지/토큰 부착 [CI 게이트 제외]
- 유형: Functional
- 우선순위: Should
- 설명: 인덱서가 `WebFilter`/`SecurityWebFilterChain` 기반 인증을 인지하도록 보강하거나, 탐색 happy 프로브가 `--auth-*` 토큰을 보호 추정 경로에 기본 부착하도록 옵션화한다.
- 수용기준:
  - Given `WebFilter`로 보호된 엔드포인트, When 탐색, Then happy 프로브가 토큰을 부착해 200 대표 경로를 보존한다.
- 검증 레벨: 수동 실증(RFC §6에서 CI 하한 게이트 명시 제외)
- 상태: 🔵 CI 자동 커버리지 분모 제외(수동 실증으로 대체)

### REQ-020 — G5a 상태 의존 read-path(Kafka 트리거) [연기]
- 유형: Functional
- 우선순위: Could
- 설명: Kafka 소비/선행 쓰기로만 생기는 상태를 읽는 GET을 위해 P4 시드 replay를 Kafka 트리거 상태로 확장(소비 이벤트를 재현 전 발행)한다.
- 수용기준:
  - Given Kafka 소비로만 생기는 상태를 읽는 GET, When 재현, Then 트리거 이벤트 발행 후 200 양성 경로가 커버된다.
- 검증 레벨: E2E black-box
- 상태: 🔵 이번 범위 제외(난이도 상·분리 권장; 분모 제외)

### 횡단 제약

### REQ-021 — 무-LLM·결정적·무-fabrication 유지
- 유형: Non-functional
- 우선순위: Must
- 설명: P1~P5의 모든 신규 합성·인덱싱 경로는 LLM/네트워크 호출 없이 결정적으로 동작하며, 캡처·SQL 근거 없는 path/요청/상태를 fabricate하지 않는다.
- 수용기준:
  - Given 신규 인덱서/합성 코드, When 빌드/생성, Then 어떤 외부 LLM·비결정 소스도 호출하지 않으며 동일 입력에 동일 출력을 낸다.
- 검증 레벨: ArchUnit 금지-import 가드(신규 indexer/generator 패키지에 LLM/HttpClient 직접 의존 금지) + 코드 리뷰.
  (결정성 자체는 각 기능 REQ의 integration 테스트가 이미 검증 — 별도 동작 테스트 중복 제거. Sonnet R6/Cursor I12.)

### REQ-022 — 기존 e2e 회귀 0(하한 게이트)
- 유형: Non-functional
- 우선순위: Must
- 설명: P1~P5 변경이 기존 탐색·생성 동작을 회귀시키지 않는다(RFC §6 하한 게이트). Sonnet I1.
- 수용기준:
  - Given P1~P5 변경, When `./gradlew check` + `e2e/run-e2e.sh`(샘플 order-service 53 테스트) 실행, Then 전부 green이다.
- 검증 레벨: E2E black-box (기존 order-service fixture)

---

## 추적 매트릭스

| REQ-ID  | 요구사항 | 수용 테스트 | Level | Status |
|---------|----------|--------------------|-------|--------|
| REQ-001 | 함수형 라우트 발견 | `RouterFunctionIndexerTest#index_discoversFunctionalRoutes` + `RouterFunctionFixtureIT#functionalRoutes_mergeIntoEndpointIndex` | integration + fixture | 🟢 green |
| REQ-002 | 함수형 body/path-var 추출 | `RouterFunctionIndexerTest#index_extractsBodyShapeAndPathVar` + `RouterFunctionIndexerTest#index_extractsBodyShapeFromBodyToFlux` | integration | 🟢 green |
| REQ-003 | 시그니처 실패 폴백 | `RouterFunctionIndexerTest#index_unresolvedBodyPost_getsSyntheticShapeForExplore` | integration | 🟢 green |
| REQ-004 | IndexResult merge | `IndexResultMergeTest#merge_concatsEndpointsAndUnionsMaps` + `RouterFunctionFixtureIT#functionalRoutes_mergeIntoEndpointIndex` | integration + fixture | 🟢 green |
| REQ-005 | 게이트웨이 라우트 발견 | `GatewayRouteFixtureE2E#discoversProxyRoutes` | E2E | 🔴 planned |
| REQ-006 | 경로 변환 필터 파싱 | `GatewayRouteIndexerTest#parsesStripPrefixAndRejectsUnsupported` | integration | 🔴 planned |
| REQ-007 | 프록시 스모크 테스트 | `GatewayRouteFixtureE2E#generatesProxyContractSmoke` | E2E | 🔴 planned |
| REQ-008 | 게이트웨이 깊은 모드 | — | E2E | 🔵 deferred |
| REQ-009 | 서버-생성 분류(parity) | `DeterministicPayloadTest#classifiesServerGeneratedKafkaFields` | integration | 🔴 planned |
| REQ-010 | 입력 유래 ID 보존 | `KafkaServerFieldFixtureE2E#inputDerivedIdAssertedConcretely` | E2E | 🔴 planned |
| REQ-011 | 패턴 matcher 단언 | `KafkaServerFieldFixtureE2E#serverGeneratedAssertedByPattern` | E2E | 🔴 planned |
| REQ-012 | 캡처-2회 diff | `KafkaDualCaptureDiffTest#detectsChangingFieldsWithCleanup` | integration | 🔴 planned |
| REQ-013 | 재현 불변식 | `ReproducibilityFixtureE2E#expectedStatusReproducesFromCleanDb` | E2E | 🔴 planned |
| REQ-014 | 비재현 경로 억제 | `ExplorationSuppressionTest#dropsNonReproducibleNon2xx` | integration | 🔴 planned |
| REQ-015 | 드롭 가시성 로그 | `ExplorationSuppressionTest#logsDroppedPaths` | integration | 🔴 planned |
| REQ-016 | cross-endpoint 의존 | — | E2E | 🔵 deferred |
| REQ-017 | 메서드-레벨 @RequestMapping | `EndpointIndexerTest#indexesMethodLevelRequestMapping` | integration | 🔴 planned |
| REQ-018 | empty-path-var 인코딩 | `EmptyPathVarFixtureE2E#captureReproduceStatusMatch` | E2E | 🔴 planned |
| REQ-019 | happy-auth(WebFilter) | (수동 실증) | manual | 🔵 deferred |
| REQ-020 | 상태 의존 read-path | — | E2E | 🔵 deferred |
| REQ-021 | 무-LLM·결정적 | `NoLlmDependencyTest#indexers_haveNoLlmOrDirectHttpClientImports` + 코드 리뷰 | integration | 🟢 green |
| REQ-022 | 기존 e2e 회귀 0 | `e2e/run-e2e.sh` (order-service 54 tests) | E2E | 🟢 green |

Coverage: 6/18 green (33%) — target 100% (대상: Must 15 + 미연기 Should 3)
- 분모(18): REQ-001,002,003,004,005,006,007,009,010,011,013,014,015,021,022 (Must 15) + REQ-012,017,018 (Should 3)
  - 주: REQ-002는 Should→Must 승격(explore skip 통과 필수 — Cursor I2).
- 제외(🔵, 4): REQ-008(Could), REQ-016(Could), REQ-019(Should·CI 게이트 제외), REQ-020(Could)

### P1 진행 현황 (2026-06-20)
- **완료(🟢): REQ-001, REQ-002, REQ-003, REQ-004, REQ-021** — RouterFunctionIndexer 구현 + IndexResult 병합 + ArchUnit LLM 금지 가드
- **완료(🟢): REQ-022** — e2e/run-e2e.sh 통과 (54 tests, failures=0, errors=0; P1 변경으로 회귀 없음)
- **잔여**: P2(REQ-005~007), P3(REQ-009~012), P4(REQ-013~015), P5(REQ-017~018) — 구현 대기

### REQ-002 정제 — 해결 완료 (commit 7ccdfd6)
최종 리뷰가 지적한 3개 정제 후보를 deferred로 두지 않고 근본 해결했다(원칙: 타협 없음):
- ✅ **path-template 역추출**: `EndpointIndexer.extractPlaceholders(path)`를 재사용해 `{id}` 등 경로 플레이스홀더를 PATH param으로 역추출한다(handler가 `pathVariable()`를 호출하지 않아도). enrichFromHandler → 역추출 → synthetic fallback 순서라, path-var 라우트는 synthetic body를 받지 않는다. 테스트: `index_backExtractsPathPlaceholders_whenHandlerSkipsPathVariable`, `index_syntheticBody_notAddedWhenPathPlaceholderExists`.
- ✅ **bodyToFlux 컬렉션**: `bodyToFlux(Dto.class)`는 `collection=true` BodyShape로 등록(`Map.merge`로 동일 FQN 충돌 시 collection=true 우선) → `SampleInputSynthesizer`가 `ArrayNode` emit. 테스트: `index_extractsBodyShapeFromBodyToFlux`(collection=true 단언), `index_bodyToMono_collectionIsFalse`.
- ✅ **params 정렬**: PATH→QUERY→FORM→BODY로 `EndpointIndexer.kindOrder`와 동일하게 정렬. 테스트: `index_paramsAreSorted_pathBeforeBody`.

→ REQ-001/002는 best-effort 한계 없이 함수형 라우트의 path-var(명시 호출 + 템플릿)·body(mono/flux)·정렬을 온전히 커버한다.

---

## E2E 연동 규칙(구현 단계)

- 각 E2E/수용 테스트는 검증 REQ-ID를 **`@Tag("REQ-001")`**(repo 관행 — `@DisplayName`은 미사용)로 참조한다.
- 이중루프: 대상 REQ의 E2E를 먼저 작성(🔴→🟡)하고, 내부 단위 TDD로 구현을 드라이브해 🟡→🟢.
- 내부 fixture가 1차 게이트(CI 강제). 외부 tainted-spring 8개 레포 재생성은 2차 confirmatory(릴리스 전·주기).
- **fixture 위치/CI wiring(Cursor I4):** 각 인덱서 fixture SUT는 `e2e/` 하위(또는 `graph-rag-builder` integration
  test 리소스)에 두고, `e2e/run-e2e.sh`에 인덱서-fixture 스텝을 추가하거나 별도 `run-indexer-fixtures-e2e.sh`로
  분리해 `.github/workflows/ci.yml`의 e2e job에 등록한다(현재 job은 order-service만 실행).
- PR 열기 전 매트릭스가 대상 기준 100% 🟢인지, 각 🟢 REQ가 실제 통과 테스트와 대응하는지(테스트명 대조) 확인한다.

## 우선순위 기반 구현 순서(RFC §7)

1. **P1**(REQ-001~004) → 2. **P3**(REQ-009~012) → 3. **P2**(REQ-005~007) → 4. **P4**(REQ-013~015) → 5. **P5**(REQ-017,018; REQ-019 수동) → 횡단 REQ-021은 각 단계에서 함께 검증.

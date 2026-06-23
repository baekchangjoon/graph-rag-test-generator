# LLM 기반 값 오라클(LlmOracle) — 설계 (v2)

- 일자: 2026-06-23
- 브랜치: feat-llm-oracle
- 관련 SPI: `io.graphrag.builder.oracle.InputOracle` (`StaticLiteralOracle`, `ConcolicOracle`와 동격)
- 결정 경로: 아키텍처 3대 결정은 secretary inbox consult(`9aae7b46…`) → **by:user, status=decided: 전부
  추천안 수락**(LLM API 비용 도입 차원 본인 에스컬레이션 후 수락).
- 리뷰: 4-리뷰어 design-doc 리뷰(Claude Sonnet ×2 + Gemini 3.5 Flash + Cursor — Cursor 슬롯
  지연으로 Sonnet 폴백 병행, 이후 Cursor도 회신) 전원 `needs_revision`. **본 v2가 반영본**
  (반영/기각 판정은 문서 끝 "리뷰 반영" 참조).
- 비목표 경계: **구조(shape)는 본 작업이 아니다.** 임의 바디 형상은 별도 "generic 재귀 빌더 +
  Instancio 폴백" 작업이, 배열/중첩/조인가드는 이미 머지된 `feat-array-nested-mutation-joinguard`가
  담당한다. **LlmOracle는 *값*만 — 구조도 커버리지 루프도 아니다.**

## 문제

랜덤 변이·concolic(ASM+Z3)은 *구조적으로 유효한 경계값*은 잘 만들지만, **비즈니스 검증을 통과해
깊은 로직까지 가는 *도메인 그럴듯한* 값**(형식 코드/패턴, 핸들러가 기대하는 enum-스타일 문자열,
쿠폰코드 등)에 약하다. happy-path 합성 한계와 exploration 변이 한계를 **분리**해 보면:

1. **`@Pattern` 정규식 — 유효값 0건(핵심 갭).**
   - happy-path: `SampleInputSynthesizer.scalarNode`(189–193행)는 String 필드에 `email` 추정이면
     `"probe@example.com"`, 아니면 `"sample-" + fieldName`을 넣는다. `@Pattern(regexp="[A-Z]{3}")`
     같은 제약은 `"sample-code"`가 **불충족** → `@Valid`가 400으로 거부 → 핸들러 바디 진입 불가.
   - exploration: `InputMutator.constraintDirected`의 `NOT_NULL, NOT_BLANK, PATTERN ->` 분기
     (302–303행)는 주석 `"@Pattern 값 생성은 보류(YAGNI)"`로 **정규식 충족값을 명시적으로 미생성**.
   - concolic: 문자열 정규식 제약을 풀지 못한다.
   → @Pattern 필드를 가진 엔드포인트는 그 필드가 게이트하는 **모든 하위 분기가 통째로 막힌다**.

2. **enum-스타일 문자열 도메인 코드 — 핸들러 내부 비교.**
   `if (status.equals("ACTIVE"))`처럼 핸들러 바디에 분기 리터럴이 있어도, 그 값이 DTO 필드에 안
   박혀 있으면 `StaticLiteralOracle`(컨트롤러 클래스 리터럴 추출)이 못 잡는 경우가 있고, concolic은
   문자열 동치를 못 푼다. `constraintDirected`의 `stringCandidates→streq` 경로(streq 변이, 365–368행
   소비)는 후보가 있어야 작동한다.

3. **`@Email`은 부분 갭(보조).** happy-path는 이미 `"probe@example.com"`으로 201을 연다(위 189–193).
   다만 exploration 단계의 `constraintDirected` EMAIL 분기(353–356행)는 *위반값* `"not-an-email"`만
   생성(reject arm)하고, 도메인 다양성(예: 특정 도메인만 허용하는 핸들러 분기)은 못 연다. → @Email은
   핵심 갭이 아니며, 본 작업은 @Pattern·도메인 코드 중심.

핸들러 메서드 소스 + 필드명/타입 + 추출 제약(@Pattern 정규식 포함)을 주면 LLM은 이런 **의미·도메인
값**(예: `[A-Z]{4}-\d{4}` + `startsWith("GOLD")` → `"GOLD-1234"`)을 강하게 생성한다.

## 범위

- **포함**:
  - `LlmOracle implements InputOracle` — 엔드포인트별 핸들러 컨텍스트로 LLM을 **단일 구조화 호출**해
    필드별 후보 *문자열 값*을 생성, `InputCandidates.strings`로 매핑.
  - `HandlerSourceExtractor` — `endpoint.handlerClass()+handlerMethod()` → 메서드 **본문 소스 텍스트**
    추출(Spoon). (인덱스 산출물엔 핸들러 소스가 없음 → 별도 추출 필요.)
  - `BuilderCli`에서 **플래그 게이트** 뒤 static+concolic과 `merge`. 플래그는 `BuildConfig`로 전달.
  - **결정성 캐시**: `(endpoint.id + 핸들러 본문 소스 해시 + 정렬된 필드셋 + 모델 ID)` 키로 LLM 출력을
    repo `src/main/resources/llm-oracle-cache/` 에 JSON 커밋. 빌드 시 **classpath 읽기**, miss일 때만
    API 호출(파일시스템 쓰기). CI는 키 없음 → **cache-or-skip**(오프라인·결정적).
  - **정적 필드선별 휴리스틱**: 엄격 검증 필드(@Pattern/@Email/도메인-코드 후보)만 LLM에 보냄.
    선별 0건 엔드포인트는 LLM 호출 자체 skip.
  - **그라운딩(loud-fail)**: LLM 출력을 BodyShape에 게이트 — 존재 필드·String 타입 호환만 수용,
    부적합은 **로그 후 폐기**.
  - Anthropic Java SDK 의존 추가 + 모델 ID 핀(기본 Haiku 4.5). **모든 LLM/HTTP 코드는
    `io.graphrag.builder.oracle` 패키지에만**(아래 아키텍처 경계 참조).
- **제외(비목표 — YAGNI)**:
  - **구조(shape) 생성** — 바디 형상/중첩/제네릭/배열.
  - **커버리지 피드백 루프** — 잔여 미커버 분기 측정 후 재질의. LLM은 union 추가 멤버일 뿐.
  - **자율 에이전트화** — tool-use 루프. 제약된 단일 structured 호출만.
  - **자동 모델 에스컬레이션** — Haiku→Sonnet 자동 승격(수동, `--llm-model`).
  - **numeric/reals 채널 기여** — 선별이 String 필드만 고르므로 numeric은 잉여. 숫자 경계는
    concolic 담당. LlmOracle는 **strings 채널만** 채운다(후속 확장 여지로 남김).

## 설계

### 핵심 불변식

LLM 출력은 **`InputCandidates.merge`로 합쳐지는 union의 추가 멤버**일 뿐이다. 소비 경로 **무변경**:
`EndpointExplorationRunner.run`이 글로벌 `candidates.strings()`를 필드명 키 `stringCandidates`로
투영하고, `InputMutator.constraintDirected`가 `streq-<field>-<value>` 변이로 흘려보낸다. 따라서
LlmOracle가 `strings`에 필드명 키로 값을 기여하면 **러너·뮤테이터 변경 0**으로 자동 소비된다.

### 아키텍처 경계 (NoLlmDependencyTest 준수)

기존 아키텍처 테스트 `NoLlmDependencyTest`(REQ-021)는 `io.graphrag.builder.index` 패키지에서
`anthropic`/`openai`/`java.net.http.HttpClient`/`okhttp3` import를 금지한다. **모든 LLM·HTTP 코드는
`io.graphrag.builder.oracle`(필요 시 하위 패키지)에만 둔다** — index 패키지 불가침. (Anthropic Java
SDK는 OkHttp 백엔드이므로 oracle 패키지에 격리.)

### 컴포넌트 분해

각 단위는 단일 책임 + 명시 인터페이스로 독립 테스트 가능하게 둔다.

```
io.graphrag.builder.oracle
  LlmOracle (implements InputOracle)
    ├─ HandlerSourceExtractor  : endpoint → 핸들러 메서드 본문 소스(Spoon)
    ├─ EndpointFieldSelector   : 엔드포인트 → LLM에 보낼 (필드, 제약) 정적 선별
    ├─ LlmValueClient          : (LlmRequest) → LlmFieldValues  (교체가능 인터페이스)
    │    ├─ AnthropicValueClient: Anthropic SDK structured 호출 (lazy; 키 없으면 호출 시점에만 실패)
    │    └─ (테스트: FakeValueClient — 결정적 스텁)
    ├─ LlmValueCache           : 키→LlmFieldValues JSON 캐시 (read=classpath / write=filesystem)
    └─ ShapeGate               : 후보값 × BodyShape 검증·필터 (loud-fail 로그)
```

#### 1. `InputOracle` 구현 — `LlmOracle`

`analyze(SutCode)`는 SPI 호환 유지. **엔드포인트 컨텍스트는 생성자 주입**(BuilderCli가 이미 만든
`IndexResult`(endpoints+bodyShapes) 재사용). 시그니처:

```java
public final class LlmOracle implements InputOracle {
    public LlmOracle(IndexResult index,                  // endpoints + bodyShapes (이미 빌드됨)
                     ConstraintExtractor constraints,     // 핸들러 내부 enum/비교(도메인코드 선별 보조)
                     ValidationConstraintExtractor valid, // @Email/@Pattern/@Size... (DTO 제약)
                     HandlerSourceExtractor handlerSrc,   // 핸들러 본문 소스
                     LlmValueClient client,               // 교체가능 (실제/Fake)
                     LlmValueCache cache,
                     String modelId);
    public String name() { return "llm"; }
    public InputCandidates analyze(SutCode sut);         // 전 엔드포인트 순회 → 글로벌 union(strings만)
}
```

`analyze`는 (DTO 제약을 **1회 일괄** 추출해 재사용) 엔드포인트별로 (a) 핸들러 메서드 본문 소스,
(b) 바디 필드명·타입, (c) 추출 제약(특히 @Pattern 정규식)을 모아 `LlmRequest`를 만들고, **캐시 우선**
조회 → miss & 키 존재면 `client` 호출 → `ShapeGate` 검증 → 필드명 키로 `InputCandidates.strings`에
누적. 전 엔드포인트 결과를 `merge`로 합쳐 글로벌 union 반환. 결정성: 엔드포인트·필드·값 모두
정렬(TreeMap/TreeSet, 기존 merge 규약).

#### 2. `HandlerSourceExtractor` — 핸들러 본문 소스

Spoon으로 `SutCode.srcDir()`를 파싱해 `(handlerClass, handlerMethod)` → 메서드 **본문 소스 텍스트**
반환. 캐시 키 해시·LLM 프롬프트 입력 양쪽에 쓰인다. (인덱스/Endpoint/ConstraintExtractor 어디에도
메서드 전체 소스가 저장돼 있지 않으므로 신규 유틸 필요.) 가능하면 공유 Spoon 모델 재사용(후속 최적화).

#### 3. `EndpointFieldSelector` — 정적 필드선별

엔드포인트 mutable 필드 중 **LLM이 가치 있는 것만**:
- `@Pattern`(정규식) 또는 `@Email` 제약이 붙은 String 필드(정규식은 `FieldConstraint.strArg`로 보유),
- enum-스타일 도메인 코드 후보(필드명 `status`/`type`/`code`/`tier`/`grade` 등 + String 타입 +
  Java enum 타입 **아님**),
- (보조) 핸들러 본문에 `.equals("...")`/`switch` 비교가 있으나 리터럴 미추출인 String 필드.

선별 0건 → 그 엔드포인트 **LLM 호출 자체 skip**(비용 0). 순수 숫자·이미 enum 타입·이미 리터럴 추출
필드는 제외(싼 오라클이 이미 커버).

#### 4. `LlmValueClient` — 교체가능 인터페이스

```java
public interface LlmValueClient {
    /** 엔드포인트 1건 필드 후보값 생성. 결정적 호출(temperature 0). */
    LlmFieldValues generate(LlmRequest request);
}
```
`LlmRequest`(record): `endpointId`, `handlerSource`, `fields`(name:type 목록), `constraints`(필드별
@Pattern/@Email/도메인힌트), `modelId`. **핸들러 소스는 free-text, 필드/제약은 구조화 파라미터로
분리**(프롬프트 주입 완화). `LlmFieldValues`(record): `Map<String,List<String>> stringValuesByField`.

- `AnthropicValueClient`: Anthropic Java SDK로 **단일 structured 출력 호출**.
  `StructuredMessageCreateParams<LlmFieldValues>` + `.outputConfig(LlmFieldValues.class)`로 JSON 스키마
  자동 도출·검증(claude-api 스킬 Java 문서 기준; 구현 시 핀 버전 API로 최종 확인). `temperature=0`,
  모델 ID 핀(기본 `claude-haiku-4-5-20251001`, `--llm-model`로 `claude-sonnet-4-6`). API 키 env
  `ANTHROPIC_API_KEY`. **생성은 lazy/관용** — 키 없어도 객체 생성 실패 금지; 실제 `generate` 호출
  시점에만 키 필요(캐시 hit면 호출 안 함).
- 테스트: `FakeValueClient`(고정 응답)로 LlmOracle·캐시·게이트 결정성/로직을 API 없이 검증.

#### 5. `LlmValueCache` — 결정성 캐시

- **키**: `sha256(endpoint.id() + "\n" + 핸들러 본문 소스 텍스트 + "\n" + 정렬된 필드셋(name:type) +
  "\n" + modelId)`. **핸들러 본문을 해시에 포함**해 바디 변경(분기 리터럴 수정 등) 시 자동 무효화 +
  무관한 커밋엔 안정(커밋 SHA 미사용). `endpoint.id()` 포함으로 오버로드/동명 메서드 충돌 방지.
- **저장(write)**: `graph-rag-builder/src/main/resources/llm-oracle-cache/<key>.json`(파일시스템
  소스트리; **디렉터리 신규 생성 필요** — 현재 `src/main/resources` 부재). 값 = `LlmFieldValues` +
  메타(modelId, 생성일).
- **읽기(read)**: classpath 로더(`getResourceAsStream("/llm-oracle-cache/<key>.json")`) — 빌드된 JAR
  에서도 동작. hit → API 미호출.
- **흐름**: read hit → 사용. miss + `ANTHROPIC_API_KEY` 존재 → `client.generate` → filesystem write
  (개발자가 커밋) → 사용. miss + 키 없음(CI) → **skip**(빈 기여, `log.info` 1줄). write 실패(권한 등)
  → `log.warn` 후 그 값은 사용하되 캐시 미기록(빌드 중단 금지). **캐시를 커밋 안 하면 다음/CI 실행은
  다시 skip** — README/에러처리에 명시.

#### 6. `ShapeGate` — 그라운딩(loud-fail)

LLM이 돌려준 (필드→문자열값)을 BodyShape에 대해 검증:
- 필드가 BodyShape에 **존재**? 없으면 폐기 + `log.warn`.
- 타입 호환? 본 작업은 String 채널만 → **`BodyField.javaType()`이 `java.lang.String`인 필드에만**
  수용(그 외 폐기 + 로그). (numeric/float는 LLM 미지원.)
- 통과분만 `InputCandidates.strings`에 반영. **junk 조용히 사용 금지**(테스트로 단언).

### BuilderCli 배선 (플래그 게이트, BuildConfig 경유)

배선 지점은 `BuilderCli.explore(ExplorationEnvironment env, BuildConfig config, IndexResult index, …)`
(파일 라인 515~) 내부의 오라클 merge(현 573–578). `options`는 `main()`(86행) 로컬이라 `explore()`에
없음 → **플래그를 `BuildConfig`로 전달**한다.

1. `BuildConfig` record에 `boolean llmOracle, String llmModel` 필드 추가(+ 생성자 갱신).
2. `main()`: `options.containsKey("--llm-oracle")` / `options.getOrDefault("--llm-model",
   "claude-haiku-4-5-20251001")`로 채워 `BuildConfig` 생성.
3. `explore()` 오라클 merge 지점(573–578 부근), 기존 `index`(IndexResult)·`constraintExtractor`(549행
   생성) 사용:
```java
if (config.llmOracle()) {
    LlmOracle llm = new LlmOracle(index, constraintExtractor, new ValidationConstraintExtractor(),
            new HandlerSourceExtractor(config.sutSrc()),
            AnthropicValueClient.fromEnv(config.llmModel()),   // lazy: 키 없어도 생성 OK
            LlmValueCache.defaultClasspath(), config.llmModel());
    inputCandidates = inputCandidates.merge(llm.analyze(sutCode));
}
```
`--llm-oracle` 없으면 **코드 경로 완전 no-op**(회귀 0). `GRB_ORACLE=static`(concolic off)과
`--llm-oracle`은 **독립 union 멤버** — 조합 자유(static-only / +concolic / +llm). BuilderCli 상단 CLI
도움말 주석에도 두 플래그 추가.

### 의존성

- `gradle/libs.versions.toml`: `anthropicJava = "2.34.0"`(claude-api 스킬 기준 최소 핀; gradle 해석
  성공 버전으로 확정) + `anthropic-java = { module = "com.anthropic:anthropic-java", version.ref =
  "anthropicJava" }`. Maven Central 제공.
- `graph-rag-builder/build.gradle.kts`: `implementation(libs.anthropic.java)`.

## 에러 처리

- **API 키 부재 + 캐시 miss**(CI 기본): skip, `log.info`. 실패 아님. 빈 strings 기여.
- **API 호출 실패**(네트워크·rate limit·refusal): 해당 엔드포인트만 skip + `log.warn`, 나머지 진행
  (best-effort union). 빌드 중단 금지.
- **structured 출력 스키마 불일치**: SDK 검증·재시도. 최종 실패 시 엔드포인트 skip.
- **ShapeGate 폐기 / 캐시 write 실패**: 로그만, 빌드 계속.
- **프롬프트 주입·비용**: 핸들러 소스를 프롬프트에 넣으므로(소스 내 `"Ignore previous…"` 등) 완화 —
  (a) 메서드 **본문만**(전체 파일 아님) 포함, (b) 필드/제약은 structured 파라미터로 분리, (c)
  temperature 0 + structured output으로 출력 형태 고정(자유 행동 차단), (d) 플래그 기반 opt-in +
  **내부 SUT 전용** 권고를 README에 명시. (자율 에이전트 아님 — 단일 제약 호출.)
- 결정성: 같은 (endpoint, 핸들러 본문, 필드셋, 모델) → 캐시 동일 → 동일 출력(캐시가 하드 보장;
  temperature 0은 보조).

## 테스트 (E2E/수용 + 단위)

### E2E/수용 (요구사항명세에서 도출 — 다음 단계 `requirements-spec`에서 REQ-ID·추적 매트릭스 확정)

최고 가용 out-of-process 레벨 = **빌더를 실제 SUT(Docker)에 대해 실행**(기존 `BuilderIntegrationTest`
패턴). 엄격 검증 필드를 가진 **신규 fixture 엔드포인트**가 필요(기존 `SignupController`는 @Email만 +
검증 후 깊은 분기 없음 → 커버리지 증가 시연 불가; @Pattern `ValidatedRequest`는 test-resources 전용으로
Docker-e2e 비대상).

- **fixture(최소 사양)**: order-service에 `@Pattern`으로 게이트되는 **깊은 분기** 엔드포인트 추가.
  예: `record RedeemRequest(@Pattern(regexp="[A-Z]{4}-\\d{4}") String couponCode, @Min(1) int quantity)`,
  핸들러 본문 `if (couponCode.startsWith("GOLD")) { …gold-tier 분기… } else { …standard… }`.
  - LLM off: happy 합성은 `"sample-couponCode"` → @Pattern 불충족 → 400, 핸들러 바디·`startsWith`
    분기 **도달 불가**. concolic도 정규식 미해결.
  - LLM on(캐시값 `"GOLD-1234"`): 정규식 충족 + `startsWith("GOLD")` true → **gold-tier 깊은 분기
    도달**. (정확한 fixture·REQ-ID는 requirements 단계에서 확정.)
- **AC-E2E-A (회귀)**: `--llm-oracle` 미지정 시 기존 경로 **불변·GREEN**(InputCandidates·생성물 동일).
- **AC-E2E-B (효과)**: `--llm-oracle` + **커밋된 캐시**로 실행 시 fixture 엔드포인트의 Jacoco branch
  커버리지가 LLM off 대비 **증가**(gold-tier 분기 도달). 캐시로 **오프라인·결정적**(API 무호출).

### 단위 (이중루프 inner; 결정성·캐시 필수)

- `LlmValueCacheTest`: 키 결정성(필드 순서 무관, 핸들러 본문 변경 시 키 변화), classpath read /
  filesystem write 라운드트립, hit/miss.
- `LlmOracleTest`(FakeValueClient): 캐시 hit 시 client 미호출, miss+key-less skip, merge 누적,
  결정성(2회 동일 출력), strings-only 기여.
- `EndpointFieldSelectorTest`: @Pattern/@Email/도메인코드 선별, 순수 숫자·enum타입·리터럴추출 제외,
  0건 skip.
- `ShapeGateTest`: 존재필드 수용, 미존재 폐기+로그, **비-String 필드(int/long/double) 폐기**, junk
  미사용 단언.
- `HandlerSourceExtractorTest`: 메서드 본문 추출, 미존재 메서드 graceful.
- `AnthropicValueClientTest`: 키 없을 때 생성자 미실패(lazy) 단언 + structured 파라미터 구성
  (모델ID·temperature0·스키마) 단위 검증(실 API 무호출).
- `NoLlmDependencyTest`(기존): index 패키지 무오염 **유지**(LLM 코드는 oracle 패키지) — 회귀 가드.

### 완료 정의

요구사항명세 추적 매트릭스 **Must + 미연기 Should 100% GREEN**, 전 모듈+e2e 회귀 GREEN, LLM off 경로
불변, 캐시로 테스트 오프라인·결정적, `NoLlmDependencyTest` GREEN.

## 리뷰 반영 (4-리뷰어 판정 요약)

- **반영(critical/important)**: 생성자 `StaticIndex→IndexResult`; 플래그 `options`→`BuildConfig`
  필드; `HandlerSourceExtractor` 신규(인덱스에 핸들러 소스 없음); 캐시 키에 **핸들러 본문 해시 +
  endpoint.id** 포함(스테일/오버로드 해결); `AnthropicValueClient` **lazy**(키 없어도 생성); **numeric
  채널 제거**(선별이 String만 → 잉여; ShapeGate String-only); **@Email 갭 재서술**(happy는 이미 충족,
  핵심은 @Pattern); E2E **fixture 구체화**(@Pattern 게이트 깊은 분기, order-service); SDK 버전 핀 +
  Maven 명시; `src/main/resources` 신규 생성 + read(classpath)/write(fs) 분리; **프롬프트 주입·비용**
  완화 명시; **NoLlmDependencyTest 경계**(oracle 패키지 격리) 명시; DTO 제약 1회 추출(중복 Spoon
  완화); 라인 인용→심볼 앵커.
- **기각(rationale)**: 캐시 위치를 test/resources·루트로 이동(SonnetFB I8) → **기각**: 프로덕션
  오라클이 classpath에서 읽어야 CI 오프라인이 성립, JSON 소량이라 JAR 영향 미미, LFS 불필요.
  commitSha를 키에 포함(Sonnet#1 I5 대안) → **기각**: 매 커밋 무효화로 커밋캐시 재사용 무력화;
  핸들러 본문 해시가 더 정밀.
- **후속(비목표)**: 커버리지 피드백 재질의, numeric/reals LLM 기여, 자동 모델 에스컬레이션, ablation
  매트릭스(GRB_LLM_ORACLE) 자동화.

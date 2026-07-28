---
name: provenance-analysis
description: Recursively slices guard-reachable call chains from an HTTP endpoint handler and tags each guard operand's value origin (INPUT / DB_READ / EXTERNAL_RESPONSE / DERIVED / UNKNOWN) via the graph-rag-builder `provenance` CLI subcommand, producing a provenance-report.json. Use this first — before triple-synthesis and trial-loop — whenever you need to synthesize a deep-happy-path request triple (body/seed/stub) for an endpoint that a build is not yet reaching 2xx on.
---

# provenance-analysis (C1)

이 스킬은 삼중(triple) 합성 파이프라인의 **첫 단계(C1)**다. 실행 순서는 항상
`provenance-analysis(C1) → triple-synthesis(C2) → trial-loop(C3)` — 별도 오케스트레이터
스킬은 없으므로(YAGNI), 이 순서를 에이전트가 직접 지킨다.

## 선행 조건

이 스킬은 **선행 산출물이 필요 없다** — 파이프라인의 첫 단계이므로 provenance-report.json도,
후보 트리플도 아직 없어도 된다. 필요한 것은 SUT 소스 디렉토리뿐이다. (다음 단계인
triple-synthesis는 이 스킬의 산출물인 provenance-report.json을 필수로 요구한다.)

## 결정적 코드가 하는 일 (에이전트가 재구현하지 말 것)

`provenance` CLI 서브커맨드가 다음을 전부 코드로 수행한다:

- 대상 엔드포인트 핸들러에서 시작해 가드(throw 또는 에러-return으로 이어지는 조건식)에
  도달하는 메서드 체인만 재귀적으로 슬라이싱한다(깊이 cap `--provenance-depth`, 기본 3,
  순환 가드 포함).
- 가드에 쓰인 각 피연산자(`ValueRef`)의 출처(origin)를 판정한다:
  - `INPUT` — 핸들러 파라미터(`@RequestBody`/`@PathVariable`/`@RequestParam` 등) 유래
  - `DB_READ` — repository/JPA/MyBatis mapper 반환값 유래(엔티티 getter 체인 → 컬럼 매핑,
    `@Column`/`@Table` 오버라이드 인식)
  - `EXTERNAL_RESPONSE` — RestTemplate/WebClient/Feign 반환 DTO 유래
  - `DERIVED` — 위 출처값의 산술·문자열 파생
  - `UNKNOWN` — 해석 실패(noClasspath 미해석, 리플렉션·프록시, 인터페이스 구현체 다수 미해소 등)
- 가드에 전혀 쓰이지 않는 요청 필드(`unguarded`)에 `semanticHint`(person-name/email/phone/
  free-text/none 등, 필드명·타입 기반 결정적 규칙)를 태깅한다 — 이는 다음 단계(C2)의 갭필
  프롬프트 입력으로만 쓰인다.

## CLI 실행법

```
./gradlew -q :graph-rag-builder:run --args="provenance \
  --sut-src <SUT_SRC_DIR> \
  [--sut-resources <RESOURCES_DIR>] \
  --endpoint '<HTTP_METHOD> <PATH>' \
  [--provenance-depth 3] \
  --out <OUT_DIR>/provenance-report.json"
```

플래그(실제 `BuilderCli` 소스 기준, 추측 아님):

| 플래그 | 필수 | 기본값 | 설명 |
|---|---|---|---|
| `--sut-src` | 예 | — | SUT 소스 루트(멀티 루트 glob 문법은 `build` 서브커맨드와 동일) |
| `--sut-resources` | 아니오 | 각 루트의 형제 `resources` 디렉토리 | MyBatis mapper XML 등이 있는 리소스 디렉토리 |
| `--endpoint` | 예 | — | 대상 엔드포인트 1개를 지목하는 spec. **정확히 1개**의 HTTP 엔드포인트로 해소되지 않으면 실패한다 |
| `--provenance-depth` | 아니오 | `3` | 호출 그래프 재귀 추적 깊이 cap |
| `--out` | 예 | — | 산출 `provenance-report.json` 경로(상위 디렉토리는 자동 생성) |

## 산출물

`provenance-report.json` — 엔드포인트별 가드 목록(`guards[]`, 각 가드의 위치·비교 연산자·
피연산자들과 그 origin/javaType/semanticHint)과 `unresolved[]`(해석 실패 목록), `unguarded[]`
(가드 미사용 필드 + semanticHint), `collectionPaths[]`를 담는다.

`collectionPaths[]`는 **컬렉션 필드의 dot-path 접두사** 목록이다(예: `["lineItems"]`). 대표원소
규약상 `List<LineItem> lineItems`의 원소 필드는 bracket 없이 `"lineItems.sku"`로 평탄화되므로,
dot-path만으로는 그 자리가 중첩 객체인지 배열인지 알 수 없다 — 다음 단계(triple-synthesis)가
`{"lineItems":[{…}]}`처럼 **배열**을 만들 유일한 근거가 이 목록이다. 이 목록이 비면 합성은
중첩 객체로 폴백하므로, 요청 DTO에 컬렉션 필드가 있는데 여기가 비어 있으면 결함으로 보고하라.

## unresolved / UNKNOWN 항목 처리 절차 (에이전트가 직접 판정)

정적 코드가 해석하지 못한 `unresolved`/`UNKNOWN` 항목만 에이전트가 개입한다 — 이미 해석된
`INPUT`/`DB_READ`/`EXTERNAL_RESPONSE`/`DERIVED` origin은 도구 판정을 신뢰하고 그대로 둔다.

**대상은 두 곳이다 — `unresolved[]`가 비어 있어도 끝난 게 아니다.** ① `unresolved[]` 배열의
항목, 그리고 ② `guards[].operands[]` 중 `origin: "UNKNOWN"`인 피연산자. 후자는 `unresolved[]`에
중복 수록되지 않으므로, `unresolved: []`인 리포트에도 UNKNOWN 피연산자가 여러 개 있을 수 있다
(예: 루프 변수를 거친 컬렉션 원소 접근, 루프 집계 변수, `null` 리터럴). 또한 **UNKNOWN
피연산자에는 자체 소스 위치 필드가 없다** — 소속 가드의 `at`(파일:라인)을 위치 근거로 써라.

1. 위 ①②의 각 항목에 대해, 리포트가 가리키는 소스 위치(`unresolved`는 클래스#메서드,
   UNKNOWN 피연산자는 소속 가드의 `at`)를 직접 연다.
2. 실제 구현체·리플렉션 대상·인터페이스 구현체를 눈으로 확인해 origin(INPUT/DB_READ/
   EXTERNAL_RESPONSE/DERIVED 중 하나, 그래도 판정 불가하면 UNKNOWN 유지)을 판정한다.
3. 판정 근거를 provenance-report.json과 같은 디렉토리의 `provenance-notes.md`에
   근거(파일:라인, 판단 이유)와 함께 남긴다. **provenance-report.json 자체의 이미 채워진
   필드(guards/origin 등)는 고치지 않는다** — 이 스킬군 전체의 원칙(§ 아래)과 동일하게,
   결정적 코드가 이미 확정한 값은 건드리지 않고 UNKNOWN/unresolved라고 명시된 자리만
   보완한다.
4. 여전히 판정 불가능한 항목은 UNKNOWN으로 남겨두고 사유를 기록한다 — 모르면 모른다고
   출력하는 것이 도구의 원칙이다(창작 금지는 다음 단계에서도 동일하게 적용된다).
5. **판정이 만든 제약을 다음 단계로 넘겨라.** UNKNOWN을 INPUT으로 판정했다면, 그 필드는
   `unguarded[]`에 `semanticHint`만 달고 실려 있어도 **실제로는 가드된 필드**다(도구가 그 가드를
   그 필드에 잇지 못했을 뿐). triple-synthesis 단계에서 그 자리의 갭 마커를 채울 때는 —
   마커에 `guard:none`이라고 적혀 있더라도 — 여기서 읽은 실제 가드 조건을 만족시켜야 한다.
   그 조건을 `provenance-notes.md`에 한 줄로 명시해 두면 다음 단계에서 놓치지 않는다.

## 마커 계약(이 파이프라인 전체의 공통 원칙)

이 스킬(C1)은 갭 마커가 있는 산출물(body.json/seed.sql/stubs.json)을 직접 만들지 않는다 —
그건 다음 단계 **triple-synthesis**의 역할이다. 하지만 이 파이프라인 전체를 관통하는
원칙은 여기서부터 지킨다: **결정적 코드가 이미 확정한 값(guards의 origin·op·operand,
unguarded의 semanticHint)은 마커만 채워라 — 마커 아닌 값 수정 금지**와 동일한 규율로, 이미
해석된 필드를 임의로 고치지 않는다. 이후 단계(triple-synthesis)가 만드는 산출물에서는
이 원칙이 문자 그대로 `__AGENT_FILL__{...}` 갭 마커 위치에만 적용된다.

## 다음 단계

이 스킬의 산출물(`provenance-report.json`)이 준비되면 **triple-synthesis** 스킬로 넘어간다.
그 다음이 **trial-loop**다.

SUT가 인증(JWT 등)으로 보호돼 있다면 그 사실을 지금 확인해 두고 trial-loop에 그대로 넘겨라 —
`trial` CLI는 `--auth-login-path`/`--auth-user`/`--auth-pass`(+ `--auth-token-field`/
`--auth-header`/`--auth-scheme`)를 `build`와 같은 이름·시맨틱으로 받는다. 이 플래그를 빠뜨리면
후보 내용과 무관하게 전부 401/403이 되어 trial 판정 자체가 무의미해진다.

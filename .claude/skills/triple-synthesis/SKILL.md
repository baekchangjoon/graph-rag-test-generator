---
name: triple-synthesis
description: Synthesizes a guard-satisfying request triple (body.json / seed.sql / stubs.json / notes.md) per endpoint from a provenance-report.json via the graph-rag-builder `synthesize-triple` CLI subcommand, routing INPUT operands to body fields, DB_READ operands to seed rows, and EXTERNAL_RESPONSE operands to WireMock stub mappings. Use this second — after provenance-analysis and before trial-loop — to produce candidate triples whose undecidable values are marked with explicit __AGENT_FILL__ gap markers for an agent to fill.
---

# triple-synthesis (C2)

이 스킬은 삼중(triple) 합성 파이프라인의 **두 번째 단계(C2)**다. 실행 순서:
`provenance-analysis(C1) → triple-synthesis(C2) → trial-loop(C3)`.

## 선행 산출물 가드 — 반드시 먼저 확인하라

이 스킬은 **provenance-report.json이 필요하다**. 실행 전에 그 파일이 존재하는지 확인하라.

- **없으면**: 이 스킬을 실행하지 말고, 먼저 **provenance-analysis** 스킬을 실행해
  `provenance-report.json`을 만들어라. 그 산출물 없이는 이 스킬이 무엇을 채워야 할지
  판단할 근거(가드·origin)가 없다.
- **있으면**: 그대로 아래 절차를 진행한다.

## 결정적 코드가 하는 일 (에이전트가 재구현하지 말 것)

`synthesize-triple` CLI 서브커맨드가 provenance-report.json을 읽어 출처별로 삼중에
라우팅한다 — `INPUT` → `body.json` 필드, `DB_READ` → `seed.sql`, `EXTERNAL_RESPONSE` →
`stubs.json`(기존 external-stubs WireMock mapping 스키마 그대로). 가드를 만족하는 값(경계값,
관계 가드의 입력-시드 공동 배치 등)은 결정적 로직이 이미 계산해 넣는다. 후보 수는
기본 4개로 cap되고 우선순위 정렬은 결정적이다(`cand-01`이 최우선). 모든 결정값의 근거는
`notes.md`에 자동 생성된다.

`DERIVED`(산술·문자열 파생, 예: `score * 2`) 피연산자도 결정적 코드가 처리한다 — 리포트의
`derivedFrom`(그 파생식이 읽는 입력 필드 목록)에 대해 입력 오라클의 해를 body에 배치한다.
오라클을 붙이려면 아래 `--sut-jar`/`--sut-src`를 주면 된다. 해가 없는 자리는 갭 마커로 남는다.

## CLI 실행법

```
./gradlew -q :graph-rag-builder:run --args="synthesize-triple \
  --report <OUT_DIR>/provenance-report.json \
  --triple-store <TRIPLE_STORE_DIR> \
  --sut-jar <SUT_BOOT_JAR>"
```

플래그(실제 `BuilderCli` 소스 기준):

| 플래그 | 필수 | 기본값 | 설명 |
|---|---|---|---|
| `--report` | 예 | — | provenance-analysis(C1)가 만든 `provenance-report.json` 경로 |
| `--triple-store` | 예 | — | 후보 트리플을 쓸 루트 디렉토리(기본 위치는 SUT 캠페인의 `.graphrag/triples`) |
| `--sut-jar` | 아니오 | — | 입력 오라클용 SUT 부트 jar(ASM+Z3 concolic). `DERIVED` 파생 루트의 해를 여기서 도출한다 |
| `--sut-src` | 아니오 | — | 입력 오라클용 SUT 소스 루트(소스 리터럴 오라클). `build`와 동일한 멀티 루트 glob 문법 |
| `--sut-resources` | 아니오 | 각 루트의 형제 `resources` | `--sut-src`를 줄 때만 의미 있음 |

**오라클 플래그를 줄지 판단하는 법 — 기본은 "있으면 준다"다.** 오라클이 소비되는 자리는
**채움 슬롯 = `unguarded[]` 필드 + `DERIVED` 파생 루트**다. 따라서 판단 기준은 `DERIVED`
피연산자의 유무가 아니라 **채움 슬롯이 하나라도 있는지**다 — `unguarded[]`가 비어 있지 않으면
`DERIVED`가 0건이어도 오라클이 마커를 결정값으로 바꿔준다. (실측: `DERIVED` 0건 + `unguarded`
2건인 리포트에서 오라클을 붙이자 후보가 1개 → 4개로, 결정 필드가 0개 → 1개로 늘었다.)
`--sut-jar`는 소스에 리터럴로 없는 해(예: `score * 2 == 84` → `body.score = 42`)까지 풀고,
`--sut-src`는 소스 리터럴을 긁는다 — 둘 다 있으면 둘 다 줘라.

플래그를 생략하면 오라클 없이 동작하고, 각 후보의 `notes.md` 첫/끝 줄에 `input-oracle: none`이
남는다 — 그 줄이 보이면 "결정될 수 있었던 값이 마커로 남았을 수 있다"는 신호이므로, 부트 jar나
소스가 있으면 플래그를 붙여 다시 돌려라.

**오라클 결정값은 마커가 아니다.** 오라클은 경계 탐색을 위해 가드를 **위반하는** 값(예:
`amount = -1`, `0`)도 후보로 만든다. 그 자리는 마커가 아니므로 **고치면 안 된다** — 그런 후보가
trial에서 실패하는 것은 결함이 아니라 루프가 경계값을 소거하는 정상 동작이다. 네가 조정할 수
있는 것은 같은 후보 안의 **다른 마커**를 그 결정값과 모순 없게 채우는 것뿐이다(예: 가드가
`sum(lineItems.amount) == total`이면 `total` 마커를 그 후보의 `amount` 합에 맞춘다).

## 산출 레이아웃

```
<triple-store>/<endpointId>/
  provenance-report.json    # --report 입력의 사본 — 다음 단계 trial-loop이 읽는 규약 위치
  cand-01/ cand-02/ …       # 미승격 후보(순번 — cand-01이 최우선)
    body.json seed.sql stubs.json notes.md
  base/cand-01/ …           # 도구가 만든 원본 그대로의 사본 — 절대 건드리지 말 것
    body.json seed.sql stubs.json          (notes.md 없음)
```

`provenance-report.json` 사본은 이 CLI가 자동으로 만든다. trial-loop의 T1 게이트는 리포트가
**필수**인데 `--provenance-report` 없이 실행하면 이 규약 위치를 읽으므로, `--out`을 어느
경로로 잡았든 `provenance-analysis → triple-synthesis → trial-loop` 순서가 그대로 동작한다.

`base/cand-NN`은 다음 단계(trial-loop 및 최종 빌드의 T1 게이트)가 "에이전트가 마커 외에
무엇을 바꿨는지"를 diff로 판정하는 기준이다. **이 디렉토리는 절대 편집하지 마라** — 이걸
고치면 마커 계약 검증 자체가 무의미해진다.

## 갭 마커 계약 — 마커만 채워라

결정적 코드가 채울 수 없는 값(가드가 없는 자유 텍스트 필드 등)은 아티팩트별로 다음
문법의 마커로 표기돼 있다:

| 아티팩트 | 마커 표기 |
|---|---|
| `body.json` | 값 위치에 JSON 문자열 `"__AGENT_FILL__{type:.., semanticHint:.., guard:..}"` |
| `stubs.json` | `response.jsonBody` 안의 값 위치에 동일한 JSON 문자열 마커 |
| `seed.sql` | INSERT 값 리터럴 위치에 **작은따옴표 문자열 리터럴** `'__AGENT_FILL__{type:.., semanticHint:.., guard:..}'`(컬럼 타입과 무관하게 항상 문자열 리터럴 형태 — SQL 파싱이 깨지지 않게) |

**마커만 채워라. 마커 아닌 값은 절대 수정 금지.** 이 규칙은 프롬프트 권고가 아니라
기계 검증(T1)으로 강제된다:

- `body.json`/`stubs.json`은 `base/cand-NN`과 JSON 키 단위 구조 diff — 마커였던 위치의
  값만 바뀌었는지 확인한다. 그 외 키의 값이 하나라도 바뀌면 reject된다.
- `seed.sql`은 파서 레벨(테이블, 컬럼→값 구조)로 정규화 비교한다 — **마커 아닌 컬럼 값을
  바꾸는 것도 reject 대상**이다(DB_READ 채널의 값 충실도 보장). 텍스트 재포맷·주석 추가는
  허용된다.
- `notes.md`는 검사하지 않는다 — 근거·사유를 자유롭게 기록하는 용도다.

## 도구가 보장하는 body/stub 형상 (읽고 나서 어긋나면 결함으로 보고하라)

이 세 가지는 합성기의 불변식이다 — 산출물이 이를 어기면 마커를 채워도 통과할 수 없으니,
손으로 고치지 말고(마커 계약 위반) 결함으로 보고하라.

1. **컬렉션 필드는 JSON 배열이다.** `List<LineItem> lineItems`처럼 컬렉션인 필드는
   `{"lineItems":[{"sku":…,"amount":…}]}`처럼 **원소 1개짜리 배열**로 나온다(대표원소 규약).
   리포트의 `collectionPaths`가 그 근거다. 객체(`{"lineItems":{…}}`)로 보이면 결함이다.
2. **가드에 등장하는 INPUT 피연산자는 전부 body에 있다.** 결합 논리(`||`) 안이라 도구가 값을
   결정하지 못한 자리도 갭 마커로 남는다. 리포트의 어떤 가드가 읽는 필드가 body에 아예 없으면
   결함이다(단, 컨테이너 타입 피연산자 자신은 원소 필드가 대신하므로 없는 게 정상).
3. **`EXTERNAL_RESPONSE` 가드가 있으면 `stubs.json`이 비지 않는다.** 같은 callSite의 여러 응답
   필드는 한 mapping의 `response.jsonBody`에 병합되고, `Content-Type: application/json` 헤더가
   함께 등록된다. `EXTERNAL_RESPONSE` 피연산자가 있는데 `stubs.json`이 `{ }`이면 결함이다
   (그 상태로는 SUT가 stub되지 않은 실제 외부 호출을 내보내 5xx가 된다).

**알려진 갭 — 동적 키 Map body.** `@RequestBody Map<String,Integer>`처럼 요청 body 루트가 동적 키
Map인 핸들러는 현재 합성 범위 밖이다(키를 에이전트가 골라야 하는데, 마커 계약은 base/candidate의
키 집합이 같을 것을 요구한다). 이 경우 `notes.md`에 `경고(합성 불가)` 줄이 남는다 — 그 줄이 보이면
trial로 넘기지 말고 provenance 단계로 돌아가 판단하라.

## 값을 채울 때 지켜야 할 것

1. **semanticHint를 따른다** — 마커의 `semanticHint`(person-name/email/phone/free-text/
   none 등)에 맞는 값을 채운다.
2. **제약 위반 금지** — Bean Validation 애노테이션이나 리포트의 가드 조건을 위반하는 값을
   넣지 않는다(그 필드가 가드에 걸려 있었다면 애초에 마커가 아니라 결정값이 이미 채워져
   있었을 것이다 — 마커는 가드가 없는 자리에만 남는다).
3. **채운 값마다 사유 주석을 남긴다** — `notes.md`에 "어떤 마커를 왜 이 값으로 채웠는지"
   한 줄씩 추가한다.
4. **PII 금지 — 합성값만 쓴다.** 실존 인물 이름·실제 이메일·실제 전화번호 등 실데이터를
   절대 넣지 않는다. 항상 명백히 합성임을 알 수 있는 값(예: `"Test User"`,
   `"test-user@example.invalid"`)을 쓴다. 휴리스틱 검증기가 실데이터로 의심되는 값을
   발견하면 그 후보의 승격을 차단하고 사람 리뷰로 넘긴다 — 통과를 기대하지 마라.

## `unresolved` 항목이 남아 있다면

provenance-report.json에 여전히 `unresolved`/`UNKNOWN` 항목이 있는데 그게 이 엔드포인트의
happy path 완주에 필요한 가드라면, 이 스킬을 계속 진행하기보다 **provenance-analysis**로
돌아가 그 항목을 먼저 해소하는 편이 낫다 — 판정 근거 없이 값을 채우면 다음 단계(trial-loop)
에서 원인 불명의 실패로 되돌아올 뿐이다.

## 다음 단계

마커를 다 채우고 사유 주석을 남겼으면 **trial-loop** 스킬로 넘어가 후보를 실제로
시험(trial)한다. SUT가 인증으로 보호돼 있으면 `trial`에 `--auth-login-path`/`--auth-user`/
`--auth-pass`(+ `--auth-token-field`/`--auth-header`/`--auth-scheme`)를 함께 넘겨야 한다 —
`build`와 같은 이름·시맨틱이며, 빠뜨리면 후보가 아무리 정확해도 401/403으로 전부 실패한다.

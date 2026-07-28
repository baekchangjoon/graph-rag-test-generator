# 삼중 합성(Phase A) 수동 실증 절차서 — E2E-B1 / E2E-B2 / E2E-B3

- 작성일: 2026-07-27 (최종 갱신: 2026-07-28)
- 상태: **E2E-B1 실증 1회 실행 완료 — 판정 RED**(§"E2E-B1 실행 기록" 참조). E2E-B2/B3는 절차
  준비 완료·실증 미실행. 실행 과정에서 드러난 절차서 자체의 오류는 해당 절에 정정 표기와 함께
  반영했다.
- 관련 문서: [design spec §11.3](../specs/2026-07-26-agent-skill-triple-synthesis-design.md),
  [요구사항명세](../requirements/2026-07-26-agent-skill-triple-synthesis-requirements.md)
  (REQ-023, REQ-027, REQ-029, REQ-030), [docs/03](../../03-graph-rag-builder.md "삼중 합성 CLI 절"),
  [docs/26](../../26-attach-mode.md), [docs/coverage-progress.md](../../coverage-progress.md)
- 대상 스킬: `.claude/skills/{provenance-analysis,triple-synthesis,trial-loop}/SKILL.md`

이 문서는 CI 게이트 밖(design spec §11.3 "E2E — 수동/주기 실증")으로 분류된 3개 수용 기준
(E2E-B1/B2/B3, REQ-027/029/030)을 **누가 언제 실행하든 동일한 결과로 재현**할 수 있도록 실행
커맨드·판정 기준·기록 위치를 고정한다. 각 절차의 "판정 기준"은 요구사항명세 수용기준 문구를
그대로 옮긴 것이다(재해석 금지).

## 이 문서의 사용법

1. 아래 3개 절차 중 하나를 골라 "사전조건"부터 순서대로 따른다.
2. 절차 종료 시 "기록 양식"의 표를 채워 **이 문서의 "실행 기록" 절**(각 절차 항목 아래)에
   덧붙인다(덮어쓰지 않고 누적 — 여러 번 실행해도 이력이 남게).
3. 판정이 green이면 [요구사항명세](../requirements/2026-07-26-agent-skill-triple-synthesis-requirements.md)의
   해당 REQ-ID를 🟡(절차 준비)에서 🟢(green)로 전환하고, 추적 매트릭스 각주에 이 문서의 실행 기록
   섹션을 근거로 링크한다. E2E-B2는 추가로 `docs/coverage-progress.md`의 Phase A 자리(§ "Phase A —
   삼중 합성" 참조)에 실측치를 채운다.
4. 판정이 실패(reject/미해결)면 🔴로 유지하고 실패 사유를 실행 기록에 남긴다 — 조용히 넘어가지
   않는다(design spec D3 "무-fabrication" 원칙과 동일하게, 실증도 결과를 있는 그대로 기록한다).

---

## E2E-B1 — 에이전트 완주 실증 (REQ-027)

### 목적·수용기준 (요구사항명세 원문)

> Given fixture SUT와 스킬 3종, When 에이전트 세션이 세 스킬을 순서대로 수행, Then promoted가
> 생성되고 diff 검사에서 마커 외 변경이 없으며 절차·결과가 문서로 기록된다.

검증 레벨: manual (수동 실증 1회 기록 — CI 게이트 제외).

### 사전조건

- `samples/order-service`에 fixture EP 4종(fulfillment/transfers/invoices/quotas, REQ-028)이
  착륙돼 있어야 한다 — `git log --oneline
  -- samples/order-service/src/main/java/io/graphrag/sample/orders/FulfillmentController.java`로 확인.
  (fixture는 Task 18에서 `main`에 병합돼 있으므로 특정 wip 브랜치를 요구하지 않는다 — 초판은
  `worktree-agent-skill-triple-synthesis`를 명시했으나 그 브랜치 밖에서도 성립한다.)
- **대상 엔드포인트는 `post-api-transfers`를 제외한 나머지 3개 중 하나를 고른다**
  (`post-api-fulfillment` | `post-api-invoices` | `post-api-quotas`). `post-api-transfers`는
  이미 Task 18에서 **사람이** 갭필한 promoted 후보(`e2e/triples/post-api-transfers/`)가 커밋돼
  있어 "에이전트 주체의 완주"를 새로 실증하는 목적에 맞지 않는다 — 재사용하지 말 것. 이하 예시는
  `post-api-fulfillment`(INPUT+EXTERNAL_RESPONSE 가드 조합, DB 가드 없음)로 든다.
  - **주의(2026-07-28 실행에서 확인):** `post-api-fulfillment`는 예시로 부적절하다 — 가드가
    `EXTERNAL_RESPONSE`(`GET /carriers/policy`)에 걸려 있는데 `trial-loop` SKILL.md가 명시하듯
    독립 `trial` CLI 경로는 **stub 등록을 항상 skip**하므로, `stubs.json`을 아무리 정확히 채워도
    외부 호출이 unstubbed 상태로 나가 500이 된다. 이 절차서로 실증할 때는
    `post-api-invoices`/`post-api-quotas`처럼 외부 의존이 없는 EP를 고르는 편이 낫다.
- Docker 실행 중, `./gradlew :graph-rag-builder:classes` 로 빌더가 컴파일된 상태.
- SUT jar 빌드: `./gradlew :samples:order-service:bootJar`.
- **OTEL javaagent 배치(누락 시 app 컨테이너가 뜨지 않는다).** `e2e/docker-compose.yml`의 app
  서비스는 `./agents:/agents:ro`를 마운트하고 `-javaagent:/agents/otel-javaagent.jar`를 강제하는데,
  `e2e/agents/`는 `.gitignore` 대상이라 clone 직후에는 비어 있다. `:graph-rag-builder:classes`
  이후 아래로 채운다:
  ```bash
  mkdir -p e2e/agents
  cp graph-rag-builder/build/resources/main/agents/otel-javaagent.jar e2e/agents/
  ```
- 작업용 임시 디렉터리(커밋 대상 아님): `WORK=.work/e2e-b1-<endpointId>` — 이 디렉터리는
  `.gitignore` 대상(`.work/`)이라 실행 산출물이 실수로 커밋되지 않는다.
- **경로는 절대경로로 준다.** 아래 모든 `:graph-rag-builder:run` 커맨드의 `--args` 안 경로는
  Gradle `JavaExec`의 작업 디렉터리(=`graph-rag-builder/` 서브프로젝트 디렉터리) 기준으로
  해석된다. 저장소 루트 기준 상대경로를 쓰면 `--sut-src '...' matched no source directory`로
  즉시 실패한다. 실행 전에 `ROOT=$(git rev-parse --show-toplevel)`를 잡고
  `--sut-src $ROOT/samples/...`처럼 절대경로로 넘긴다(아래 예시는 가독성을 위해 상대경로로
  적었으므로 그대로 붙여넣지 말고 `$ROOT/`를 앞에 붙일 것).

### 절차

**1) SUT + DB 기동** (trial-loop이 실제 HTTP/DB에 붙어야 하므로 분석 환경이 아니라 **살아있는
SUT**가 필요하다 — `e2e/docker-compose.yml`의 `postgres`/`wiremock`/`app` 서비스를 그대로 쓴다):

```bash
docker compose -p grb-e2e-b1 -f e2e/docker-compose.yml up -d postgres wiremock app
# app이 /actuator/health로 뜰 때까지 대기(수십 초) 후 확인:
curl -sf http://localhost:58080/actuator/health
```

`app`은 `kafka`에 `depends_on`이 걸려 있어 위 커맨드는 **kafka 컨테이너도 함께 기동한다** —
정리(§완료 후 처리)는 project 단위(`-p grb-e2e-b1`)로 하므로 자동으로 함께 제거된다.

**2) provenance-analysis 스킬 실행** — `.claude/skills/provenance-analysis/SKILL.md`를 그대로
따른다:

```bash
mkdir -p "$WORK"
./gradlew -q :graph-rag-builder:run --args="provenance \
  --sut-src samples/order-service/src/main/java \
  --sut-resources samples/order-service/src/main/resources \
  --endpoint 'POST /api/fulfillment' \
  --out $WORK/provenance-report.json"
```

`provenance-report.json`의 `guards[]`/`unresolved[]`/`unguarded[]`를 스킬 절차대로 검토한다
(unresolved가 있으면 먼저 해소).

**3) triple-synthesis 스킬 실행** — `.claude/skills/triple-synthesis/SKILL.md`를 그대로 따른다:

```bash
./gradlew -q :graph-rag-builder:run --args="synthesize-triple \
  --report $WORK/provenance-report.json \
  --triple-store $WORK/triples"
```

산출된 `$WORK/triples/post-api-fulfillment/cand-01/{body.json,seed.sql,stubs.json,notes.md}`의
`__AGENT_FILL__{...}` 갭 마커를 스킬 절차(semanticHint 준수·PII 금지·notes.md 사유 기록)대로
채운다. **`base/cand-01/`는 절대 편집하지 않는다.**

**4) trial-loop 스킬 실행** — `.claude/skills/trial-loop/SKILL.md`의 루프 규율(성공 시 즉시 종료,
실패 시 `toolSuggestion` 우선 적용, 예산 소진까지 반복)을 따른다:

```bash
./gradlew -q :graph-rag-builder:run --args="trial \
  --endpoint post-api-fulfillment \
  --http-method POST --path /api/fulfillment \
  --sut-base-url http://localhost:58080 \
  --jdbc-url jdbc:postgresql://localhost:56432/app --db-user app --db-password app \
  --db-type postgres \
  --auth-login-path /api/auth/login --auth-user admin --auth-pass password \
  --triple-store $WORK/triples \
  --trial-budget 8"
```

> **`--auth-*`는 필수다(실행 #1의 차단 원인 1 수정 반영, 2026-07-28).** fixture SUT
> `samples/order-service`는 `/api/auth/**`·`/actuator/**`를 제외한 모든 경로가 JWT로 보호된다.
> `trial` CLI는 이제 `build`와 동일한 이름·시맨틱으로 `--auth-*`를 파싱해 로그인 토큰을 매 invoke에
> 붙인다(`BuilderCli.parseAuthConfig` 단일 소스). 이 플래그를 빼면 후보 내용과 무관하게 전부 403이
> 되어 판정이 무의미해진다 — 실행 #1이 RED로 끝난 원인 중 하나다.

종료 코드 0(성공, 즉시 `promoted/`로 이동)이 될 때까지 스킬의 digest 판독→값 수정→재실행 루프를
반복한다. 종료 코드 3(예산 소진)이면 `$WORK/triples/post-api-fulfillment/failed/digest-final.json`을
근거로 값을 고쳐 실패 후보를 최상위(`cand-NN`)로 되돌리고 다시 실행한다(스킬 문서의 "루프 규율"
그대로).

### 마커 외 변경 없음 — diff 검사 커맨드 (2단계: 즉시 확인 + 권위 있는 확인)

**(a) 즉시 확인(사람이 눈으로 훑는 1차 게이트)** — 승격된 후보와 `base/` 사본을 직접 diff한다:

```bash
for f in body.json seed.sql stubs.json; do
  echo "=== $f ==="
  diff -u "$WORK/triples/post-api-fulfillment/base/cand-01/$f" \
          "$WORK/triples/post-api-fulfillment/promoted/cand-01/$f" || true
done
```

**판정**: 위 diff에 나타나는 모든 변경 라인이 (구) `__AGENT_FILL__{...}` 마커였던 위치의 값
치환이어야 한다 — 마커가 아니었던 키/컬럼 값이 하나라도 바뀌었다면 이 단계에서 이미 실패로
기록한다(재작업 후 재실행).

**(b) 권위 있는 확인(T1 게이트 — `TripleValidator`의 실제 마커-diff 강제)**

> **정정(2026-07-28 E2E-B1 실행에서 확인).** 이 절의 초판은 "독립 `trial` CLI(T2) 경로는
> `TripleValidator`를 호출하지 않는다"고 적었으나 **사실이 아니다**. `BuilderCli.runTrial`은
> 각 후보에 대해 `runCandidate` 이전에
> `validator.validate(candDir, baseDir, report, BodyShape.empty())`를 호출하며(소스 주석의
> "C4 리뷰 Critical 3(a)" fix), 거부된 후보는 DB/HTTP를 전혀 건드리지 않고 `T1_REJECTED`
> 다이제스트와 함께 `failed/`로 간다. `trial-loop` SKILL.md의 §"T1 검증 게이트는 이 CLI에도
> 적용된다"가 옳고 이 절차서가 stale이었다. 따라서 **`trial` 실행 자체가 이미 마커-diff(REQ-009)
> 기계 검증을 수행한다** — `failed/digest-final.json`에 `T1_REJECTED`가 없으면 그 후보들은
> 마커-diff를 기계적으로 통과한 것이다.

이 경로에 남는 유일한 T1 갭은 `BodyShape.empty()`로 인한 **body 필드 스키마 검증 skip**이다
(마커-diff·seed 화이트리스트·PII·stub 스키마는 전부 적용). 그 갭까지 닫으려면 실제 `BodyShape`를
가진 통합 `build` 경로(`TriplePromotionGate.attempt` → `TripleValidator.validate`)를 1회 더
돌린다:

```bash
./gradlew -q :graph-rag-builder:run --args="build \
  --sut-src samples/order-service/src/main/java \
  --sut-resources samples/order-service/src/main/resources \
  --sut-jar samples/order-service/build/libs/order-service.jar \
  --sut-compose e2e/docker-compose.yml \
  --out $WORK/build \
  --sut-id order-service \
  --endpoint post-api-fulfillment \
  --external-stubs e2e/external-stubs \
  --sut-env EXTERNAL_INVENTORY_URL={{wiremock}} \
  --triple-candidates $WORK/triples \
  --commit-sha e2e-b1-manual"
```

**판정**: `$WORK/build/exploration-report.json`에서 대상 endpoint의 `tripleAdopted:true` +
`staleTriples`가 비어 있음 + 빌드 로그에 `"T1 재검증 실패"` 문자열이 없음. 이 3가지가 모두
확인되면 (a)의 눈 검사 결과가 기계 검증으로도 뒷받침된 것이다. `tripleAdopted:false`나
`staleTriples`에 항목이 있으면 T1이 실제로 reject한 것이므로 REQ-027 판정은 실패다(마커 외 값이
바뀌었거나 다른 사유 — 로그의 STALE 사유 문자열을 그대로 기록).

### 판정 기준 (요구사항명세 수용기준 재확인)

- `trial` CLI가 종료 코드 0을 반환하고 후보가 `$WORK/triples/<endpointId>/promoted/cand-NN/`로
  이동해 있다.
- 위 (a)+(b) diff 검사에서 마커 외 변경이 없음이 확인된다(사람 확인 + T1 기계 확인 이중 확인).
- 절차·결과가 아래 "기록 양식"으로 이 문서에 기록된다.

### 기록 양식

| 항목 | 값 |
|---|---|
| 실행일자 | |
| 대상 endpointId | |
| 실행 에이전트(모델·세션) | |
| 스킬 실행 순서 준수 여부 | provenance-analysis → triple-synthesis → trial-loop 순서 그대로 수행했는지 |
| trial 시도 횟수(성공까지) | |
| (a) 사람 diff 결과 | 마커 외 변경 없음 / 있음(상세) |
| (b) T1 기계 확인 결과 | tripleAdopted / staleTriples 값 |
| 최종 판정 | GREEN / RED(사유) |
| 산출물 보존 위치 | (WORK 디렉터리는 `.gitignore` 대상 — GREEN이면 `$WORK/triples/<endpointId>/{base,promoted}/cand-01`을 `e2e/triples/<endpointId>/`로 복사해 fixture로 커밋할지는 별도 PR 판단) |

### 완료 후 처리

GREEN 판정 시 요구사항명세 REQ-027 행을 🟡(절차 준비) → 🟢(done)로 바꾸고 위 표를 각주로 링크한다.
RED면 🔴 planned로 유지하고 실패 사유·재시도 계획을 기록한다.

### 실행 기록

> 이 절은 **누적**한다(덮어쓰지 않는다). 실행할 때마다 아래에 항목을 추가한다.

#### 실행 #1 — 2026-07-28 (RED)

| 항목 | 값 |
|---|---|
| 실행일자 | 2026-07-28 |
| 대상 endpointId | `post-api-invoices` (`POST /api/invoices`) — `post-api-quotas`/`post-api-fulfillment`도 대조로 확인 |
| 실행 에이전트(모델·세션) | Claude Opus 5 (Claude Code 서브에이전트), worktree `manual-evidence-and-drift` / 브랜치 `worktree-manual-evidence-and-drift` |
| 스킬 실행 순서 준수 여부 | **준수** — `provenance-analysis`(C1) → `triple-synthesis`(C2) → `trial-loop`(C3) 순서로 각 SKILL.md를 읽고 그 지시대로 수행 |
| trial 시도 횟수(성공까지) | **성공 없음.** `trial` 1회 호출로 후보 4개 전부 시도(budget 8, `attempts=4`), 전부 실패 → 종료 코드 **3** |
| (a) 사람 diff 결과 | **마커 외 변경 없음.** 후보 4개 × 3파일 전부 확인 — `body.json`은 `lineItems.sku`의 `__AGENT_FILL__{…}` 1줄만 `"SKU-TEST-0001"`로 치환, `seed.sql`/`stubs.json`은 diff 없음(빈 파일/`{ }` 그대로) |
| (b) T1 기계 확인 결과 | **마커-diff는 기계 통과.** `failed/digest-final.json`에 `T1_REJECTED` **0건**(4개 digest 전부 `outcomeKind: FAILURE`, `status: 403` — 즉 T1을 통과해 실제 invoke까지 갔다). `build` 경로 `tripleAdopted`/`staleTriples`는 **측정 불가** — promoted 후보가 만들어지지 않아 소비할 대상이 없다 |
| 최종 판정 | **RED** — 아래 차단 원인 2건. 어느 쪽도 마커 계약 안에서 수리 불가 |
| 산출물 보존 위치 | `.work/e2e-b1-post-api-invoices/`, `.work/e2e-b1-post-api-quotas/`, `.work/e2e-b1-post-api-fulfillment/`, `.work/e2e-b1-diag-transfers{,2}/` (전부 `.gitignore` 대상 — 커밋하지 않음) |

##### 차단 원인 1 — 독립 `trial` CLI에 인증 경로가 없다 (모든 invoke가 403)

`trial`은 후보 4개 전부에 대해 `status 403 Forbidden`을 받았다(`path: /api/invoices`,
`responseBody.error: "Forbidden"`). 원인은 트리플 내용이 아니라 **인증**이다:

- fixture SUT `samples/order-service`는 JWT로 보호된다 —
  `auth/SecurityConfig.java`가 `/api/auth/**`·`/actuator/**`·`/ws/**`·`/error`만 `permitAll()`이고
  나머지는 `anyRequest().authenticated()` + `JwtAuthFilter`.
- 그런데 `BuilderCli.runTrial`은 `EndpointExplorationRunner`를 **`RequestHeaders.empty()`로
  하드코딩**해 생성하고 `AuthTokenProvider`도 붙이지 않는다. `--auth-login-path`/`--auth-user`/
  `--auth-pass` 등 `AuthConfig` 플래그는 `BuilderCli` 안에서 **`build` 서브커맨드 경로에서만**
  파싱된다(`e2e/run-e2e.sh`가 build에 그 플래그를 넘겨 쓰는 것과 대조).
- 즉 **인증이 걸린 SUT에 대해서는 독립 `trial` CLI가 트리플 내용과 무관하게 항상 실패한다.**
  `trial-loop` SKILL.md도, 이 절차서도 이 제약을 언급하지 않는다.

수동 재현(토큰을 직접 붙이면 403이 사라진다 — 인증이 원인임의 대조 증거):

```bash
TOKEN=$(curl -s -X POST http://localhost:58080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"password"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
curl -s -w "\nHTTP %{http_code}\n" -X POST http://localhost:58080/api/invoices \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" \
  --data-binary @.work/e2e-b1-post-api-invoices/triples/post-api-invoices/failed/cand-03/body.json
# → HTTP 400 (403이 아님)
```

##### 차단 원인 2 — `synthesize-triple`의 body가 SUT DTO와 형상이 맞지 않는다

인증을 우회해 직접 호출해도 2xx가 나오지 않는다. 세 EP 모두 **도구가 생성한 body 자체**가
happy path를 만족시킬 수 없고, 그 수리는 마커가 아닌 자리를 고쳐야 하므로 마커 계약(REQ-009)
위반이 된다:

| endpointId | 생성된 body(마커 채운 후) | 인증 붙인 직접 호출 | 원인 |
|---|---|---|---|
| `post-api-invoices` | `{"lineItems":{"sku":"SKU-TEST-0001","amount":1}}` | **HTTP 400** | DTO는 `List<LineItem> lineItems`인데 **배열이 아니라 객체**로 생성됨. 또한 가드 `sum != req.total()`의 INPUT 피연산자 `total`이 **body에서 통째로 누락** |
| `post-api-quotas` | `{ }` | **HTTP 422** | 가드가 `quotas.isEmpty()`인데 빈 body 생성. **갭 마커가 0개**라 에이전트가 채울 자리조차 없다 |
| `post-api-fulfillment` | `{"carrierCode":"CR-TEST-01"}` | **HTTP 500** | 가드 `req.parcelWeight() > policy.maxWeight()`의 INPUT 피연산자 `parcelWeight`가 body에서 누락. `stubs.json`은 `EXTERNAL_RESPONSE` 피연산자 2개(`allowedPrefix`/`maxWeight`)가 있는데도 `{ }`로 비어 있고, 독립 `trial` CLI는 stub 등록을 항상 skip하므로 외부 호출이 unstubbed로 나가 500 |

`post-api-invoices`는 `--sut-jar`/`--sut-src` 오라클을 붙여 후보 4개(`amount` = -1/0/1/2)를
얻었으나 형상 문제는 그대로였다. 오라클 없이 돌리면 후보가 1개로 줄고 `amount`가 마커로
남을 뿐, 배열/`total` 문제는 동일하다.

##### 부수 발견 — `e2e/triples/post-api-transfers` fixture는 현행 도구 출력과 다르다

커밋된 `base/cand-01`이 "도구가 만든 원본"이라는 전제(마커-diff의 기준선)가 성립하는지 확인하려
그 fixture의 **자기 자신의** `provenance-report.json`으로 `synthesize-triple`을 재실행해 대조했다:

```
committed base/cand-01/body.json : {"fromAccountId":"seed-fromaccountid","amount":1,"note":<marker>,"items":[{"sku":<marker>,"qty":<marker>}]}
재생성  base/cand-01/body.json    : {"amount":100,"note":<marker>,"items":{"sku":<marker>,"qty":<marker>}}
committed base/cand-01/seed.sql  : INSERT INTO fund_accounts (id, balance_amount) VALUES ('seed-fromaccountid', 1);
재생성  base/cand-01/seed.sql     : INSERT INTO fund_accounts (balance_amount) VALUES (100);
```

`items`가 배열↔객체로 다르고, `EXISTS` 가드의 INPUT 피연산자 `fromAccountId`가 재생성본에는
없다. 즉 **커밋된 fixture의 `base/`는 현행 `synthesize-triple`이 만들 수 없는 산출물**이며
(a17a8cc "promoted 부트스트랩"), 도구 출력의 증거로 쓸 수 없다. 차단 원인 2가 transfers에도
동일하게 존재함을 뒤집어 보여주는 관측이기도 하다.

##### 판정 근거 요약 (수용기준 대조)

| 수용기준 | 결과 |
|---|---|
| promoted가 생성된다 | ❌ `promoted/` 디렉터리 미생성, `trial` 종료 코드 3 |
| diff 검사에서 마커 외 변경이 없다 | ✅ (a) 사람 diff·(b) T1 기계 게이트 모두 통과 — 단 승격이 없어 "승격된 후보의" diff는 아니다 |
| 절차·결과가 문서로 기록된다 | ✅ 이 절 |

3개 중 1개(promoted 생성)가 미충족이므로 **REQ-027은 🔴**. 조용히 넘어가지 않는다.

##### 재시도 계획 (선행 수정 필요 — 절차 재실행만으로는 GREEN 불가)

1. **`trial` CLI에 인증 배선 추가** — `build` 경로와 동일한 `--auth-*` 플래그(또는
   `--request-headers-file`)를 `runTrial`에서도 파싱해 `AuthTokenProvider`/`RequestHeaders`로
   넘긴다. 이것 없이는 인증된 SUT 전반에서 이 스킬 파이프라인이 동작하지 않는다.
2. **`synthesize-triple`의 body 형상 수정** — (a) 컬렉션 필드(`List<T>`)를 JSON 배열로 생성,
   (b) 가드에 쓰인 INPUT 피연산자(`total`, `parcelWeight`)를 body에 반드시 포함,
   (c) `EXTERNAL_RESPONSE` 피연산자가 있으면 `stubs.json`에 해당 mapping을 생성.
3. 위 2건 수정 후 이 절차를 그대로 재실행하고 "실행 #2"로 누적 기록한다.

---

## E2E-B2 — petclinic 커버리지 실측 (REQ-029)

### 목적·수용기준 (요구사항명세 원문)

> Given 동일 petclinic jar, When 현행 vs Phase A 빌드 A/B, Then coveredAppBranches가 **145 대비
> 순증**하거나, 순증하지 않으면 **원인 분석이 coverage-progress.md에 첨부**된 경우에만 green으로
> 판정한다(무조건 기록=green 금지).

검증 레벨: manual (주기 실증). 측정 방식은 `docs/coverage-progress.md` "측정 방식"과 동일 원칙
(동일 jar, 도구 버전만 A/B) — 이 절차는 그 원칙을 이번 Phase A 변경에 적용한다.

### 사전조건

- petclinic(REST fork) 체크아웃 + jar 빌드 — `docs/coverage-progress.md` §측정 방식,
  `e2e/sweep-petclinic-cross-class.sh` 헤더 주석과 동일 전제:
  ```bash
  export PETCLINIC_ROOT=~/github_spring-petclinic/spring-petclinic   # 기본값, env로 override 가능
  cd "$PETCLINIC_ROOT" && ./gradlew bootJar   # 또는 ./mvnw package -DskipTests
  ```
- Docker 실행 중(Testcontainers로 DB 기동), `./gradlew :graph-rag-builder:classes`.
- **"동일 jar"** 원칙: A(현행)/B(Phase A) 두 실행 모두 **같은 petclinic jar**를 가리켜야 한다 —
  변하는 것은 오직 graph-rag-builder 도구 버전(A) 또는 트리플 게이트 활성/비활성(B) 뿐이다.

### 절차 — A(현행 baseline)

REQ-022(회귀 0, ablation)에 따르면 `GRB_TRIAL=off`이거나 `--triple-candidates` 미지정 시
Phase A 도구는 현행과 정규화-동등이다. 따라서 별도로 `main` 브랜치를 체크아웃하지 않고, **이
브랜치에서 `--triple-candidates`를 주지 않은 채** 돌리면 A(baseline)와 동등하다(이 동등성 자체는
`TrialAblationE2E#REQ-022`로 이미 CI 회귀화돼 있다 — 재확인 삼아 `GRB_TRIAL=off`를 명시해도 무방):

```bash
GRB_TRIAL=off ./gradlew -q :graph-rag-builder:run --args="build \
  --sut-src \$PETCLINIC_ROOT/src/main/java \
  --sut-resources \$PETCLINIC_ROOT/src/main/resources \
  --sut-jar \$(ls \$PETCLINIC_ROOT/build/libs/spring-petclinic-*.jar | head -1) \
  --out .work/e2e-b2-baseline \
  --sut-id petclinic \
  --sut-compose \$PETCLINIC_ROOT/docker-compose.yml --db-service postgres \
  --auth-login-path /api/auth/login --auth-user admin --auth-pass password \
  --budget-requests 60 --trace-mode none \
  --commit-sha e2e-b2-baseline"
python3 -c "import json; print(json.load(open('.work/e2e-b2-baseline/exploration-report.json'))['coveredAppBranches'])"
```

기대값: **145**(±소폭, `docs/coverage-progress.md` 표 "**(a)** 클래스레벨 path 변수 역추출" 행과
동일 baseline). 145와 크게 다르면 petclinic 버전/jar가 이전 측정과 다르다는 신호이므로, 먼저
그 차이를 원인 분석에 기록하고 진행한다.

### 절차 — B(Phase A: 트리플 후보 투입)

145/253 잔여 108개 분기 중 **삼중 합성으로 열릴 가능성이 있는 가드**(다중 가드 순차 조건 —
`docs/25` §9 "비선형·interprocedural·집계·상태 의존")를 대상으로 provenance→synthesize-triple→
trial-loop을 petclinic에 적용한다. 대상 선정 절차:

1. A 실행의 `.work/e2e-b2-baseline/exploration-report.json`에서 각 엔드포인트의
   `missedBranches[]`(classFqn+line)를 훑어, **DB 상태 비교 + 외부 응답 비교처럼 다중 가드가
   순차로 겹치는 지점**을 1~3개 고른다(petclinic은 외부 HTTP 호출이 없으므로 대부분 INPUT+DB_READ
   조합이 된다 — `e2e/sweep-petclinic-cross-class.sh`가 이미 이런 분석 패턴의 선례다).
2. 고른 각 엔드포인트에 대해 E2E-B1과 동일한 3단계(provenance → synthesize-triple → trial-loop)를
   petclinic 대상으로 수행한다(`--sut-src $PETCLINIC_ROOT/...`, `--sut-base-url` 은 petclinic
   compose가 올린 포트, `--jdbc-url`은 petclinic postgres 포트). 산출 트리플 저장 위치는
   `.work/e2e-b2-triples/`(커밋 대상 아님 — petclinic은 이 repo의 fixture가 아니므로 promoted
   사본을 repo에 넣지 않는다).
3. 승격된 후보가 하나 이상 생기면, 그 저장소를 가리켜 B(Phase A) 빌드를 돌린다:

```bash
./gradlew -q :graph-rag-builder:run --args="build \
  --sut-src \$PETCLINIC_ROOT/src/main/java \
  --sut-resources \$PETCLINIC_ROOT/src/main/resources \
  --sut-jar \$(ls \$PETCLINIC_ROOT/build/libs/spring-petclinic-*.jar | head -1) \
  --out .work/e2e-b2-phaseA \
  --sut-id petclinic \
  --sut-compose \$PETCLINIC_ROOT/docker-compose.yml --db-service postgres \
  --auth-login-path /api/auth/login --auth-user admin --auth-pass password \
  --budget-requests 60 --trace-mode none \
  --triple-candidates .work/e2e-b2-triples \
  --commit-sha e2e-b2-phaseA"
python3 -c "import json; print(json.load(open('.work/e2e-b2-phaseA/exploration-report.json'))['coveredAppBranches'])"
```

**동일 jar 검증**: A/B 두 실행에 쓴 `--sut-jar` 경로/체크섬(`sha256sum`)이 같음을 실행 기록에
남긴다 — 다르면 "동일 jar A/B"가 아니므로 판정 자체가 무효다.

### 판정 기준 (요구사항명세 수용기준 재확인, 무조건 기록=green 금지)

- `coveredAppBranches(B) > 145` → **green**, 상승폭을 `docs/coverage-progress.md`에 기록.
- `coveredAppBranches(B) <= 145`(순증 없음) → 원인 분석을 반드시 첨부해야만 **green**으로 판정한다
  (예: "대상 엔드포인트의 잔여 가드는 집계/비선형이라 이번 Phase A 메커니즘의 적용 범위 밖" 처럼
  구체적 근거를 남긴다). 원인 분석 없이 "순증 없음"만 기록하면 **RED**로 남긴다(요구사항명세
  "무조건 기록=green 금지" 문구 그대로).

### 기록 양식

| 항목 | 값 |
|---|---|
| 실행일자 | |
| petclinic jar 경로 + sha256 | |
| A(baseline) coveredAppBranches | |
| B(Phase A) coveredAppBranches | |
| Δ(B-A) | |
| 대상으로 고른 엔드포인트/가드 | |
| 승격된 트리플 후보 수 | |
| 판정 | GREEN(순증) / GREEN(원인분석 첨부) / RED |
| 원인 분석(순증 없을 때 필수) | |

### 완료 후 처리

GREEN이면 `docs/coverage-progress.md`의 "Phase A — 삼중 합성" 절(아래 문서 동기화 참조)에 이 표를
반영하고 추이 그래프에 새 점을 추가하며, 요구사항명세 REQ-029를 🟡 → 🟢로 전환한다. RED면 🔴로
유지하고 재시도 계획을 남긴다.

---

## E2E-B3 — attach 경계 수동 확인 (REQ-030)

### 목적·수용기준 (요구사항명세 원문)

> Given 실 SUT attach 구성, When 플래그 조합별 시도, Then REQ-023 동작이 실 환경에서 재현됨을
> 기록한다.

참고로 REQ-023 자체의 원문 수용기준: "Given attach 구성에서 플래그 0개/1개/2개, When trial 시도,
Then 0·1개는 seed 미적용·사유 기록, 2개일 때만 적용된다." E2E-B3는 이를 **CI(AttachSeedGateIT,
이미 🟢)가 아니라 실 attach 환경에서** 재확인하는 것이 목적이다. 검증 레벨: manual. 스텁은
attach 미지원(design spec §8, REQ-025로 CI 검증됨)이므로 이 절차의 범위 밖이다.

### 중요 — 표준 `trial` CLI가 아니라 `build --attach`를 써야 한다

`BuilderCli.runTrial`(독립 `trial` 서브커맨드)이 만드는 `TrialRunner`는 6-arg 생성자를 써서
**`attachMode=false`로 고정**돼 있다 — REQ-023/024 게이트는 `trial` 단독 CLI 경로에서는 애초에
활성화되지 않는다. attach 이중 opt-in(`--attach-allow-seed`/`--confirm-non-production`)이 실제로
`TrialRunner`에 전달되는 경로는 `build` 서브커맨드 → `EndpointExplorationRunner` →
`TriplePromotionGate` 뿐이다(코드 근거: `BuilderCli` 148~164행의 `AttachConfig` 생성, `--attach`
존재 시에만 두 플래그를 읽음). 따라서 이 절차는 **`build --attach --triple-candidates <dir>
[--attach-allow-seed] [--confirm-non-production]`** 조합으로 수행한다.

### 사전조건

- "실 SUT"로 이 repo의 `samples/order-service` + `e2e/docker-compose.yml`을 attach 대상으로
  쓴다(이 repo에서 구할 수 있는 가장 현실적인 대리물 — 진짜 외부 고객 SUT가 있다면 그쪽으로
  반복 실행하는 편이 요구사항의 취지에 더 가깝다. 그 경우 아래 명령의 `--sut-compose`/
  `--app-service`/포트만 그 SUT에 맞게 바꾼다).
- attach 대상 엔드포인트는 seed.sql이 비어 있지 않은 `post-api-transfers`를 쓴다 — 이미 커밋된
  `e2e/triples/post-api-transfers/{base,promoted}/cand-01`(REQ-018 fixture)를 그대로 재사용해
  "seed 적용 여부"를 관측 가능하게 한다.
- SUT app 이미지 빌드: `./gradlew :samples:order-service:bootJar && docker compose -p
  grb-e2e-b3 -f e2e/docker-compose.yml build app`.

### 절차 — 4개 플래그 조합을 각각 독립 실행

공통 커맨드 골격(`e2e/run-attach-e2e.sh` 패턴과 동일 attach 배선):

```bash
FLAGS="$1"   # 아래 표의 값을 그대로 넣어 4회 반복 실행
PROJECT="grb-e2e-b3"
trap 'docker compose -p "$PROJECT" -f e2e/docker-compose.yml -f .work/e2e-b3/work/attach-override.yml down -v >/dev/null 2>&1 || true' EXIT
rm -rf .work/e2e-b3; mkdir -p .work/e2e-b3
./gradlew -q :graph-rag-builder:run --args="build \
  --sut-src samples/order-service/src/main/java \
  --sut-resources samples/order-service/src/main/resources \
  --sut-jar samples/order-service/build/libs/order-service.jar \
  --sut-compose e2e/docker-compose.yml \
  --out .work/e2e-b3 --sut-id order \
  --attach --app-service app --app-port 58080 --coverage-port 16300 \
  --jdbc-url jdbc:postgresql://localhost:56432/app --db-service postgres \
  --auth-login-path /api/auth/login --auth-user admin --auth-pass password \
  --external-stubs e2e/external-stubs \
  --sut-env EXTERNAL_INVENTORY_URL={{wiremock}},EXTERNAL_FRAUD_URL={{wiremock}} \
  --triple-candidates e2e/triples \
  --endpoint post-api-transfers \
  $FLAGS"
```

4회 반복 — `$FLAGS`에 아래 표의 값을 순서대로 넣는다(매 회 위 `trap`이 스택을 내리므로 실행 간
DB 상태는 초기화된다):

| # | `$FLAGS` (플래그 개수) | REQ-023 기대 동작 |
|---|---|---|
| 1 | `` (0개) | seed 미적용, 사유에 `--attach-allow-seed`·`--confirm-non-production` 둘 다 누락으로 지목 |
| 2 | `--attach-allow-seed` (1개) | seed 미적용, 사유에 `--confirm-non-production`만 누락으로 지목 |
| 3 | `--confirm-non-production` (1개) | seed 미적용, 사유에 `--attach-allow-seed`만 누락으로 지목 |
| 4 | `--attach-allow-seed --confirm-non-production` (2개) | seed 적용됨 — 후보가 promoted로 이동(또는 REQ-024/025 경로대로 처리) |

### 판정 기준 — 어디서 확인하나

- **사유/게이트 로그**: 빌더 표준출력에서 `attach seed gate closed for post-api-transfers
  (REQ-023, candidate skipped)` 로그 라인(`TrialRunner.runCandidate`가 남김) — 누락 플래그 목록이
  `TrialRunner.attachSeedGateReason()` 형식(`missing: --attach-allow-seed, --confirm-non-production`
  등)으로 정확히 일치하는지 확인한다.
- **산출물**: `.work/e2e-b3/exploration-report.json`의 대상 endpoint `tripleRejected`/
  `staleTriples`(0~3번 조합에서 attach seed gate 사유로 채워짐) vs `tripleAdopted:true`(4번
  조합, seed 실제 적용 후 승격됐을 때만).
- 4번 조합에서 만약 attach 역-DELETE 실패(REQ-024)나 EXTERNAL_RESPONSE stub skip(REQ-025)이
  함께 관측되면 그것도 그대로 기록한다(이 절차의 목적은 REQ-023 하나지만, 같은 실행에서 함께
  드러나는 attach 안전 경계 동작은 부수적으로 기록해 둔다).

### 기록 양식

| # | 플래그 조합 | 관측된 게이트 로그(누락 플래그 목록) | seed 적용 여부 | exploration-report 필드 | 판정 |
|---|---|---|---|---|---|
| 1 | 0개 | | 미적용 | | GREEN/RED |
| 2 | allow-seed만 | | 미적용 | | GREEN/RED |
| 3 | confirm만 | | 미적용 | | GREEN/RED |
| 4 | 둘 다 | | 적용 | | GREEN/RED |

### 완료 후 처리

4개 조합 모두 GREEN이면 REQ-030을 🟡 → 🟢로 전환. 하나라도 RED면(실 환경에서 이중 opt-in이
CI(AttachSeedGateIT)와 다르게 동작) 🔴로 유지하고 코드/문서 불일치를 별도 이슈로 남긴다.

---

## 부록 — 왜 이 3개는 CI가 아니라 수동인가

design spec §10(Phase 경계)·§11.3이 명시한 이유를 그대로 옮긴다: E2E-B1은 **에이전트 주체**의
실제 완주(창작·판단 품질)를 실증하는 것이 목적이라 결정적 CI로 대체할 수 없다(에이전트 없이도
파이프라인이 완주된다는 것은 E2E-A3/REQ-018이 이미 CI로 보장한다). E2E-B2는 **외부 petclinic
체크아웃**(이 repo의 CI가 접근하지 않는 별도 리포지토리)에 대한 실측이라 CI 대상이 아니다.
E2E-B3는 **attach egress 라우팅 미구현**(design spec §8, Phase C 백로그)으로 실 SUT 종류에 따라
결과가 달라질 수 있는 영역이라 반복 가능한 단일 CI로 고정하기 어렵다.

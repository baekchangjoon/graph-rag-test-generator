# 삼중 합성(Phase A) 수동 실증 절차서 — E2E-B1 / E2E-B2 / E2E-B3

- 작성일: 2026-07-27 (최종 갱신: 2026-07-28)
- 상태: **E2E-B1 실증 2회 실행 — 실행 #1 RED, 실행 #2 GREEN**(§"E2E-B1 실행 기록" 참조).
  실행 #1이 지목한 두 차단 원인을 코드로 고친 뒤(`fa2f45a`, `07d8ced`) 재실행해 REQ-027이 🟢가
  됐다. **E2E-B2 실증 1회 실행 — RED**(§"E2E-B2 실행 기록"): 머신 과부하로 A/B 빌드가 3회 모두
  SUT 부팅 실패했고, 그와 별개로 petclinic에서는 승격 가능한 트리플 후보를 **구조적으로 만들 수
  없음**이 정적으로 확정됐다(순증 없음이 아니라 **효과 미측정**). E2E-B3는 절차 준비 완료·실증
  미실행. 실행 과정에서 드러난 절차서 자체의 오류는 해당 절에 정정 표기와 함께 반영했다.
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
> 기계 검증을 수행한다** — 실패 후보 어디에도 `T1_REJECTED`가 없으면 그 후보들은 마커-diff를
> 기계적으로 통과한 것이다.
>
> **증거 파일은 종료 코드에 따라 다르다(2026-07-28 실행 #2에서 확인).** `trial`이 **종료 코드 3**
> (전부 실패)으로 끝났을 때만 `failed/digest-final.json`(이번 호출의 digest 배열)이 쓰인다.
> **종료 코드 0**(어떤 후보가 승격)으로 끝나면 그 파일은 생성되지 않고, 앞서 실패한 후보들에
> 후보별 `failed/cand-NN/digest.txt`만 남는다. 그러므로 확인은 두 경로를 모두 덮는
> `grep -rl T1_REJECTED "$WORK/triples/<endpointId>/failed/" | wc -l` → `0` 으로 한다.

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
  --auth-login-path /api/auth/login --auth-user admin --auth-pass password \
  --triple-candidates $WORK/triples \
  --commit-sha e2e-b1-manual"
```

> **`--auth-*`는 여기서도 필수다(실행 #2에서 확인, 2026-07-28).** fixture SUT는 JWT 보호이므로
> 이 플래그 없이는 대상 EP가 2xx에 도달하지 못해 `tripleAdopted` 판정 자체가 무의미해진다
> (`e2e/run-e2e.sh`의 `build` 호출도 같은 플래그를 넘긴다). 초판 커맨드에는 빠져 있었다.
> 반대로 `run-e2e.sh`에 있는 `--with-kafka`는 여기서 **넣지 마라** — 대상 EP가 Kafka를 쓰지 않고,
> `apache/kafka` Testcontainer 기동 실패(ARM Mac)로 빌드가 통째로 죽는다.

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

##### 선행 수정 반영 상태 (2026-07-28)

위 1·2번은 **수정 완료**다(RED→GREEN 회귀 테스트 동반). 재실행(실행 #2) 전에 알아둘 변경점:

| 항목 | 수정 내용 | 회귀 테스트 |
|---|---|---|
| 차단 원인 1 | `BuilderCli.parseAuthConfig`/`parseRequestHeaders`를 `build`에서 추출해 `runTrial`에 배선. 절차서의 `trial` 커맨드에 `--auth-*`가 추가됐다(위 참조) | `TrialCliE2E` REQ-013 2건(대조군 403 / 배선 후 승격) |
| 차단 원인 2(a) 배열 | `ProvenanceReport.collectionPaths[]` 신설 + 합성이 그 접두사를 JSON 배열(대표원소 1개)로 생성 | `TripleSynthesizerIT` REQ-005 2건, `ProvenanceIndexerIT` REQ-005 |
| 차단 원인 2(b) 가드 피연산자 | 가드의 INPUT 피연산자를 전부 body 슬롯으로 보장(`total`/`parcelWeight`). 컨테이너 타입 피연산자는 제외 | `TripleSynthesizerIT` REQ-005, `FixtureTripleShapeE2E` |
| 차단 원인 2(c) stub | 모든 `EXTERNAL_RESPONSE` 피연산자에 stub 자리 확보 + 같은 callSite 병합 + `Content-Type` 헤더 | `TripleSynthesizerIT` REQ-008, `FixtureTripleShapeE2E` |
| 부수 결함 | 컨테이너/스칼라 라이브러리 메서드(`List.isEmpty()`)가 만들던 유령 dot-path(`items.empty`) 제거 — golden 리포트도 갱신 | `ProvenanceIndexerIT` REQ-001 |

**남은 갭(수정하지 않음, 근거와 함께 유지):**

- **`post-api-quotas`는 여전히 합성 불가.** `@RequestBody Map<String,Integer>`는 요청 body 루트가
  동적 키 Map이라 에이전트가 **키**를 골라야 하는데, 마커 계약(REQ-009)은 base/candidate의 키 집합이
  동일할 것을 요구하므로 "키 자리 마커"를 표현할 수 없다. 억지 우회 대신 `notes.md`에
  `경고(합성 불가)` 줄을 남기도록 바꿨다(조용한 빈 body 금지). 이 EP로는 E2E-B1을 재실행하지 마라.
- **`post-api-fulfillment`는 독립 `trial` CLI로 검증 불가.** 그 CLI는 `HttpCaptureServer`를 띄우지
  않아 stub 등록을 항상 skip한다(`docs/03` "주의" 참조) — 이제 `stubs.json`이 올바르게 채워지지만
  등록되지 않으므로 외부 호출이 unstubbed로 나간다. 이 EP의 삼중은 `build --triple-candidates`
  경로(T3)로 검증해야 한다.
- **DB 가드가 있는 EP의 seed 공동 배치는 `synthesize-triple` CLI에서 여전히 불완전하다.** 이 CLI는
  물리 스키마를 받을 경로가 없어 `tables=[]`로 호출하므로(`runSynthesizeTriple` Javadoc), EXISTS/
  비교 가드의 DB 쪽 값은 배치되지 않는다(INPUT 쪽은 이제 갭 마커로 남는다). `post-api-transfers`의
  커밋된 fixture가 현행 도구로 재현되지 않는 "부수 발견"의 잔여 원인이 이것이다.

**따라서 실행 #2의 대상 EP는 `post-api-invoices`를 권한다** — 외부 의존도 DB 가드도 없고, 형상
결함이 모두 해소돼 마커(`total`, `lineItems.sku`)만 채우면 완주 가능한 형태다.

#### 실행 #2 — 2026-07-28 (GREEN)

| 항목 | 값 |
|---|---|
| 실행일자 | 2026-07-28 |
| 대상 endpointId | `post-api-invoices` (`POST /api/invoices`) |
| 실행 에이전트(모델·세션) | Claude Opus 5 (Claude Code 서브에이전트), worktree `manual-evidence-and-drift` / 브랜치 `worktree-manual-evidence-and-drift` |
| 스킬 실행 순서 준수 여부 | **준수** — `provenance-analysis`(C1) → `triple-synthesis`(C2) → `trial-loop`(C3). 세 SKILL.md를 먼저 읽고 그 지시대로만 진행했다(1차 수정 과정의 사전 지식으로 지름길을 타지 않음) |
| trial 시도 횟수(성공까지) | **T2 1회 호출 / 후보 3개 시도**(budget 8) — `cand-01`(amount=-1) 422, `cand-02`(amount=0) 422, **`cand-03`(amount=1) → HTTP 201 promoted, 즉시 종료**. 실패 2건은 결함이 아니라 오라클이 준 경계값 후보를 루프가 소거하는 정상 동작이다. 이후 잔여 `cand-04`로 종료 코드를 명시 캡처하는 확인용 T2 호출 1회 추가(→ `cand-04`도 201 promoted, `TRIAL_EXIT=0`) |
| (a) 사람 diff 결과 | **마커 외 변경 없음.** `base/cand-03` vs `promoted/cand-03`: 키 집합 동일, 변경된 키는 마커였던 2개(`lineItems[0].sku`, `total`)뿐, `seed.sql`/`stubs.json`은 바이트 동일. `cand-04`도 동일 결과 |
| (b) T1 기계 확인 결과 | **`tripleAdopted: true`, `staleTriples: []`, 빌드 로그에 `"T1 재검증 실패"` 0건** — 3조건 모두 충족. `trial` 경로에서도 `T1_REJECTED` 0건 |
| 최종 판정 | **GREEN** — 수용기준 3항목 전부 충족 |
| 산출물 보존 위치 | `.work/e2e-b1-run2-post-api-invoices/`(`.gitignore` 대상 — 커밋하지 않음). 하위: `provenance-report.json`, `provenance-notes.md`, `triples-nooracle/`(오라클 없이 돌린 1차 합성), `triples/`(오라클 포함, 승격본 포함), `build/exploration-report.json` |

##### 대상 EP 선택 이유

`post-api-transfers`는 절차서가 제외한다(사람이 갭필한 fixture 재사용 금지). 남은 3개 중:

- **`post-api-quotas`** — `@RequestBody Map<String,Integer>`(동적 키 Map 루트)라 마커 계약(REQ-009,
  base/candidate 키 집합 동일)이 "키 자리 마커"를 표현할 수 없다. 구조적 한계로 합성 불가(위 §남은 갭 1).
- **`post-api-fulfillment`** — 가드가 `EXTERNAL_RESPONSE`(`GET /carriers/policy`)에 걸려 있는데
  독립 `trial` CLI는 stub 등록을 항상 skip한다(`trial-loop` SKILL.md §결정적 코드가 하는 일).
  `stubs.json`이 옳게 채워져도 이 경로로는 검증되지 않는다(위 §남은 갭 2).
- **`post-api-invoices`** — 외부 의존 없음, DB 가드 없음(`seed.sql` 빈 파일), 가드 3개가 전부
  INPUT/파생이라 마커만으로 완주 가능. **선택.**

##### 실제 실행 커맨드와 관측

**C1 provenance** (`ROOT=$(git rev-parse --show-toplevel)`, `WORK=$ROOT/.work/e2e-b1-run2-post-api-invoices`):

```
provenance --sut-src $ROOT/samples/order-service/src/main/java \
  --sut-resources $ROOT/samples/order-service/src/main/resources \
  --endpoint 'POST /api/invoices' --out $WORK/provenance-report.json
→ provenance report for post-api-invoices: 3 guard(s), 0 unresolved(s)
→ collectionPaths: ["lineItems"]   # 형상 불변식 1의 근거가 실려 있다
```

`unresolved`는 0건이지만 guards 안에 `origin:"UNKNOWN"` 피연산자가 4개 있어, 스킬 §"unresolved /
UNKNOWN 항목 처리 절차"대로 소스를 직접 열어 판정하고 `$WORK/provenance-notes.md`에 근거를 남겼다
(리포트 자체는 수정하지 않음). 판정 요약: `null` 리터럴 1건(출처 없음, UNKNOWN 유지), `isEmpty()`
결과 1건(DERIVED from INPUT `lineItems`), `li.amount()` 1건(**INPUT** `lineItems.amount` — 루프 변수
↔ 컬렉션 원소 체인 미해소), `sum` 1건(DERIVED 집계). 이 판정에서 **`lineItems.amount`는
`unguarded[]`에 실려 있지만 실제로는 가드된다**는 제약이 나왔고, 그에 따라 마커를
`amount > 0 && sum == total`을 만족하도록 채웠다.

**C2 synthesize-triple** — 스킬의 오라클 결정 규칙("리포트에 `DERIVED` 피연산자가 있으면 `--sut-jar`")을
글자 그대로 적용하면 **이 리포트에는 `DERIVED`가 0건**이므로 오라클 없이 돌리게 된다. 그렇게 먼저
돌린 결과(`$WORK/triples-nooracle/`)는 후보 **1개**, 마커 3개(`sku`/`amount`/`total`)였고 `notes.md`
끝줄에 `input-oracle: none`이 남았다. 스킬이 그 줄을 "부트 jar가 있으면 붙여 다시 돌리는 편이 낫다"는
신호로 규정하므로 재실행:

```
synthesize-triple --report $WORK/provenance-report.json --triple-store $WORK/triples \
  --sut-jar $ROOT/samples/order-service/build/libs/order-service.jar \
  --sut-src $ROOT/samples/order-service/src/main/java \
  --sut-resources $ROOT/samples/order-service/src/main/resources
→ 4 candidate(s), input-oracle: static-literal + concolic-asm-z3 -> numeric 15 field(s), strings 5
→ cand-01..04의 lineItems[0].amount = -1 / 0 / 1 / 2 (오라클 결정값 = 마커 아님)
```

형상 불변식 3종 확인: ① `lineItems`가 **배열**(`[{...}]`) ✅ ② 가드 INPUT 피연산자 `total`이 body에
존재 ✅(컨테이너 `lineItems` 자신은 제외가 정상) ③ `EXTERNAL_RESPONSE` 피연산자 0개이므로
`stubs.json`이 `{ }`인 것은 결함 아님 ✅.

갭필: `lineItems[0].sku` → `"SKU-TEST-000N"`(free-text, 가드 없음, 합성값·PII 아님), `total` →
그 후보의 `amount` 합(가드 `sum != total` 만족). `amount`는 마커가 아니므로 건드리지 않았다 —
그래서 `cand-01`/`cand-02`는 `amount<=0` 가드에 걸릴 것이 예상됐고 실제로 그렇게 됐다. 채운 사유는
각 후보 `notes.md`에 append했다.

**C3 trial** — SUT는 JWT 보호이므로 스킬 지시대로 `--auth-*`를 넘겼다:

```
trial --endpoint post-api-invoices --http-method POST --path /api/invoices \
  --sut-base-url http://localhost:58080 \
  --jdbc-url jdbc:postgresql://localhost:56432/app --db-user app --db-password app --db-type postgres \
  --auth-login-path /api/auth/login --auth-user admin --auth-pass password \
  --triple-store $WORK/triples --trial-budget 8
→ trial: auth wired (login /api/auth/login as admin, header Authorization) — invoke에 토큰이 붙는다
→ cand-01 failed (status 422) / cand-02 failed (status 422)
→ cand-03 promoted -> …/promoted/cand-03 (status 201)
```

`failed/cand-01/digest.txt`의 `mappedGuard`가 `<=@InvoiceController.java:23`으로 정확히 역매핑됐다
(`toolSuggestion: null` — 스킬이 명시한 대로 제안 산출 조건(`DB_READ` 피연산자 NUMERIC 비교)에
해당하지 않는다). **403은 한 건도 없다** — 실행 #1의 차단 원인 1이 해소됐음이 여기서 확인된다.

##### (a) 마커-diff 사람 확인 — 커맨드와 출력

절차서 §(a)의 `diff -u`는 통과하나 갭필 시 JSON 재포맷이 섞여 눈으로 읽기 나쁘다. T1이 실제로 보는
것은 **JSON 키 단위 구조 diff**이므로 그와 같은 의미로 다시 검사했다(둘 다 수행):

```
$ python3 (base vs promoted 평탄화 키 비교)
--- cand-03 body.json key-level diff ---
 key sets identical: True
  CHANGED lineItems[0].sku: '__AGENT_FILL__{type:String, semanticHint:free-text, guard:none}' -> 'SKU-TEST-0003'  (base was marker: True)
  CHANGED total: '__AGENT_FILL__{type:int, semanticHint:none, guard:!= at InvoiceController.java:28}' -> 1  (base was marker: True)
  non-marker changes: NONE
  seed.sql byte-identical: True
  stubs.json byte-identical: True
--- cand-04 body.json key-level diff ---
 key sets identical: True
  CHANGED lineItems[0].sku: … -> 'SKU-TEST-0004'  (base was marker: True)
  CHANGED total: … -> 2  (base was marker: True)
  non-marker changes: NONE
  seed.sql byte-identical: True
  stubs.json byte-identical: True

$ grep -rl T1_REJECTED failed/ | wc -l
0
```

##### (b) T1 기계 확인 — 커맨드와 출력

```
build --sut-src … --sut-resources … --sut-jar … --sut-compose $ROOT/e2e/docker-compose.yml \
  --out $WORK/build --sut-id order-service --endpoint post-api-invoices \
  --external-stubs $ROOT/e2e/external-stubs \
  --sut-env EXTERNAL_INVENTORY_URL={{wiremock}},EXTERNAL_FRAUD_URL={{wiremock}} \
  --auth-login-path /api/auth/login --auth-user admin --auth-pass password \
  --triple-candidates $WORK/triples --commit-sha e2e-b1-manual-run2
→ BUILD_EXIT=0
→ triple adopted for post-api-invoices (REQ-018): …/triples/post-api-invoices/promoted/cand-03

$ python3 (exploration-report.json에서 대상 EP 추출)
{"endpointId": "post-api-invoices", "tripleAdopted": true, "tripleRejected": {}, "staleTriples": []}
$ grep -c "T1 재검증 실패" build.log   → 0
$ grep -ic "stale" build.log          → 0
```

##### 판정 근거 요약 (수용기준 대조)

| 수용기준 | 결과 |
|---|---|
| promoted가 생성된다 | ✅ `promoted/cand-03`(HTTP 201), 확인용 2회차에서 `promoted/cand-04`도 201·`TRIAL_EXIT=0` |
| diff 검사에서 마커 외 변경이 없다 | ✅ (a) 키 단위 diff에서 `non-marker changes: NONE`, `seed.sql`/`stubs.json` 바이트 동일 + (b) T1 기계 게이트 `tripleAdopted:true`/`staleTriples:[]`/`T1 재검증 실패` 0건 |
| 절차·결과가 문서로 기록된다 | ✅ 이 절 |

3개 전부 충족이므로 **REQ-027은 🟢**. 실행 #1의 차단 원인 2건(인증 배선 부재 → 전부 403 / body 형상
결함 → 400)은 각각 `fa2f45a`·`07d8ced`로 해소됐고, 이번 실행에서 403이 0건이고 배열 형상
`lineItems:[{…}]`이 그대로 관측돼 해소가 실측으로 확인됐다.

##### 이번 실행에서 새로 드러난 문서 문제 (해당 문서를 이 커밋에서 정정)

1. **`provenance-analysis` SKILL.md §"unresolved / UNKNOWN 항목 처리 절차"** — 절 제목은
   "unresolved / UNKNOWN"인데 1번 단계가 "`unresolved` 목록의 각 항목에 대해"로만 적혀 있다. 이번
   리포트는 `unresolved: []`이면서 guards 안에 UNKNOWN 피연산자가 4개였고, 절차를 글자 그대로
   따르면 그 4개를 건너뛰게 된다. 게다가 UNKNOWN 피연산자에는 개별 소스 위치 필드가 없어 소속
   가드의 `at`을 위치 근거로 써야 한다는 점도 적혀 있지 않다. → 두 사실을 스킬에 명시.
2. **`triple-synthesis` SKILL.md §"오라클 플래그를 줄지 판단하는 법"** — "리포트에 `DERIVED`
   피연산자가 있으면 `--sut-jar`를 줘라"는 규칙만으로는 이번 케이스를 못 잡는다. 이 리포트의
   `DERIVED` 피연산자는 **0건**인데(집계 `sum`이 UNKNOWN으로 남았다) 오라클을 붙이자 후보가
   1개→4개, 결정 필드가 0→1개로 늘었다. 실제로 오라클이 의미 있는 조건은 "`DERIVED`가 있을 때"가
   아니라 "**채울 슬롯(unguarded + DERIVED 파생 루트)이 하나라도 있을 때**"다. → 규칙 문구를 정정.
3. **이 절차서 §(b)의 `build` 커맨드에 `--auth-*`가 없다.** fixture SUT는 JWT 보호이므로 그대로
   붙여넣으면 대상 EP가 2xx에 도달하지 못한다(`e2e/run-e2e.sh`는 이 플래그를 넘긴다). → 커맨드 정정.
4. **이 절차서 §(b)가 `failed/digest-final.json`을 T1_REJECTED 증거원으로 지목**하지만, 그 파일은
   `trial`이 **종료 코드 3**으로 끝났을 때만 쓰인다. GREEN(종료 코드 0) 경로에서는 실패 후보에
   후보별 `digest.txt`만 남고 `digest-final.json`은 생성되지 않는다. → 두 경로를 구분해 표기.

##### 막히지는 않았으나 기록해 둘 마찰 (문서 수정 없이 관측만)

- **`--with-kafka`를 붙이면 이 머신에서 `build`가 실패한다.** `e2e/run-e2e.sh`를 따라 처음에
  `--with-kafka`를 넣었더니 `apache/kafka:3.8.0` Testcontainer가 exit 126(`Timed out waiting for log
  output matching '.*Transitioning from RECOVERY to RUNNING.*'`)로 죽어 빌드가 실패했다. `invoices`
  EP는 Kafka를 쓰지 않으므로 플래그를 빼고 재실행해 통과했다. 절차서 §(b)에는 원래 이 플래그가
  없으므로 문서 결함은 아니다(ARM Mac 환경 이슈, `TrialSeedMySqlExecutableCommentIT`의 mysql
  타임아웃과 같은 부류).
- **zsh에서 `${PIPESTATUS[0]}`가 비어 나온다**(zsh는 `$pipestatus`). 종료 코드를 증거로 남기려면
  파이프 없이 `> log 2>&1; echo $?` 형태로 캡처해야 한다. 문서 문제는 아니고 실행 요령이다.

##### 자원 정리

compose는 고유 project name `grb-e2e-b1-run2`로 띄우고 `down -v --remove-orphans`로 내렸다.
`build` 경로의 Testcontainers는 Ryuk이 회수한다. 종료 후 확인:

```
docker ps -a --filter name=grb-e2e-b1-run2 | wc -l   → 0
docker network ls --filter name=grb-e2e-b1-run2 -q   → 0
docker volume ls --filter name=grb-e2e-b1-run2 -q    → 0
docker ps        (전체)                               → 0건
docker ps -a --filter status=exited (전체)            → 0건
```

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
- **한산한 머신(2026-07-28 실행 #1에서 확인된 필수 사전조건).** `SutProcess.BOOT_TIMEOUT`이
  `Duration.ofSeconds(90)` **하드코딩 상수이고 CLI 오버라이드가 없다.** petclinic은 otel +
  pjacoco javaagent 2종을 달고 뜨므로 한산한 머신에서도 부팅에 수십 초가 걸리고, 부하가 걸리면
  이 데드라인을 넘겨 `SUT did not become healthy in PT1M30S`로 A/B가 **시작조차 못 한다**.
  실행 전에 `uptime`의 load average가 코어 수(`sysctl -n hw.ncpu`)를 크게 넘지 않는지 확인하라.

### 절차 — A(현행 baseline)

REQ-022(회귀 0, ablation)에 따르면 `GRB_TRIAL=off`이거나 `--triple-candidates` 미지정 시
Phase A 도구는 현행과 정규화-동등이다. 따라서 별도로 `main` 브랜치를 체크아웃하지 않고, **이
브랜치에서 `--triple-candidates`를 주지 않은 채** 돌리면 A(baseline)와 동등하다(이 동등성 자체는
`TrialAblationE2E#REQ-022`로 이미 CI 회귀화돼 있다 — 재확인 삼아 `GRB_TRIAL=off`를 명시해도 무방):

> **정정(2026-07-28 실행 #1). `GRB_TRIAL=off`는 Gradle 경유로 전달된다는 보장이 없다.**
> `EndpointExplorationRunner`는 이 스위치를 `System.getenv`로 읽는데, Gradle `JavaExec`가 포크하는
> JVM은 **호출 셸이 아니라 (장수명) Gradle 데몬 프로세스의 환경**을 상속한다. 따라서
> `GRB_TRIAL=off ./gradlew ...`는 데몬이 그 변수 없이 이미 떠 있으면 무시된다. 확실한 방법은
> **빈 디렉터리를 `--triple-candidates`로 지정**하는 것이다(후보 부재 → `TriplePromotionGate`가
> 부작용 0으로 NO_CANDIDATE 처리 — `BuilderCli` 286행 주석의 REQ-036 계약). 아래 커맨드는 두
> 방법을 함께 쓴다.

```bash
GRB_TRIAL=off ./gradlew -q :graph-rag-builder:run --args="build \
  --sut-src \$PETCLINIC_ROOT/src/main/java \
  --sut-resources \$PETCLINIC_ROOT/src/main/resources \
  --sut-jar \$(ls \$PETCLINIC_ROOT/build/libs/spring-petclinic-*.jar | head -1) \
  --out .work/e2e-b2-baseline \
  --triple-candidates .work/e2e-b2/empty-triples \
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
- **A/B를 실행하지 못했거나 승격 후보가 0개면 → 무조건 RED**(2026-07-28 실행 #1에서 추가).
  위 두 분기는 **A/B가 실제로 실행됐음을 전제**한다. ① 빌드가 실패해 수치를 못 얻었거나,
  ② §절차 B의 "승격된 후보가 하나 이상 생기면"이 성립하지 않아 B에 투입할 트리플이 0개면,
  그것은 "순증 없음"이 아니라 **효과 미측정**이다. 원인 분석을 붙이더라도 green으로 올리지
  않는다 — 원인 분석 첨부는 "순증 없음" 분기에만 붙는 조건이지, 미실측을 green으로 바꾸는
  조건이 아니다. 이 경우 기록에 **"순증 없음"이 아니라 "미측정"임을 명시**해 다음 세션이
  "효과 없음"으로 오독하지 않게 한다.

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

### 실행 기록

> 이 절은 **누적**한다(덮어쓰지 않는다). 실행할 때마다 아래에 항목을 추가한다.

#### 실행 #1 — 2026-07-28 (RED — A/B 실측 미실시 + 후보 생성 구조적 불가)

| 항목 | 값 |
|---|---|
| 실행일자 | 2026-07-28 |
| petclinic jar 경로 + sha256 | `~/github_spring-petclinic/spring-petclinic/build/libs/spring-petclinic-4.0.0-SNAPSHOT.jar` / `28e8cea2075203371e2b09a2879441df0cf14847362e40d5d7b34c5d29921ab0` (2026-06-16 빌드, HEAD `fe81280`와 동일 소스 — jar가 마지막 커밋보다 나중이라 stale 아님) |
| A(baseline) coveredAppBranches | **미측정** — 빌드 3회 연속 실패(`SUT did not become healthy in PT1M30S`). 아래 §차단 원인 1 |
| B(Phase A) coveredAppBranches | **미측정** — A가 실패했고, 애초에 투입할 promoted 후보를 만들 수 없었다(아래 §차단 원인 2) |
| Δ(B-A) | **산출 불가**(두 값 모두 미측정) |
| 대상으로 고른 엔드포인트/가드 | `POST /api/reservations`(가드 11개: INPUT 범위검사 9 + 비선형 `deposit*1.1 < nights*rate` + DB `countByRoomNumberAndStatus >= 2`), `PUT /api/reservations/{id}`(가드 7개, `DB_READ` 피연산자 2개 — Phase A가 노리는 INPUT×DB_READ 조합에 가장 근접한 EP) |
| 승격된 트리플 후보 수 | **0개** — 두 EP 모두 `trial`까지 가지 못한다(구조적으로 승격 불가, §차단 원인 2) |
| 판정 | **RED** |
| 원인 분석(순증 없을 때 필수) | 아래 §차단 원인 1·2. **"순증 없음"이 아니라 "실측 미실시 + 후보 생성 불가"다** — 효과가 없다고 읽으면 오독이다(§판정 근거 참조) |

##### 측정 설계 — 무엇을 A/B로 나눴나

Phase A의 삼중 합성은 **커밋된 promoted 후보를 소비**하는 구조이므로, 후보 없이 A/B를 돌리면
B는 A와 같을 수밖에 없다(게이트 미발화). 그래서 이번 실증은 두 층으로 나눠 설계했다:

1. **후보 없는 A/B** — Phase A 코드가 기존 경로를 회귀시키지 않는지(REQ-022 회귀 0의 실사용 확인).
   동일해야 정상이다. → **A 자체가 부팅 실패로 미측정**(차단 원인 1).
2. **후보를 만든 뒤의 A/B** — petclinic의 막힌 EP에 스킬 파이프라인(provenance → synthesize-triple
   → trial)을 적용해 promoted를 만들고, 그것을 `--triple-candidates`로 넘긴 빌드와 안 넘긴 빌드를
   비교. **이것이 이 기능의 실제 효과 측정이다.** → **후보를 만들 수 없어 미실시**(차단 원인 2).

2번이 불가능함은 **머신 부하와 무관하게 정적으로 확정**됐다(아래). 즉 차단 원인 1이 해소돼도
2번은 그대로 막혀 있다.

> **A(baseline)의 ablation 배선 주의.** 절차서 본문은 `GRB_TRIAL=off`를 예시로 들지만, Gradle
> `JavaExec`가 포크하는 JVM의 환경변수는 **호출 셸이 아니라 Gradle 데몬 프로세스의 환경**을
> 상속하므로 `GRB_TRIAL=off ./gradlew ...`가 전달된다는 보장이 없다. 이번 실행은 그 대신
> **빈 디렉터리를 `--triple-candidates`로 지정**해 게이트를 확실히 NO_CANDIDATE로 만들었다
> (`TriplePromotionGate`가 후보 부재 시 부작용 0으로 빠지는 계약 — `BuilderCli` 286행 주석).
> 두 방법 모두 REQ-022 동등이지만 후자가 전달 경로 불확실성이 없다. → 절차서 정정 대상(§4).

##### 차단 원인 1 — 머신 과부하로 SUT가 하드코딩된 90초 health timeout 안에 못 뜬다

`build` 3회(11:50 / 11:54 / 12:12) 전부 동일 실패:

```
Exception in thread "main" java.lang.IllegalStateException: SUT did not become healthy in PT1M30S
	at io.graphrag.builder.env.SutProcess.awaitHealthy(SutProcess.java:104)
```

SUT는 죽은 게 아니라 **느릴 뿐**이다 — `.work/e2e-b2-baseline/work/sut.log` 기준 3회차는
프로세스 기동 12:13:22 → Spring `main` 진입 12:14:19(**javaagent 2종 부착에만 57초**) →
JPA repository 스캔 12:14:49로, 90초 데드라인(12:14:52) 시점에 아직 컨텍스트 초기화 중이었다.
1회차에는 데드라인 이후 12:53:10에 HikariPool까지 정상 기동한 로그가 남아 있다.

원인은 도구가 아니라 **머신 상태**다:

| 관측 | 값 |
|---|---|
| CPU 코어 | 10 (`sysctl hw.ncpu`) |
| load average | 217 ~ 410 (측정 구간 내내) |
| 원인 프로세스 | `~/.codegraph/versions/v1.0.0/node … codegraph.js init` **41개**, 다수가 CPU 30~90% 점유 |
| 그 프로세스들의 수명 | 최장 **13일**, 상당수 1일 이상 — 이번 실증이 만든 것이 아닌 **기존 상주 프로세스** |

**이 프로세스들은 종료하지 않았다.** 테스트 자원 정리 규칙상 "테스트가 띄운 것"만 정리 대상이고
공유·장수명 프로세스는 건드리지 않는다. 종료 여부는 결정 사항이므로 위임 체인을 그대로 밟았다:
① secretary 인박스(`e6490fa6b4674e9691d7522c7c84bee3.request.json`) → **300초 폴링 타임아웃**
(`.timeout` 파일 기록), ② `consult-secretary` 스킬 → **CLI 크래시**
(`llm_client.py`: `RuntimeError: brain returned non-JSON`), ③ → **safe_default 적용**(종료 금지,
BLOCKED 기록). 무한 대기하지 않았다.

**도구 측 결함도 함께 드러났다(별도 기록 대상):** `SutProcess.BOOT_TIMEOUT`은
`Duration.ofSeconds(90)` **하드코딩 상수이고 CLI 오버라이드 플래그가 없다**. 즉 이 절차서의
E2E-B2는 부하가 걸린 머신에서는 **원리적으로 실행할 수 없다** — 절차서가 사전조건으로 "한산한
머신"을 요구하지 않는 것도 문서 갭이다(§이번 실행에서 새로 드러난 문서 문제 3).

##### 차단 원인 2 — petclinic에는 승격 가능한 트리플 후보를 만들 수 없다 (부하와 무관, 정적 확정)

이쪽은 SUT 부팅이 필요 없는 정적 경로(`provenance` + `synthesize-triple`)라 과부하 상태에서도
끝까지 실행됐다. 결론부터: **두 EP 모두 마커 계약 안에서는 2xx에 도달할 수 없는 후보만 나온다.**

**(a) `POST /api/reservations` — 가드 11개, `unresolved: 0`, 그러나 피연산자 origin이 전멸**

```
provenance --endpoint 'POST /api/reservations'
→ provenance report for post-api-reservations: 11 guard(s), 0 unresolved(s)
```

`unresolved`는 0건인데, 11개 가드의 피연산자 중 **INPUT으로 판정된 것은 컨트롤러 자신의
`request == null` 1건뿐이고 나머지는 전부 `UNKNOWN`**이다. `req.nights()`/`req.roomNumber()`/
`req.priceTier()` 등은 `service.create(request)`로 **한 단계 넘어간 서비스 메서드의 파라미터**인데,
그 전파에서 origin이 소실된다. DB 가드
(`repository.countByRoomNumberAndStatus(...) >= ROOM_CAPACITY`, `ReservationService.java:76`)도
`DB_READ`가 아니라 `UNKNOWN×UNKNOWN`으로 나온다. (E2E-B1 실행 #2가 이미 지적한
"`unresolved: []`여도 UNKNOWN 피연산자가 남는다"는 함정의 극단 사례다.)

그 결과 `synthesize-triple`이 낸 후보는 **1개, 결정 필드 0/11**이고 body 형상에 결함이 3개 있다:

| # | 결함 | 근거 |
|---|---|---|
| A | **enum을 중첩 객체로 생성.** `priceTier`는 `enum PriceTier{BASIC,PREMIUM,VIP}`인데 body가 `"priceTier":{"nightlyRate":<marker>}` — enum의 파생 getter를 필드로 착각했다. Jackson이 enum에 객체를 매핑할 수 없어 **400 확정** | `PriceTier.java:22`, `cand-01/body.json` |
| B | **유령 필드.** 컨트롤러 메서드 **파라미터명**을 딴 `"request"` 키가 body에 생성된다(`CreateReservationRequest`에 그런 필드는 없다). Spring 기본 설정이 unknown property를 무시해 이번엔 무해했으나, 실제 `BodyShape`를 가진 통합 `build` 경로의 T1은 이를 잡아야 정상이다 | `cand-01/body.json` |
| C | **가드 표기 누락.** `checkInDate` 마커가 `guard:none`인데 실제로는 `!checkInDate.isAfter(now)` 가드가 있다(`ReservationService.java:70`). 마커 설명만 믿고 채우면 422가 된다 | `cand-01/body.json` vs 소스 |
| D | **오라클 산출물 전량 폐기.** `notes.md`에 `unguarded 조합 수가 안전 상한(4096)을 초과 — 오라클 변주 생략, 갭 마커 단일 조합으로 폴백`. 오라클이 numeric 13 + strings 3 필드의 해를 실제로 구해 놓고도(`InputCandidates 오라클 16개 필드 후보 보유`) unguarded가 10개라 조합 폭발 상한에 걸려 **하나도 쓰이지 않았다** | `cand-01/notes.md` |

**실증(대조 실험).** 결함 A가 실제 차단 원인임을 추론이 아니라 관측으로 고정했다. petclinic을
h2 프로파일로 단독 기동(javaagent 없음 → 24초에 healthy)해 두 body를 같은 토큰으로 POST했다 —
**값은 완전히 동일하고 형상만 다르다**:

```
(1) 합성 후보, 마커만 채움(키 집합 base와 동일 — 계약 준수)
    {"petName":"Test Pet","ownerEmail":"test-owner@example.invalid","promoCode":"WELCOME10",
     "roomNumber":200,"nights":3,"animalCount":2,"loyaltyPoints":600,
     "priceTier":{"nightlyRate":150.0},"depositAmount":500.0,
     "checkInDate":"2027-01-01","request":"unused"}
    → HTTP 400  {"status":400,"error":"Bad Request","path":"/api/reservations"}

(2) 손으로 형상만 고친 대조군(priceTier를 enum 문자열로, 유령 request 제거)
    {… 동일 값 …, "priceTier":"VIP", …}
    → HTTP 201  {"id":6,…,"priceTier":"VIP","loyaltyPoints":700,"status":"PENDING","promoApplied":true}
```

즉 **엔드포인트는 2xx에 도달 가능하고, 도달하지 못하는 것은 합성기의 형상**이다. 그리고
(2)로 가려면 `priceTier`를 객체→문자열로 바꾸고 `request` 키를 지워야 하는데, 둘 다
**마커 위치의 값 치환이 아니라 키 집합·구조 변경**이라 마커 계약(REQ-009) 위반이다 —
에이전트가 계약 안에서 수리할 수 없다.

**(b) `PUT /api/reservations/{id}` — `DB_READ`는 잡히지만 짝이 되는 INPUT이 UNKNOWN**

```
provenance --endpoint 'PUT /api/reservations/{id}'
→ 7 guard(s), 0 unresolved(s)
```

이 EP는 `DB_READ` 피연산자가 2개 잡힌다(`ReservationService.java:128`의 `existing.getStatus()`,
`:138`의 `countByRoomNumberAndStatus`). **Phase A가 노리는 INPUT×DB_READ 조합에 가장 근접한
지점**이다. 그런데 짝이 되는 반대편 피연산자(`req.status()`, `req.nights()`)가 전부 `UNKNOWN`이라
라우팅 조건(`INPUT×(DB_READ|EXTERNAL_RESPONSE)`)이 성립하지 않는다. 산출된 후보 4개는:

| 결함 | 관측 |
|---|---|
| **`@PathVariable`을 body에 배치** | 후보 4개 전부 `"id": -1`. `id`는 경로 변수라 body로 전달되지 않고, 게다가 `-1`은 `id <= 0` 가드 위반값이다. **마커가 아니라 오라클 결정값**이라 고칠 수도 없다 |
| **by-id EP인데 seed가 빈 파일** | 후보 4개 전부 `seed.sql`이 비어 있다. 갱신 대상 행이 없으므로 `findById(...).orElseThrow()`에서 404 확정 |
| **유령 `request` 필드** | (a)의 결함 B가 그대로 재현 |
| 후보 간 차이 | `nights`만 0/1/29/2로 변주(오라클 경계값). 나머지는 동일 |

**(c) 공통 근본 원인 — notes.md가 스스로 밝힌다**

두 EP의 `notes.md`가 **모든 가드**를 미지원으로 표기한다:

```
op '||' at ReservationService.java:49 — 결합 논리/미지원 가드 라우팅은 후속 task 범위(확장 지점)
op '<'  at ReservationService.java:73 — INPUT×(DB_READ|EXTERNAL_RESPONSE) 조합이 아닌 비교 가드는 미지원(확장 지점)
op '>=' at ReservationService.java:76 — INPUT×(DB_READ|EXTERNAL_RESPONSE) 조합이 아닌 비교 가드는 미지원(확장 지점)
…  (POST 11개 / PUT 7개, 전부 동일 유형의 "확장 지점")
```

정리하면 petclinic의 잔여 가드는 **① `||`/`&&`로 결합된 INPUT 단독 범위검사가 압도적**이고,
**② 컨트롤러 → 서비스로 한 단계 넘어간 파라미터에서 origin이 UNKNOWN으로 소실**된다. Phase A
삼중 합성이 여는 대상은 `INPUT×DB_READ` / `INPUT×EXTERNAL_RESPONSE` **교차 가드**인데,
petclinic에서는 그 조합이 정적으로 인식조차 되지 않는다. 이것이 "petclinic에서 이번 기능의
효과를 측정할 수 없는" 구조적 이유다.

##### 판정 근거 요약 (수용기준 대조)

수용기준: *"coveredAppBranches가 145 대비 순증하거나, 순증하지 않으면 원인 분석이
coverage-progress.md에 첨부된 경우에만 green으로 판정한다(무조건 기록=green 금지)."*

| 수용기준 요소 | 결과 |
|---|---|
| 동일 jar A/B 실행 | ❌ **미실시** — A가 3회 모두 SUT 부팅 실패, B는 투입할 후보 자체가 없음 |
| `coveredAppBranches` 순증 | ❌ 판정 불가 — 두 값 모두 미측정 |
| 순증 없을 때의 원인 분석 첨부 | ⚠️ 원인 분석은 첨부했으나(이 절 + `coverage-progress.md`), 그 원인은 **"순증하지 않았다"가 아니라 "측정하지 못했다"**이다 |

수용기준의 green 분기(순증 / 순증 없음+원인분석)는 **A/B가 실행됐음을 전제**한다. 이번엔 그
전제가 성립하지 않으므로 **어느 분기에도 해당하지 않는다 → REQ-029는 🔴**. "원인 분석을 적었으니
green"으로 처리하는 것은 수용기준이 명시적으로 금지한 "무조건 기록=green"에 해당한다.

**오독 방지 — 이 RED는 "Phase A가 효과 없음"을 뜻하지 않는다.** 이번 실증이 확정한 것은
"**petclinic으로는 이 기능의 효과를 측정할 수 없다**"이지 "효과가 없다"가 아니다. Phase A의
기능 자체는 `samples/order-service`에서 E2E-B1 실행 #2(REQ-027 🟢)로 실제 완주가 확인돼 있다.
petclinic은 **벤치마크로서 이 기능의 적용 범위 밖**이라는 것이 이번 관측의 내용이다.

##### 재시도 계획 (절차 재실행만으로는 GREEN 불가 — 선행 수정 필요)

1. **[환경]** 머신이 한산할 때(load < 코어 수) 재실행한다. 겸해서 `SutProcess.BOOT_TIMEOUT`에
   CLI 오버라이드(`--sut-boot-timeout-sec`)를 추가하는 편이 낫다 — 90초 하드코딩은 이 절차서를
   환경에 취약하게 만든다.
2. **[합성기 형상, 차단 원인 2의 (a)]** ① enum 타입 필드를 중첩 객체가 아니라 **enum 상수
   문자열**로 생성, ② 핸들러 **파라미터명 유래 유령 필드**(`request`) 생성 금지,
   ③ `@PathVariable` 피연산자를 body가 아니라 **경로 치환값**으로 라우팅, ④ by-id EP의 대상 행
   `seed.sql` 생성. 각각 RED 회귀 테스트 동반.
3. **[origin 전파, 차단 원인 2의 (c)②]** 컨트롤러 → 서비스 **1단계 파라미터 전파**에서 INPUT
   origin을 유지한다. 이것이 풀려야 petclinic에서 `INPUT×DB_READ` 라우팅이 발화한다.
4. **[오라클 조합 상한, 결함 D]** unguarded가 많을 때 조합 폭발로 오라클 산출물을 **전량 폐기**하는
   대신, 필드별 대표값 샘플링 등으로 일부라도 소비한다.
5. 위 수정 후 이 절차를 그대로 재실행하고 "실행 #2"로 누적 기록한다. **2·3번이 풀리기 전까지는
   petclinic 재측정의 기대값이 "B == A"이므로**, 그 상태의 재실행은 REQ-022 회귀 0 확인(측정 설계
   1층)에만 의미가 있고 효과 측정(2층)은 여전히 불가다.

##### 이번 실행에서 새로 드러난 문서 문제

1. **절차서 §"절차 — B"가 "승격된 후보가 하나 이상 생기면"을 전제**하지만, 후보가 **0개일 때
   어떻게 판정하는지**를 적어두지 않았다. 이번처럼 "후보 생성 자체가 불가"한 경우가 실재하므로,
   그 분기(= 효과 미측정, 순증 없음과 구분)를 판정 기준에 명시해야 한다. → 아래 §정정에 반영.
2. **사전조건에 "한산한 머신"이 없다.** SUT 부팅에 90초 하드코딩 데드라인이 걸려 있으므로
   부하 상태는 실행 가능/불가를 가르는 사전조건이다. → 사전조건에 추가.
3. **A(baseline)의 `GRB_TRIAL=off` 예시가 Gradle에서 전달을 보장하지 않는다**(위 §측정 설계 주의
   참조). → 빈 `--triple-candidates` 디렉터리 방식을 병기.
4. **기대값 "145"의 전제가 흔들렸을 가능성**: 이번 정적 인덱싱은 `found 24 endpoint(s)`인데
   `docs/coverage-progress.md`의 145 측정 당시는 23개다. jar/소스는 동일 커밋이므로 인덱서 쪽
   변화로 보이며, 재측정 시 baseline이 145와 다를 수 있다. 절차서가 이미 "145와 크게 다르면 먼저
   그 차이를 원인 분석에 기록하고 진행"이라고 규정하고 있으므로 문구 변경은 불필요하나, **24 vs 23
   관측 자체를 여기 남겨** 다음 실행이 놀라지 않게 한다.

##### 산출물 보존 위치 (전부 `.gitignore` 대상 — 커밋하지 않음)

`.work/e2e-b2/`(실행 로그·jar sha·probe body 2종), `.work/e2e-b2-baseline/`(실패한 A 빌드 산출물 +
`work/sut.log`), `.work/e2e-b2-triples-src{,-put}/`(provenance 리포트), `.work/e2e-b2-triples{,-put}/`
(합성 후보).

##### 자원 정리 (누수 검증 게이트)

`build`가 띄운 Testcontainers(postgres, ryuk)는 JVM 종료 시 Ryuk이 회수했고, 대조 실험의 petclinic
프로세스는 스크립트의 `trap cleanup EXIT INT TERM`이 **캡처한 PID만** 종료했다. 스위트 종료 후 확인:

```
docker ps -a                                  → 0건
docker network ls --filter name=grb -q        → 0건
pgrep -f spring-petclinic                     → 0건
docker volume ls --filter dangling=true       → 19건(전부 이번 실행 이전 생성 — 최신 것이 01:36Z,
                                                 이번 실행 구간은 02:50Z~03:22Z. 이번 실행 기인 0건)
```

무차별 정리(`docker system prune`, 광범위 `pkill`)는 수행하지 않았다. 과부하의 원인인 codegraph
프로세스 41개도 이 테스트 소유가 아니므로 그대로 두었다(위 §차단 원인 1).

#### 실행 #2 — 2026-07-28 (**GREEN** — tainted-spring mindgraph, 2xx 도달 실측)

실행 #1의 두 차단 원인을 모두 해소하고 재실행했다. **대상 SUT를 petclinic에서 tainted-spring
mindgraph로 교체**했고(근거: [벤치마크 조사](2026-07-28-tainted-spring-benchmark-survey.md)),
**지표를 `coveredAppBranches` 순증에서 "엔드포인트 2xx 도달"로 개정**했다(근거: 요구사항명세
REQ-029 개정 콜아웃).

##### 차단 원인 해소

- **부하**: `~/.claude/plugins/cache/temp_*`를 인덱싱하던 `codegraph.js init` 33개를 종료하고
  그 경로의 `.codegraph` 인덱스 13개를 삭제했다(플러그인 설치 부산물 — 사용자 프로젝트 아님).
  load 256 → 8. 실행 #1의 90초 health timeout 문제는 재현되지 않았다(SUT가 즉시 기동).
- **후보 생성 불가**: mindgraph는 평면 스칼라 body·단일 테이블 EXISTS 가드라 실행 #1이 지적한
  결함 A(enum 중첩)/D(조합 폭발)에 걸리지 않는다. 대신 **읽기 엔드포인트에서만 발현하는 결함
  6건**이 새로 드러나 전부 수정했다(커밋 `60ecb4b`, `19b4270`, `a3f90af` — 아래 §드러난 결함).

##### A/B 결과

| | A (baseline, `GRB_TRIAL=off`) | B (Phase A) |
|---|---|---|
| `get-internal-graphs-diary-diaryid` | `noHappyPathReason: "all responses error-enveloped"` | **`noHappyPathReason: null`** |
| 기록된 path | `…-s404e404-1` (404) | **`…-s200-1` (200)** + `…-s404e404-1` |
| `trialCount` / `tripleAdopted` | 0 / `false` | 1 / **`true`** |
| `get-internal-graphs-user-userid` | 404만 | 404만 (Redis 캐시 — `seed.sql` 채널 없음, 변화 없음) |
| `coveredAppBranches` | 0/28 | 0/28 |

**판정: GREEN.** 개정된 수용기준("A에서 2xx 미도달 → B에서 2xx 도달")을 충족한다. 이 엔드포인트는
이 프로젝트 이력상 **한 번도 2xx가 관측된 적이 없다** — 기존 블랙박스 아카이브의 생성 테스트
4건이 전부 404 단언이고, 유일한 500 케이스는 재생 불가 시드로 격리돼 있다
(`graphrag-blackbox/KNOWN-LIMITATIONS.md`).

**보조 지표는 움직이지 않았다(0/28 유지).** 축소해 적지 않는다 — 이 두 엔드포인트는
`totalBranches: 0`이라 분기 커버리지가 원리적으로 움직일 수 없다. 지표를 2xx 도달로 개정한
이유가 정확히 이것이며, 분기 수만 봤다면 이번 실행도 "효과 미측정"으로 기록됐을 것이다.

##### 파이프라인 완주 기록 (에이전트 채움 포함)

```
provenance        → EXISTS @ GraphService.java:81 → [INPUT:diaryId, DB_READ:graph_record]
synthesize-triple → body {diaryId: "seed-diaryid"}  (갭 마커 0개 — 전부 결정)
                    seed INSERT graph_record (diary_id, links_json, nodes_json, updated_at, user_id)
                         VALUES ('seed-diaryid', __AGENT_FILL__, __AGENT_FILL__, '2037-01-01', 'seed-user_id')
[에이전트]        → 마커 2자리만 '[]' 로 채움 (semanticHint=nodes_json/links_json)
trial             → status 200 → promoted/cand-01   (exit 0)
build --triple-candidates → tripleAdopted=true, path s200-1 status=200
```

도구가 **결정 가능한 것은 전부 결정하고 알 수 없는 두 값만 위임**했다는 점이 설계 의도대로
작동한 증거다. `nodes_json`이 유효한 JSON이어야 한다는 것은 스키마(TEXT NOT NULL)로는 알 수 없고
핸들러의 `objectMapper.readValue` 호출에서만 드러나는 사실이다.

##### 드러난 결함 6건 (전부 "읽기 엔드포인트" 조건에서만 발현 — POST 픽스처 테스트를 모두 통과하고 있었다)

| # | 결함 | 증상 | 커밋 |
|---|---|---|---|
| 1 | `orElseThrow`의 커스텀 도메인 예외 미인식 | 6개 서비스 14 EP가 `guards: []` | `60ecb4b` |
| 2 | 호출 인자↔파라미터 바인딩 부재 | 서비스 계층 가드 피연산자가 전부 UNKNOWN | `60ecb4b` |
| 3 | EXISTS 가드에 조회 테이블 미기재 | 합성기가 seed 놓을 테이블을 몰라 배치 skip | `60ecb4b` |
| 4 | 인터페이스 디스패치 조용한 누락 | 가드를 품은 호출을 건너뛰고도 리포트가 "깨끗함" | `19b4270` |
| 5 | `synthesize-triple`에 스키마 입력 경로 없음 | PK 빠진 INSERT — NOT NULL PK 테이블에서 실행 불가 | `a3f90af` |
| 6 | NOT NULL TEXT에 갭 마커 대신 padding | 존재 가드는 통과하나 역직렬화가 던져 **404→500** | `a3f90af` |
| 7 | trial CLI 경로 변수 미바인딩 | 유효 후보가 404를 받아 실패 판정 | `a3f90af` |
| 8 | `BodyShape` null에서 T1 NPE | 게이트 전체가 현행 경로로 회귀 | `a3f90af` |
| 9 | 시간형 컬럼 리터럴 varchar 바인딩 | T2는 200인데 채택 INSERT에서만 실패 | `a3f90af` |
| 10 | 채택 직후 pass-2가 채택 입력 덮어씀 | 확정 run의 200이 최종 리포트에서 404로 회귀 | `a3f90af` |

> 실행 #1이 "후보 생성 구조적 불가"로 기록한 것은 petclinic 특성(enum 중첩·조합 폭발)이었고,
> 이번에 드러난 것은 그와 **겹치지 않는 별개의 결함군**이다. 실행 #1의 결함 A~D는 여전히 미해결이며
> petclinic을 다시 대상으로 삼으려면 그쪽을 별도로 고쳐야 한다.

##### 산출물 보존 위치 (전부 `.gitignore` 대상)

`.work/e2e-b2/graph-A`(A 산출), `.work/e2e-b2/graph-B`(B 산출), `.work/e2e-b2/triples`(후보·promoted),
`.work/tainted-spring/`(SUT 클론 8개 + platform compose).

##### 자원 정리 (누수 검증 게이트)

`build`가 띄운 Testcontainers는 JVM 종료 시 Ryuk이 회수했다. `trial`용으로 띄운 compose 스택
(`-p grmindgraph`)은 **이 실행이 만든 것이므로 정리 대상**이다 — 아래 §완료 후 처리 참고.

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
# 경로는 반드시 절대 경로여야 한다 — :graph-rag-builder:run의 작업 디렉터리는 저장소 루트가
# 아니라 모듈 디렉터리라, 상대 경로를 주면 "matched no source directory"로 즉시 실패한다.
W="$(pwd)"
trap 'docker compose -p "$PROJECT" -f "$W/e2e/docker-compose.yml" -f "$W/.work/e2e-b3/work/attach-override.yml" down -v --remove-orphans >/dev/null 2>&1 || docker compose -p "$PROJECT" -f "$W/e2e/docker-compose.yml" down -v --remove-orphans >/dev/null 2>&1 || true' EXIT INT TERM
rm -rf .work/e2e-b3; mkdir -p .work/e2e-b3
./gradlew -q :graph-rag-builder:run --args="build \
  --sut-src $W/samples/order-service/src/main/java \
  --sut-resources $W/samples/order-service/src/main/resources \
  --sut-jar $W/samples/order-service/build/libs/order-service.jar \
  --sut-compose $W/e2e/docker-compose.yml \
  --out $W/.work/e2e-b3 --sut-id order \
  --attach --app-service app --app-port 58080 --coverage-port 16300 \
  --jdbc-url jdbc:postgresql://localhost:56432/app --db-service postgres \
  --auth-login-path /api/auth/login --auth-user admin --auth-pass password \
  --external-stubs $W/e2e/external-stubs \
  --sut-env EXTERNAL_INVENTORY_URL={{wiremock}},EXTERNAL_FRAUD_URL={{wiremock}} \
  --triple-candidates $W/e2e/triples \
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

### 실행 기록

#### 실행 #1 — 2026-07-28 (**GREEN** — 4/4 조합 기대대로)

절차대로 `samples/order-service` + `e2e/docker-compose.yml`을 attach 대상으로, 커밋된
`e2e/triples/post-api-transfers/{base,promoted}/cand-01` 픽스처를 재사용해 4개 조합을 각각
독립 실행했다(매 회 `trap`이 compose 스택을 내려 DB 상태 초기화).

| # | 플래그 조합 | 관측된 게이트 로그(누락 플래그 목록) | seed 적용 여부 | exploration-report 필드 | 판정 |
|---|---|---|---|---|---|
| 1 | 0개 | `attach seed gate closed(REQ-023 이중 opt-in 미충족) — missing: --attach-allow-seed, --confirm-non-production` | 미적용 | `tripleRejected={"attach-seed-gate-closed":1}`, `staleTriples=["post-api-transfers/promoted/cand-01"]` | **GREEN** |
| 2 | `--attach-allow-seed` | `… — missing: --confirm-non-production` | 미적용 | 동일(`attach-seed-gate-closed`) | **GREEN** |
| 3 | `--confirm-non-production` | `… — missing: --attach-allow-seed` | 미적용 | 동일(`attach-seed-gate-closed`) | **GREEN** |
| 4 | 둘 다 | **게이트 차단 로그 없음** | 적용(게이트 통과) | `tripleRejected={"attach-stub-inapplicable":1}` | **GREEN** |

**판정 근거 — 조합 4가 결정적이다.** 1~3은 누락 플래그 목록이 조합별로 **정확히 다르게** 나오고
(둘 다 / confirm만 / allow-seed만), 4에서는 그 로그가 **아예 나오지 않으며** 거부 사유가
`attach-seed-gate-closed` → `attach-stub-inapplicable`로 **바뀐다**. 즉 seed 게이트를 통과해
다음 단계까지 진행했다는 뜻이다 — 차단된 후보는 애초에 그 단계에 도달하지 않는다. 이것이
REQ-023 수용기준("0·1개는 seed 미적용·사유 기록, 2개일 때만 적용된다")의 실 환경 재현이고,
REQ-030이 요구하는 것 전부다.

**조합 4의 `status=500`은 결함이 아니다(REQ-025의 설계된 귀결).** 같은 실행이 남긴 로그:

```
attach EXTERNAL_RESPONSE stub inapplicable for post-api-transfers (REQ-025):
  candidate stub skipped, attach WireMock routing is Phase C scope
```

`post-api-transfers`는 fraud-check 외부 호출에 `stubs.json`이 필요한데 attach 모드는 스텁 등록을
**의도적으로 전혀 시도하지 않는다**(design spec §8 — attach egress 라우팅은 Phase C 백로그).
스텁 없이 실제 외부 호출이 나가 500이 된 것이므로, 이는 REQ-023 판정과 무관하며 절차서가
"부수적으로 기록해 두라"고 지시한 REQ-025 관측에 해당한다. **CI(AttachStubSkipIT)로만 확인되던
REQ-025가 실 attach 환경에서도 동일하게 동작함이 이번에 함께 실증됐다.**

**REQ-024(attach 역-DELETE 실패) 관측:** 발생하지 않았다 — 조합 4에서 후보 seed의 정리가
실패했다는 로그가 없고 `attachRemainingRows`도 비어 있다.

**산출물 보존 위치(전부 `.gitignore` 대상):** `.work/e2e-b3-{1,2,3,4}/`(조합별 그래프 산출 +
`exploration-report.json`), 실행 로그는 세션 스크래치패드.

**자원 정리(누수 검증 게이트).** 4회 모두 `trap cleanup EXIT INT TERM`이
`docker compose -p grb-e2e-b3 … down -v --remove-orphans`를 수행했다. 스위트 종료 후 확인:

```
docker compose ls | grep grb-e2e-b3        → 0건
docker ps -a --format '{{.Names}}' | grep grb-e2e-b3 → 0건
docker volume ls -q --filter name=grb-e2e-b3         → 0건
docker ps                                             → 0건(이 세션이 띄운 것 전부 없음)
```

이 실행에 앞서 E2E-B2가 띄웠던 `grmindgraph` compose 스택도
`down -v --remove-orphans`로 내렸다(잔존 0). 무차별 정리(`docker system prune`, 광범위 `pkill`)는
수행하지 않았다.

**절차서 자체의 결함 1건(수정함).** §절차의 명령 골격이 `--sut-src samples/…` 등 **상대 경로**를
쓰는데, `:graph-rag-builder:run`의 작업 디렉터리는 저장소 루트가 아니라 모듈 디렉터리라
`--sut-src 'samples/order-service/src/main/java' matched no source directory`로 즉시 실패한다
(실측 — 첫 시도가 이 오류로 1초 만에 종료됐다). 절차를 그대로 따르면 재현되지 않으므로 골격을
절대 경로(`$W/…`)로 고쳤다.

> **미확인 사항(고치지 않고 남김):** E2E-B1 §"통합 build 경로 재확인"의 명령 스니펫도 같은
> 상대 경로 형태(`--sut-src samples/order-service/src/main/java`)를 쓴다. 같은 결함일
> 가능성이 높지만, E2E-B1 실행 #2는 GREEN으로 기록돼 있어 **그때 실제로 무엇을 실행했는지**
> (절대 경로로 바꿔 돌렸는지, 다른 cwd였는지)를 지금 확인할 수 없다. 추측으로 남의 실행 기록을
> 고치지 않고 이 갭만 표시해 둔다 — E2E-B1을 다음에 재실행할 때 확인할 것.

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

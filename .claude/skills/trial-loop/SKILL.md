---
name: trial-loop
description: Repeatedly drives the graph-rag-builder `trial` CLI subcommand (T2) — which applies one candidate's seed rows and body (stub registration is always skipped on this CLI path), invokes the endpoint with capture off, and classifies the response into a FailureDigest — applying any toolSuggestion verbatim and only fabricating a new value within the marker contract when the digest offers none (an UNKNOWN failure), until a candidate is promoted or the trial budget is exhausted. Use this third and last — after provenance-analysis and triple-synthesis — to validate and promote a candidate request triple.
---

# trial-loop (C3)

이 스킬은 삼중(triple) 합성 파이프라인의 **세 번째·마지막 단계(C3)**다. 실행 순서:
`provenance-analysis(C1) → triple-synthesis(C2) → trial-loop(C3)`.

용어: **T2**는 `trial` CLI(결정적 코드, 후보 1개를 시험하고 digest를 내는 부분)를 가리키고,
**이 스킬(C3)**은 그 T2를 반복 구동하는 에이전트 절차다 — 둘을 혼용하지 않는다.

## 선행 산출물 가드 — 반드시 먼저 확인하라

이 스킬은 **triple-synthesis(C2)가 만든 후보 트리플**이 필요하다. 실행 전에
`<triple-store>/<endpointId>/cand-NN/` 디렉토리(마커가 채워진 `body.json`/`seed.sql`/
`stubs.json`)가 존재하는지 확인하라.

- **없으면**: 이 스킬을 실행하지 말고, 먼저 **triple-synthesis** 스킬을 실행해 후보를
  만들고 갭 마커를 채워라(마커가 그대로 남아 있으면 trial이 무의미한 값으로 실패한다).
- **있으면**: 그대로 아래 루프를 진행한다.

## ⚠️ 이 CLI는 **실 DB에 쓴다** — 실행 전에 반드시 읽어라

`trial` CLI는 `--jdbc-url`이 가리키는 **실제 데이터베이스에 직접 INSERT/DELETE를 수행한다.**
후보 `seed.sql`의 행을 넣고, 판정 후 그 행을 되돌리는 DELETE를 실행한다.

- **비-운영 DB에만 붙여라.** 이 CLI에는 `build --attach` 경로의 이중 opt-in 가드
  (`--attach-allow-seed` + `--confirm-non-production`, REQ-023)가 **없다** — 주어진 JDBC URL에
  아무 조건 없이 쓴다. 운영/공유 DB URL을 넘기지 마라.
- **정리 DELETE는 스키마의 PK로만 나간다.** 후보가 컬럼 순서를 바꾸거나 인용 식별자에 문장을
  숨겨도 그것이 DELETE 문에 반영되지 않는다(키는 DB 카탈로그의 PK, 값은 PreparedStatement
  바인딩). 대신 **정리 키를 스키마 사실로 결정할 수 없는 후보는 시도 자체가 차단된다**(PK 없는
  테이블, 컬럼 목록 없는 `INSERT INTO t VALUES (...)`, 안전하지 않은 식별자 등) — 그 후보는
  DB를 전혀 건드리지 않고 `SEED_CLEANUP_UNRESOLVABLE` 다이제스트와 함께 `failed/`로 간다.
- **정리 DELETE가 실패하면 행이 남는다.** 이 CLI 경로(비-attach)는 REQ-024의 승격 차단을 적용하지
  않으므로, cleanup 실패는 로그로만 관측된다 — 로그에 `trial candidate seed cleanup failed`가
  보이면 남은 행을 직접 확인하라.
- **동시 실행 금지.** 아래 "직렬화 유의" 참조 — 이 CLI에는 직렬화 락이 없다.

## T1 검증 게이트는 이 CLI에도 적용된다

각 후보는 `runCandidate` 이전에 **T1 검증 게이트**(`TripleValidator`)를 통과해야 한다 —
마커-diff(REQ-009, 마커 외 변경 reject), `seed.sql` 화이트리스트(REQ-010), WireMock stub 스키마
(REQ-011), PII 휴리스틱 차단(REQ-012). 거부된 후보는 **DB/HTTP를 전혀 건드리지 않고**
`T1_REJECTED` 다이제스트와 함께 `failed/`로 이동한다.

그래서 **provenance 리포트가 필수**다(화이트리스트 허용 테이블 집합의 유일한 출처):
`--provenance-report`를 주거나, 저장 레이아웃 규약 위치
`<triple-candidates>/<endpointId>/provenance-report.json`에 파일이 있어야 한다. 없으면 CLI는
후보를 하나도 시험하지 않고 즉시 실패한다(fail-closed).

**보통은 아무것도 안 해도 된다** — `triple-synthesis`(`synthesize-triple` CLI)가 입력 리포트를
그 규약 위치로 복사하므로, `provenance-analysis → triple-synthesis → trial-loop` 순서를 그대로
따랐다면 `--provenance-report`를 넘길 필요가 없다. 별도 경로의 리포트를 쓰고 싶을 때만 플래그로
지정하라.

**이 경로에 남는 유일한 T1 갭:** 이 CLI에는 그래프 자산이 없어 `BodyShape`를 `empty()`로 넘기므로,
REQ-011 중 **body 필드 스키마 검증만** skip된다(마커-diff·화이트리스트·PII·stub 스키마 검증은 전부
적용). body에 SUT DTO에 없는 필드가 있는지는 통합 `build` 경로(실제 `BodyShape` 보유)가 잡는다.

## 결정적 코드가 하는 일 (에이전트가 재구현하지 말 것)

`trial` CLI 서브커맨드(T2) 1회 호출은 대기 중인 후보들(`cand-NN`)을 순번대로, `--trial-budget`
한도 안에서 다음 시퀀스로 시도한다: happy 시드 정리 → 후보 `seed.sql` INSERT → 후보
`stubs.json` 등록(**표준 CLI 경로에서는 항상 skip** — 아래 참고) → 후보 `body.json`으로
**캡처-off 경량 invoke**(SQL 캡처 scope 미개설, 요청별 JaCoCo dump 스킵, 결과가 누적
커버리지/그래프에 병합되지 않는다 — 확정 run 대비 가벼운 이유가 이 모드다). 응답은
`ResponseClassifier`(엔벨로프 인지)로 판정한다. 실패하면
`FailureDigest`를 산출한다 — status·outcome kind·응답 바디·SUT 로그 구간·스택 발췌, 그리고
**실패 가드 역매핑**(스택 프레임 ↔ provenance 가드 위치 자동 대조, 안 되면 응답/로그
메시지 ↔ 가드 검증 메시지 매칭 휴리스틱, 그래도 안 되면 `mappedGuard: null`). 경계 ±1,
enum 불일치, 필수 필드 누락처럼 규칙으로 수리 가능한 실패는 `toolSuggestion`(구체적인
수정 제안)까지 산출한다. 성공하면 그 후보를 즉시 `promoted/`로 옮기고 CLI가 종료 코드
0으로 끝난다.

**stub 등록(③)은 이 CLI 경로에서 항상 skip된다.** `BuilderCli.runTrial`은 자체
`HttpCaptureServer`를 기동하지 않고 `TrialRunner`에 `httpCapture=null`을 넘긴다 — 코드가
`registerCandidateStub`을 무조건 skip하게 만드는 하드코딩이며, 임시 배선 누락이 아니라
현재 CLI의 실제 동작이다(`runTrial` Javadoc에도 명시). 즉 **`EXTERNAL_RESPONSE` 가드에
의존하는 엔드포인트는 trial 중 `stubs.json`이 등록되지 않고, 실제 외부 서비스 응답(또는
SUT의 기본 동작 — 예: 외부 호출 실패 시 재시도/타임아웃/예외)을 그대로 받는다.** 그 결과
`stubs.json`의 갭 마커를 아무리 정확히 채워도 이 CLI 단독 실행으로는 검증되지 않는다 —
`EXTERNAL_RESPONSE` 삼중의 실제 검증은 stub 배선이 있는 확정 run/build 경로(T3) 쪽 책임이다.

## CLI 실행법

```
./gradlew -q :graph-rag-builder:run --args="trial \
  --endpoint <ENDPOINT_ID> \
  --http-method <METHOD> --path '<PATH>' \
  --sut-base-url <BASE_URL> \
  --jdbc-url <JDBC_URL> [--db-user <USER>] [--db-password <PASSWORD>] \
  --db-type postgres|mysql|mariadb \
  [--triple-store <DIR>] [--triple-candidates <DIR>] \
  [--trial-budget 8] \
  [--happy-seeds <required-seeds.json>] \
  [--provenance-report <provenance-report.json>]   # 플래그는 선택, 리포트 파일 자체는 필수 \
  [--sut-log-file <FILE>] \
  [--error-when-present <field1,field2,...>] [--semantic-status-field <field>] \
  [--error-detail-field <field>] [--error-detail-contains <substring>]"
```

플래그(실제 `BuilderCli.runTrial` 소스 기준):

| 플래그 | 필수 | 기본값 | 설명 |
|---|---|---|---|
| `--endpoint` | 예 | — | endpointId(예: `post-api-transfers`) |
| `--http-method` | 예 | — | HTTP 메서드 |
| `--path` | 예 | — | 요청 경로 |
| `--sut-base-url` | 예 | — | 이미 떠 있는 SUT의 base URL |
| `--jdbc-url` | 예 | — | 시드 INSERT 대상 DB의 JDBC URL |
| `--db-user` | 아니오 | `""` | DB 사용자 |
| `--db-password` | 아니오 | `""` | DB 비밀번호 |
| `--db-type` | 예 | — | `postgres` \| `mysql` \| `mariadb` |
| `--triple-store` | 아니오 | `.graphrag/triples` | promote/fail 대상 루트 |
| `--triple-candidates` | 아니오 | `--triple-store`와 동일 | 시도할 후보를 읽어올 루트(다르게 줄 수 있음) |
| `--trial-budget` | 아니오 | `8` | 이번 호출에서 시도할 후보 최대 개수(fuzzer 예산과 분리된 값) |
| `--happy-seeds` | 아니오 | — | 후보 시드 전에 먼저 넣을 happy-path 시드(JSON, `RequiredSeed[]`) |
| `--provenance-report` | 플래그는 아니오, **파일은 예** | `<triple-candidates>/<endpointId>/provenance-report.json` | T1 화이트리스트 허용 테이블 집합의 유일한 출처 + 실패 가드 역매핑(`FailureDigest`). 플래그를 생략하면 기본값 경로를 읽고, 거기에도 파일이 없으면 CLI는 후보를 하나도 시험하지 않고 즉시 실패한다(fail-closed). `synthesize-triple`이 입력 리포트를 이 기본값 경로로 복사하므로, 파이프라인 순서대로 실행했다면 별도 지정이 필요 없다 |
| `--sut-log-file` | 아니오 | — | 로그 구간 발췌 대상 로그 파일 |
| `--error-when-present` | 아니오 | (없음 → `StatusOnlyClassifier`) | 콤마 구분 필드 목록. 하나라도 주면 `ErrorEnvelopeClassifier` 사용 |
| `--semantic-status-field` | 아니오 | `errorCode` | 에러 엔벨로프의 상태 필드명(`--error-when-present`와 함께 씀) |
| `--error-detail-field` | 아니오 | — | 에러 상세 필드명 |
| `--error-detail-contains` | 아니오 | — | 에러 상세에 포함돼야 하는 부분 문자열 |

**분류 플래그는 확정 run/build와 반드시 맞춰라.** `ClassifierConfig.from(o)`가 이 4개
옵션을 읽어 `ResponseClassifier`를 만든다 — 생략하면 항상 `StatusOnlyClassifier`(HTTP
status만으로 성공/실패 판정)로 떨어진다. 이 SUT가 실제로는 200 OK + 에러 바디(에러
엔벨로프) 계약을 쓴다면, trial 단계에서 이 플래그들을 빠뜨리면 build가 실패로 볼 응답을
trial은 성공으로 오판(또는 그 반대)할 수 있다 — build 커맨드에 준 것과 동일한 값을
넘겨야 판정 일관성이 보장된다.

종료 코드: **0** = 어떤 후보가 성공해 즉시 `promoted/`로 이동함(더 시도하지 않고 끝). **3** =
이번 호출에서 시도한 후보가 전부 실패(예산 소진 포함) — 시도한 후보들은 `failed/`로
이동하고, `<endpointId>/failed/digest-final.json`에 이번 호출에서 시도한 순서대로 digest
배열이 남는다. **예산이 대기 후보 수보다 적어 아예 시도하지 못한 후보는 원래 위치
(top-level `cand-NN`)에 그대로 남는다** — 다음 호출에서 예산이 재충전된 채로 이어서
시도된다.

## 루프 규율

1. **T2(trial CLI)를 1회 실행**한다. 종료 코드 확인.
2. **0(성공)**이면 그 즉시 끝 — 해당 후보는 이미 `promoted/`로 이동해 있다. 아래 5번으로.
3. **3(전부 실패)**이면 `<endpointId>/failed/digest-final.json`을 읽고, 각 실패 후보
   (`<endpointId>/failed/cand-NN/`)의 digest를 순서대로 판독한다:
   - **`toolSuggestion`이 있으면 그대로 적용한다** — 창작하지 말고 제안값을 정확히 반영한다.
     **현재 `FailureDigest.suggestPatch`가 제안을 만드는 조건은 좁다**: `mappedGuard`가
     NUMERIC 비교(`>`/`>=`/`<`/`<=`)이고 그 가드의 피연산자 중 정확히 하나가 `DB_READ`
     origin(컬럼 식별 가능)일 때만 산출되며, 그때도 항상 `seed.sql`의 해당 컬럼 값만
     패치한다(`{"seed.sql": {"column": ..., "value": ...}}`). `body.json`/`stubs.json`을
     가리키는 `toolSuggestion`은 현재 산출되지 않는다 — 이 조건을 벗어나는 실패는 전부
     아래 "UNKNOWN 실패" 경로(에이전트 창작)로 간다.
   - **`toolSuggestion`이 없는 UNKNOWN 실패일 때만** 에이전트가 새 값을 창작한다 —
     digest의 응답 바디·`mappedGuard`(있다면)·로그 구간·스택 발췌를 근거로 삼는다.
     `mappedGuard: null`(가드 역매핑 실패)이 일반적으로 나올 수 있음을 전제하고, 그럴
     때는 응답/로그 메시지 텍스트를 직접 읽어 원인을 판단한다.
   - **어느 경우든 마커 계약은 그대로 유지한다: 마커만 채워라 — 마커 아닌 값은 절대
     수정 금지.** `triple-synthesis`가 이미 가드를 만족시켜 확정한 값(마커가 아니었던
     값)은 이 수리 단계에서도 건드리지 않는다 — 수정 대상은 항상 원래 갭 마커였던
     자리, 또는 `toolSuggestion`이 명시적으로 지목한 자리뿐이다.
   - 값을 고친 뒤 그 후보 디렉토리를 `failed/`에서 다시 최상위(`<endpointId>/cand-NN`)로
     옮겨 다음 T2 호출이 재시도하게 한다.
4. **`--trial-budget`(기본 8) 누적 소진** 시 재시도를 멈추고 `failed/digest-final.json`을
   최종 실패 보고서로 남긴 채 종료한다 — 더 창작하지 않는다. (예산 8은 fuzzer 예산과는
   별개다: trial 1회 비용이 HTTP 왕복 + 시드 INSERT 수준으로 저렴하므로 여유 있게 잡은
   값이다.)
5. **승격 이후**: 성공한 후보는 `promoted/`로 이동해 있다. 확정 run(T3, 캡처-on 재실행)과
   PR 리뷰 게이트는 빌더/사람 프로세스가 처리하며 이 스킬의 책임 범위 밖이다 —
   `promoted/`로 옮겨진 것만 확인하고 종료한다.

## 직렬화 유의

trial은 SUT DB 전역 상태(시드 INSERT/DELETE)를 만진다. 한 endpoint의 trial-loop이 끝나기
전에 **같은 SUT를 대상으로 다른 endpoint의 trial-loop을 동시에 시작하지 마라** — endpoint
단위로 직렬 진행한다. **이 스킬이 호출하는 독립 `trial` CLI 자체에는 직렬화 락이 없다** —
`EndpointExplorationRunner`의 정적 락(`TRIPLE_GATE_LOCK`, REQ-017)은 통합 `build` 파이프라인
내부에서 trial 재확인+확정 run 구간을 감싸는 코드이며, `BuilderCli.runTrial`(이 스킬이 쓰는
독립 CLI 경로)에는 적용되지 않는다. 즉 여러 endpoint의 trial-loop을 동시에 돌리지 않는
것은 **전적으로 에이전트(이 스킬을 수행하는 주체)의 책임**이다.

## PII 금지

새 값을 창작할 때도 실존 인물 이름·실제 연락처 등 실데이터를 절대 넣지 않는다 — 합성값만
쓴다(triple-synthesis 단계와 동일한 원칙).

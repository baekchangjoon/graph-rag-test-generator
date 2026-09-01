# 테스트 커버리지 개선 추이 (graph-rag-builder)

> **개발 트래킹 문서** — 사용자 안내가 아니라 커버리지 진행을 기록하는 작업 문서다. 실증 기록은 시간 역순이 아니라 실증 번호순으로 읽는다.

작성: 2026-06-16

이 문서는 `graph-rag-builder`(SUT를 탐색해 그래프/테스트를 생성하는 도구)의 입력 발견 능력이
단계적으로 향상되며 **벤치마크 SUT의 분기 커버리지**가 어떻게 상승해 왔는지 기록한다.

## 측정 방식

- **벤치마크 SUT**: `spring-petclinic`(REST fork) — 다양한 검증/상태 가드를 가진 표준 벤치마크.
- **지표**: `exploration-report.json`의 **APP-AGGREGATE 분기 커버리지** = `coveredAppBranches / totalAppBranches`.
  분모(`totalAppBranches = 253`)는 SUT 바이트코드로 고정 — 도구가 탐색으로 **연** 분기 수만 변한다.
- **방식**: 도구가 SUT를 직접 부팅(Testcontainers + JaCoCo)하고 분기를 탐색하며 측정. 같은 jar에 대해
  도구 버전만 바꿔 A/B 실측 → 순수하게 "입력 발견 능력"의 기여를 분리한다.

> 데이터 출처: **Stage 0~3**(33→113)은 이전 세션 측정(메모리 `input-discovery-staged-roadmap` 기록),
> **47.0%→57.3%**(119→145)는 2026-06-16 세션에서 **동일 jar에 도구 버전만 바꿔 직접 재측정**한 값이다.
> 측정 시점·SUT 버전 차이로 단계 경계의 절대값은 ±소폭 오차가 있을 수 있으나, **추세와 각 단계의 기여는
> 동일 방식으로 검증**됐다.

## 추이 (petclinic, covered/253 분기)

```
 % │
60 │                                              ● 57.3%  (a #40 / #5 #41)
   │                                        ● 51.8%  (@Controller 폼 #39)
50 │                                  ◦ 47.0%  (이전 세션 종료 baseline)
   │
   │                    ● 44.7%  (Stage 3: by-id 진입 #22)
40 │
   │
30 │           ● 27.3%  (Stage 1+2: conjunction/joint #21)
   │
20 │     ● 18.6%  (Stage 0: 유효 happy #20)
   │  ● 13.0%  (정적 인덱싱만 — happy 입력 없음)
10 │
   └────────────────────────────────────────────────────
     static  S0    S1+2   S3    …auth/S4…  #39   #40/#5
```

| 단계 | covered/253 | % | PR | 핵심 개선 |
|---|---|---|---|---|
| 정적 인덱싱만 | 33 | 13.0% | — | 엔드포인트/스키마만, 유효 입력 없음 |
| **Stage 0** 유효 happy 합성 | 47 | 18.6% | #20 | enum 첫 상수·ISO 날짜·이메일 합성 (`EnumConstantExtractor`) |
| **Stage 1+2** conjunction/joint | 69 | 27.3% | #21 | `&&` 다필드 가드 동시충족 (`extractConjunctions`, joint 변이) |
| **Stage 3** by-id 진입 | 113 | 44.7% | #22 | 리소스 시드로 `/{id}` 엔드포인트 진입, 가드 유래 enum 컬럼 시드 |
| Stage 3b~4 · auth (누적) | ~119 | 47.0% | #23~ | fresh-DB by-id, 상태가드 양-arm, 음성-인증 거부 arm |
| **#3** @Controller 폼 인덱싱 | 131 | 51.8% | **#39** | `@Controller` 웹폼(form-urlencoded) 인덱싱·탐색 (`ParamKind.FORM`) |
| **(a)** 클래스레벨 path 변수 역추출 | 145 | **57.3%** | **#40** | `@ModelAttribute` 헬퍼의 path 변수를 PATH로 역추출 → 폼 핸들러 진입 |
| **#5** 상태머신 다중 전이 | 145 | 57.3% | **#41** | ENUM `==` 가드 다중 변종(petclinic은 해당 가드 부재 → 무변) |

**누적: 13.0% → 57.3% (+44.3pp). 2026-06-16 세션 기여분: 47.0% → 57.3% (+10.3pp).**

## 2026-06-16 세션 상세 (이번 개선의 분리 측정)

동일 petclinic jar에 도구 버전만 바꿔 측정 — 각 PR의 순수 기여:

| 버전 | covered/253 | Δ | 인덱싱 endpoints | 비고 |
|---|---|---|---|---|
| baseline (이전 main) | 119 (47.0%) | — | 16 | REST-only |
| + #39 @Controller 폼 | 131 (51.8%) | **+12** | 23 | `@Controller` 7개 폼 핸들러 신규 인덱싱 |
| + #40 path 변수 역추출 | 145 (57.3%) | **+14** | 23 | `post-owners-ownerid-pets-new` 등 폼 **진입** 해금(0/12→커버) |
| + #5 상태머신 다중 전이 | 145 (57.3%) | +0 | 23 | petclinic에 ENUM `==` 상태 가드 없음(order-service 벤치마크로 검증) |

- **#40이 최대 기여(+14)**: #39로 폼이 인덱싱돼도 클래스레벨 `{ownerId}`(`@ModelAttribute`에서만 해석)를
  PATH로 못 잡아 `findOwner`가 5xx로 막혔던 것을 역추출로 해소 → 폼 바인딩/검증 분기가 대거 열림.
- **REST 엔드포인트 무회귀**: 16개 `@RestController` 엔드포인트의 covered/total 분기는 전 단계에서 **불변**
  (이번 작업이 기존 경로를 깨지 않음을 동일-jar 비교로 확인).
- **#5는 petclinic 무변·order-service에서 실증**: petclinic엔 enum 상태 전이 가드가 없어 APP-AGGREGATE는
  그대로지만, order-service `BookingController.advance`(200/409/410)로 다중 전이 arm 캡처를 결정적으로 검증.

## Phase A — 에이전트 스킬 기반 삼중 합성 [🟢 실증 #2 GREEN — tainted-spring mindgraph 2xx 도달]

`provenance`/`synthesize-triple`/`trial` CLI 3종 + 에이전트 스킬 3종으로 다중 가드(입력 검증 →
DB 상태 비교 → 외부 응답 검증) 순차 조건 때문에 못 열던 **깊은 happy path**를 여는 기능이다
(설계: `docs/superpowers/specs/2026-07-26-agent-skill-triple-synthesis-design.md`, CLI/스킬
사용법: [docs/03](03-graph-rag-builder.md) "삼중 합성" 절).

petclinic의 잔여 145/253(57.3%)은 이 표(위 "추이" 표)의 마지막 상태다 — 잔여 108개 분기가
"비선형·interprocedural·집계·상태 의존 가드"(`docs/24-input-discovery-internals.md` "남은 한계")로 정체돼
있었고, 삼중 합성이 그중 다중 가드 순차 조건에 해당하는 부분을 얼마나 여는지가 이 절이 채울
자리다.

### 2026-07-28 실증 #2 결과 — GREEN (mindgraph, 2xx 도달)

실증 #1이 확정한 "petclinic으로는 측정 불가"를 받아들여 **대상 SUT를 tainted-spring
`mindgraph`로 교체**하고, **지표를 `coveredAppBranches` 순증에서 "엔드포인트 2xx 도달"로
개정**했다(요구사항명세 REQ-029 개정 콜아웃 참조).

| 항목 | A (baseline, `GRB_TRIAL=off`) | B (Phase A) |
|---|---|---|
| SUT | tainted-spring `mindgraph` (동일 jar `mindgraph-service-0.1.0.jar`) | 동일 |
| `GET /internal/graphs/diary/{diaryId}` | 404만 · `noHappyPathReason="all responses error-enveloped"` | **200** (`s200-1`) · `noHappyPathReason=null` |
| `tripleAdopted` | `false` | **`true`** (trialCount=1) |
| `GET /internal/graphs/user/{userId}` | 404만 | 404만 (Redis 캐시 — `seed.sql` 채널 대상 아님) |
| `coveredAppBranches` | 0/28 | 0/28 |
| 판정 | — | 🟢 **GREEN** |

**판정 근거는 위 표의 A(404만)/B(200) 대조 그 자체다.** 보조로, 이 엔드포인트는 과거에도 2xx가
관측된 적이 없는 것으로 보인다 — tainted-spring `mindgraph` 레포에 동봉된 블랙박스 아카이브
(**이 저장소가 아니라 그 외부 레포의** `graphrag-blackbox/`)의 생성 테스트 4건이 전부 404
단언이고 유일한 500 케이스는 재생 불가 시드로 격리돼 있다. 이 저장소만으로는 확인할 수 없으므로
판정 근거로 쓰지 않는다.

**보조 지표가 움직이지 않은 것(0/28)을 축소해 적지 않는다.** 이 두 엔드포인트는
`totalBranches: 0`이라 분기 커버리지가 원리적으로 움직일 수 없다 — 지표를 2xx 도달로 개정한
이유가 정확히 이것이며, 분기 수만 봤다면 이번 실행도 "효과 미측정"으로 기록됐을 것이다.
**따라서 petclinic의 145/253은 이번 실증으로 갱신되지 않았다** — 아래 실증 #1 절이 설명하듯
그 SUT는 별도의 미해결 결함군(enum 중첩 body·조합 폭발)에 막혀 있다.

전체 기록·파이프라인 완주 로그·드러난 결함 10건은
[수동 실증 절차서 § E2E-B2 실행 #2](superpowers/reports/2026-07-26-triple-synthesis-manual-evidence.md).

### 2026-07-28 실증 #1 결과 — RED (효과 미측정)

| 항목 | 값 |
|---|---|
| baseline(A, 이 절 작성 시점) | 145/253 (57.3%) |
| A(baseline) 재측정 | **미측정** — `build` 3회 연속 `SUT did not become healthy in PT1M30S` |
| Phase A 적용 후(B) | **미측정** — 투입할 promoted 트리플 후보가 **0개** |
| Δ | **산출 불가**(두 값 모두 미측정) |
| petclinic jar sha256 | `28e8cea2075203371e2b09a2879441df0cf14847362e40d5d7b34c5d29921ab0` |
| 판정 | 🔴 — **"순증 없음"이 아니라 "미측정"**이다 |

**⚠️ 이 표를 "Phase A는 효과가 없다"로 읽으면 오독이다.** 이번 실증이 확정한 것은
"**petclinic으로는 이 기능의 효과를 측정할 수 없다**"이다. 기능 자체의 완주는
`samples/order-service`에서 E2E-B1 실행 #2로 확인됐다(REQ-027 🟢).

#### 원인 분석 (수용기준이 요구하는 첨부 항목)

**원인 1 — 머신 과부하로 A/B 빌드가 시작조차 못 했다.** 10코어 머신의 load average가 측정 구간
내내 217~410이었다(원인: 이 실증과 무관하게 상주 중이던 `codegraph init` 프로세스 41개, 최장
13일). petclinic SUT는 otel + pjacoco javaagent 2종을 달고 뜨는데 `SutProcess.BOOT_TIMEOUT`이
**90초 하드코딩 상수이고 CLI 오버라이드가 없어**, 부팅이 데드라인을 넘겨 3회 모두 실패했다
(3회차 실측: 프로세스 기동 → Spring `main` 진입까지만 57초). 이 프로세스들은 이 테스트 소유가
아니므로 종료하지 않았다(정리 규칙 준수).

**원인 2 — petclinic에는 승격 가능한 트리플 후보를 만들 수 없다(부하와 무관, 정적 확정).**
SUT 부팅이 필요 없는 `provenance` + `synthesize-triple` 경로는 끝까지 실행됐고, 결과는 명확하다:

- **`POST /api/reservations`** — 가드 11개, `unresolved: 0`이지만 피연산자 origin이 사실상 전멸
  (`INPUT` 1건, 나머지 `UNKNOWN`). DB 가드 `countByRoomNumberAndStatus >= 2`조차 `DB_READ`로
  잡히지 않는다. 합성 후보는 1개·**결정 필드 0/11**이고, enum `priceTier`를
  `{"nightlyRate":…}` 중첩 객체로 만드는 형상 결함이 있다.
  - **대조 실험(라이브 petclinic)**: 마커만 채운 후보 → **HTTP 400**, 값은 그대로 두고 형상만
    고친 손수 body → **HTTP 201**. 엔드포인트는 2xx 도달 가능하고, 막는 것은 합성기의 형상이다.
    그 수리는 키 집합·구조 변경이라 **마커 계약(REQ-009) 위반**이므로 에이전트가 할 수 없다.
- **`PUT /api/reservations/{id}`** — `DB_READ` 피연산자가 2개 잡혀 Phase A가 노리는
  `INPUT×DB_READ` 조합에 가장 근접하지만, 짝이 되는 반대편(`req.nights()`/`req.status()`)이
  `UNKNOWN`이라 라우팅이 발화하지 않는다. 후보 4개 전부 `@PathVariable`인 `id`를 **body에**
  `-1`로 배치하고 `seed.sql`이 비어 있어 승격 불가.
- **공통 근본 원인** — 두 EP의 `notes.md`가 **모든 가드**(11 + 7)를 "확장 지점(미지원)"으로
  표기한다: ① `||`/`&&` 결합 논리 미지원, ② `INPUT×(DB_READ|EXTERNAL_RESPONSE)`가 아닌 비교
  미지원. petclinic의 잔여 가드는 **`||`로 결합된 INPUT 단독 범위검사**가 압도적이고, 컨트롤러 →
  서비스로 한 단계 넘어간 파라미터에서 origin이 UNKNOWN으로 소실된다. 즉 Phase A 삼중 합성이
  여는 대상인 **교차 가드가 petclinic에서는 정적으로 인식되지 않는다**.

상세 관측·커맨드·재시도 계획(합성기 형상 4건 + origin 전파 + 오라클 조합 상한)은
[수동 실증 절차서 — E2E-B2 실행 기록](superpowers/reports/2026-07-26-triple-synthesis-manual-evidence.md#e2e-b2--petclinic-커버리지-실측-req-029)에 있다.

#### 추이 그래프에 점을 추가하지 않은 이유

측정값이 없기 때문이다. 위 "추이" 표·그래프의 마지막 상태(145/253, 57.3%)가 여전히 최신이며,
Phase A 행은 실측이 성립한 뒤에 추가한다.

**실측 절차**: [수동 실증 절차서 — E2E-B2](superpowers/reports/2026-07-26-triple-synthesis-manual-evidence.md)에
동일-jar A/B 실행 커맨드·판정 기준·기록 양식이 고정돼 있다. **위 문단과 이 절차서의 petclinic
전제는 실증 #1(RED) 당시의 기록이다** — 실증 #2에서 대상을 tainted-spring으로, 지표를 "엔드포인트
2xx 도달"로 개정했고(§2026-07-28 실증 #2 결과), REQ-029는 🟢로 전환됐다. **다만 petclinic
145/253은 그 개정으로 갱신되지 않았고 여전히 이 표의 최신 상태다** — petclinic에서 Phase A가 여는
분기 수는 아직 답이 없는 미해결 항목으로 남는다(그 SUT의 별도 결함군: enum 중첩 body·조합 폭발).

## 작업 #4 — float inter-field (구현 완료)

| 작업 | 상태 | 비고 |
|---|---|---|
| **#4** float/double inter-field | **구현·머지** | `Sym` Rational 일반화 + domain(INT/REAL/MIXED) 추적 + Z3 Real(`solveTupleReal`, 경계 margin). order-service `PricingController` band 벤치마크로 실증(`BuilderIntegrationTest`). 정수 경로 무회귀. `2026-06-16-interfield-float-double.md` |

## 보류 작업 (ROI 평가)

| 작업 | 상태 | 사유 |
|---|---|---|
| **(b)** entity-Formatter | 설계 노트만 | "폼 커맨드 정확 선택" 선결 + Formatter parse SUT-특화 + petclinic 한정 실효 |

(b)는 3-모델 리뷰로 **큰 코어 비용 대비 실측 실효가 낮음**이 확인되어 설계를 보존하고 구현을 보류했다.
#4는 ROI 보류였으나 후속 세션에서 코어 일반화(향후 float-heavy SUT 대비) + 정수 경로 무회귀를 조건으로 구현했다.

## 방법론

매 단계는 **동일 jar A/B 실측 + CI 회귀화**로 검증했다: ① 설계 문서 → 3-모델(Sonnet/Gemini/GPT) 리뷰 →
triage → ② double-loop TDD(E2E 수용 먼저 RED) → ③ 전 SUT 회귀 스윕(order-service e2e + petclinic +
MSA 정적/실측) → ④ spec-compliance + code-quality 리뷰 → PR. 커버리지 상승은 항상 **회귀 0**를 동반했다.

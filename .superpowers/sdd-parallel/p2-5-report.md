# P2-5 Report: 병렬 set-동등 하드게이트 + speedup (order-service)

**날짜**: 2026-06-24  
**REQ-IDs**: REQ-P004, REQ-P009  
**결과**: **FAIL** — set-동등 불충족 (2개 필드)

---

## 1. 실행 요약

| 항목 | 값 |
|------|---|
| sequential baseline | `e2e/gate-p1-5/pjacoco_run2/graph/graph.json` (P1-5 gate) |
| parallel run | `e2e/gate-p2-5/par4_run/graph/graph.json` |
| `--coverage-backend` | pjacoco (both) |
| `--parallelism seq` | 1 (P1-5 기존 결과 재사용) |
| `--parallelism par` | 4 |
| T_seq | 3978s (66m18s) |
| T_par4 | 1311s (21m51s) |
| **speedup(N=4)** | **3.03x** |

---

## 2. 필드별 set-diff (GraphSetEquivDiffTool)

| 필드 | seq | par4 | 고유 key (seq) | 고유 key (par4) | 판정 |
|------|-----|------|--------------|--------------|------|
| endpoints | 28 | 28 | 28 | 28 | ✅ EQUIVALENT |
| paths | 157 | 157 | 157 | 157 | ✅ EQUIVALENT |
| **sql** | 72 | 263 | 68 unique keys | 224 unique keys | ❌ EXTRA 156, MISSING 0 |
| **httpCalls** | 2 | 0 | 2 | 0 | ❌ MISSING 2 |
| wsEndpoints | 2 | 2 | 2 | 2 | ✅ EQUIVALENT |
| wsExchanges | 3 | 3 | 3 | 3 | ✅ EQUIVALENT |
| seeds | 22 | 22 | 22 | 22 | ✅ EQUIVALENT |
| kafkaConsumers | 2 | 2 | 2 | 2 | ✅ EQUIVALENT |
| kafkaExchanges | 4 | 4 | 4 | 4 | ✅ EQUIVALENT |
| mappers | 1 | 1 | 1 | 1 | ✅ EQUIVALENT |
| tables | 7 | 7 | 7 | 7 | ✅ EQUIVALENT |
| coveredAppBranches | (branch 집합) | (branch 집합) | 미검증(diff only) | | — |

---

## 3. 실패 상세 — 원인 분석

### 3-A. SQL cross-contamination (BUILDER BUG)

**증상**: `sql` 필드에서 병렬 결과가 156개 여분의 SQL key를 가짐.

**원인**: `OtelSpanCapture`가 OTel trace 수신 실패 시 SUT 로그 파서 폴백을 사용한다.
```
[pool-2-thread-N] WARN OtelSpanCapture - otel entry span timeout ... fell back to log-parser (N sql)
```
이 폴백은 `sut.logOffset()` 기반 — 요청 직전/직후 SUT 로그 커서로 SQL을 추출한다.
병렬 실행에서는 **4개 워커가 동시에 SUT에 요청**하므로 로그에 여러 워커의 SQL이 섞인다.
워커 A가 로그 구간 `[logStart_A, logEnd_A]`를 읽을 때 그 구간에는 워커 B/C/D의 SQL도 포함되어,
다른 엔드포인트 SQL이 엉뚱한 pathId로 귀속된다.

**영향 범위**: 60+ pathId에서 SQL 오염 발생 (순수하게 log-parser 폴백 경로를 쓰는 전 케이스).

**분류**: BUILDER BUG — `OtelSpanCapture.logParser()` fallback이 동시 실행 비안전.

**수정 방향** (P2-5 수정 태스크에서 구현):
- 옵션 A: OTel 경로(baggage `test.id` correlation)가 안정적으로 작동하면 log-parser 폴백을 parallel 경로에서 비활성화 (SQL 수 감소하더라도 오염 SQL은 제거됨).
- 옵션 B: log-parser fallback을 per-traceId OTel coverage의 백업으로만 허용하되, 병렬에서는 traceId 기반 SQL row 필터를 추가 (OTEL span과 SQL 로그의 timestamp 정합).
- 옵션 C: log-parser fallback을 완전히 제거하고 OTel SQL capture만 사용 (현 pjacoco + OTel 설정으로 SQL 캡처가 0인 경우도 있어 타당성 재검토 필요).

**즉각 조치**: log-parser fallback이 `--parallelism > 1`이면 SQL을 빈 리스트로 반환 → parallel에서는 OTel만 의존.

### 3-B. httpCalls 누락 (BUILDER BUG)

**증상**: `httpCalls` 필드에서 병렬 결과가 0 (sequential은 2).
누락 항목: `post-api-orders-s201-2|GET|/inventory/stock`, `post-api-orders-s409e409-1|GET|/inventory/stock`.

**원인**: `httpCapture.drainNewExchanges()` (line 1550, EndpointExplorationRunner.java)는
WireMock stub에 대한 호출을 **큐에서 drain**한다. 공유 단일 `HttpCaptureServer` 인스턴스를
4개 워커가 동시에 사용하면, 워커 A가 `drainNewExchanges()`를 호출할 때 워커 B의 `/inventory/stock`
호출을 빼가거나, 반대로 A의 호출이 B에 의해 빼앗길 수 있다 → race on shared drain queue.

**분류**: BUILDER BUG — `HttpCaptureServer.drainNewExchanges()`는 공유 drain이므로 parallel 비안전.

**수정 방향**: per-request WireMock request 추적 (request timestamp 또는 traceId 헤더 기반 필터).
현재 `baggage: test.id=<traceId>`를 SUT에 주입하고 있으므로, WireMock stub이 해당 헤더를 전달한다면
WireMock 요청 로그에서 traceId 기준으로 httpCall을 귀속할 수 있다.

---

## 4. race/seed-collision 검사

| 항목 | 결과 |
|------|------|
| ConcurrentModificationException | **0** |
| JDBC constraint violation | **0** |
| Duplicate entry / seed collision | **0** |
| identity resync skipped (COALESCE type mismatch) | 다수 (sequential과 동일, 알려진 SUT 특성) |
| SUT 500 Internal Server Error | **0** |
| 워커 크래시 / Future.get() 예외 | **0** |
| pjacoco exec-await timeout (WARN) | 있음 (sequential과 동일, WS traceId 포함) |

**결론**: race/seed-collision 자체는 발생하지 않았다. P2-1/2/3/4 구현은 정상 동작.

---

## 5. Speedup 결과

| 항목 | 시간 | speedup |
|------|------|---------|
| sequential (N=1, P1-5) | 3978s (66m18s) | 1.0x |
| parallel N=4 | 1311s (21m51s) | **3.03x** |

speedup은 REQ-P001 맥락의 ~2x를 초과(3.03x). 단, 이는 **오염된 SQL 포함 결과**이므로
set-동등 달성 후 재측정 필요.

---

## 6. 판정

**P2-5 HARD GATE: FAIL** (REQ-P004, REQ-P009 미충족)

- **paths / seeds / endpoints / kafkaConsumers / wsEndpoints 등 9개 필드**: SET-EQUIVALENT ✅
- **sql**: EXTRA 156 (log-parser fallback 동시실행 오염) ❌
- **httpCalls**: MISSING 2 (shared drain race) ❌

---

## 7. 후속 조치

P2-5 FAIL이므로 P1-6(JaCoCo 제거)은 보류. 다음 수정 필요:

### 수정 F1: log-parser SQL fallback — parallel 경로에서 비활성화 (또는 traceId 필터)
- `OtelSpanCapture`: `parallelism > 1` 시 log-parser fallback SQL을 반환하지 않음.
- 근거: OTel trace로 SQL 0이 되더라도 오염 SQL보다 낫다. 추후 OTel 안정화로 해결.

### 수정 F2: HttpCaptureServer.drainNewExchanges() — per-traceId 귀속
- WireMock stub 호출 시 `baggage: test.id=<traceId>` 헤더가 SUT를 통해 WireMock에 전달되면
  WireMock admin API로 해당 traceId의 요청만 필터해 귀속.
- 또는: drain 타이밍 기반 window 격리 (요청 전 drain, 요청 후 drain — diff 계산).

### 대안 F2b: httpCapture per-request timestamp window
- 요청 직전 WireMock 요청 수 기록, 직후 새 요청만 추출.
- 병렬에서는 다른 워커의 호출도 섞일 수 있으므로 완전한 해결책이 아님.

수정 후 P2-5 재실행 필요.

---

## 8. gate-p2-5.sh 하네스

`e2e/parallel/gate-p2-5.sh` 작성 완료:
- `--skip-seq --seq-graph <path>` : 기존 sequential 결과 재사용
- `--par4-and-8` : N=4+8 동시 게이트
- 레이스/시드충돌 로그 검사 + 자동 speedup 계산 포함

---

## 9. F1/F2 수정 후 재게이트 (2026-06-24, commit 189c1d2)

**실행**: `e2e/gate-p2-5/par4_regate_run/` — `--parallelism 4`, pjacoco, T=1211s

| 항목 | 값 |
|------|---|
| par4 재게이트 wall-clock | 1211s (20m11s) |
| speedup (vs seq 3978s) | **3.28x** |
| race/seed-collision | **0** (CME=0, JDBC=0, false-404=0) |

### 9-A. GraphSetEquivDiffTool 결과

| 필드 | seq | par4 | A에만 | B에만 | 판정 |
|------|-----|------|-------|-------|------|
| endpoints | 28 | 28 | 0 | 0 | ✅ EQUIVALENT |
| paths (branch-key) | 157 | 156 | — | — | ✅ EQUIVALENT (tool 기준) |
| **sql** | 68 | 3 | **65** | 0 | ❌ MISSING 65 |
| **httpCalls** | 2 | 0 | **2** | 0 | ❌ MISSING 2 |
| wsEndpoints | 2 | 2 | 0 | 0 | ✅ EQUIVALENT |
| wsExchanges | 3 | 3 | 0 | 0 | ✅ EQUIVALENT |
| **seeds** | 22 | 21 | **1** | 0 | ❌ MISSING 1 |
| kafkaConsumers | 2 | 2 | 0 | 0 | ✅ EQUIVALENT |
| kafkaExchanges | 4 | 4 | 0 | 0 | ✅ EQUIVALENT |
| mappers | — | — | — | — | ✅ EQUIVALENT |
| capturedEventEmits | — | — | — | — | ✅ EQUIVALENT |

**GraphSetEquivDiffTool 판정: NON-EQUIVALENT (3개 필드 실패)**

### 9-B. F1/F2 수정 결과 평가

**F1 (SQL traceId 격리) — 구조 정상, OTel 미도달이 새 문제**

- 수정 효과: log-parser 폴백 완전 비활성 → 오염 SQL 156개 EXTRA 제거 ✅
- 새 문제: OTel 스팬이 병렬 모드에서 timeout (로그에 "otel entry span timeout" 다수)
  → OTLP agent가 8초 내 DB span을 전달하지 않아 par4에서 SQL 3개만 캡처
- 근본 원인: 병렬 4 워커가 동시에 SUT를 호출 → SUT 부하 증가 → OTEL BSP export 지연
  → OTel SQL 캡처 자체가 병렬에서 불안정
- 수정 방향(F1b): OTel timeout을 늘리거나, SUT 부하 기반 timeout 동적 조정,
  또는 병렬에서도 log-parser를 traceId timestamp window로 안전하게 사용

**F2 (HttpCaptureServer per-traceId drain) — 구현 정상, SUT 미전파가 근본 문제**

- 수정 효과: `drainByTraceId(traceId)` 구현 완료 — baggage 기반 필터 ✅
- 새 문제: order-service가 outbound HTTP (inventory 호출) 시 `baggage: test.id=<traceId>`를
  **전파하지 않는다** — `baggagePropagated: false`(sequential graph에서 확인)
- 근본 원인: SUT가 baggage 전파를 지원하지 않으므로 WireMock 이벤트에 test.id baggage 없음
  → `drainByTraceId`가 항상 빈 리스트 반환
- 수정 방향(F2b): 타임스탬프 윈도우 기반 per-worker drain
  (요청 직전 이벤트 수 스냅샷, 요청 후 새 이벤트 추출 — per-worker로 격리)

**seeds MISSING 1 — 재현성 분석 필요**

- `get-api-profiles-by-name-name-s200-1|users|[id,name]` 시드 1개 누락
- 재실행 시 해결될 수 있는 타이밍 이슈이거나, 해당 엔드포인트의 순차 병렬 실행 결과 차이

### 9-C. 판정

**P2-5 재게이트: FAIL** (REQ-P004, REQ-P009 미충족)

| 필드 | 1차(c3cce29) | F1/F2 후(189c1d2) | 변화 |
|------|-------------|------------------|------|
| sql | EXTRA 156 ❌ | MISSING 65 ❌ | 오염 제거됨, OTel 불안정 노출 |
| httpCalls | MISSING 2 ❌ | MISSING 2 ❌ | 동일 (SUT 미전파) |
| seeds | EQUIVALENT ✅ | MISSING 1 ❌ | 신규 불일치 (타이밍?) |

F1b, F2b 추가 수정 필요. P1-6(JaCoCo 제거) 계속 보류.

## §10. 정정 (2026-06-24, 재부팅 후 세션) — httpCalls "SUT 미전파" 판정 철회

§9의 "httpCalls(2): order-service가 baggage 미전파 (SUT-dependent)" 결론을 **철회한다**.

**검증 방법(직접 근거)**: order-service를 OTel javaagent(`OTEL_PROPAGATORS=tracecontext,baggage`)와
WireMock과 함께 부팅하고, `POST /api/orders {type:EXPRESS}`에 `baggage: test.id=<id>`를 주입해
`InventoryClient`(RestTemplate)의 outbound `/inventory/stock` 호출에 baggage가 전파되는지 단정하는
통합 테스트 `OtelHttpCaptureIntegrationTest.inboundBaggage_propagatesToOutboundInventoryCall`를 추가·실행.

**결과**: `exchanges=1 paths=[/inventory/stock] baggagePresent=true`, `drainByTraceId(<id>)` 귀속 성공 →
**baggage는 정상 전파된다**. BUILD SUCCESSFUL.

**오판 원인**: §9의 `baggagePropagated:false`는 `HttpCaptureServer.drainNewExchanges()`의
`baggagePresent` 플래그가 잘못된 토큰 `test-id=`(하이픈)을 검사하던 버그였다 — 실제 주입/전파 토큰은
`test.id=`(점). 플래그를 `test.id=`로 수정. `drainByTraceId`는 처음부터 올바른 `test.id=`를 사용했다.

**그러면 병렬 게이트 httpCalls=0의 진짜 원인은?** baggage 전파가 정상이므로, 0 관측은 부하 하
EXPRESS-주문 요청의 처리/타이밍 또는 통합 경로 문제로 추정 — F1b(SQL await 30s) 적용 빌더로 재게이트해
규명한다. baggage 한계로 게이트 면제하지 않는다. PASS 기준 = 11/11 전 필드 set-동등.

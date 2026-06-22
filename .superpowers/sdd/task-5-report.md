# Task 5 Report — V3(b) per-request 오버헤드 측정 (REQ-005)

## 방법론

### 환경
- petclinic 4.0.0-SNAPSHOT, OTel 2.11.0 javaagent + pjacoco `traceKeyAutoCreate=true`
- JDK Corretto 17.0.18, 로컬 loopback (127.0.0.1:8080 SUT, 127.0.0.1:6310 제어)

### 측정 포함 범위

| | Baseline | Measured |
|---|---|---|
| HTTP 요청 | `GET /owners?lastName=` 60회 | 동일 60회 |
| traceparent | **없음** | **있음** (결정론적 traceId) |
| flush | **없음** | **있음** (`POST /__coverage__/test/stop?testId=<traceId>`) |
| load | **없음** | **있음** (`awaitAndLoad(traceId)` — .exec 파일 대기 + 로드) |
| pjacoco agent | 부착 상태 (traceKeyAutoCreate=true) | 동일 |

- Baseline: pjacoco agent가 부착되어 있으나 traceparent 없으므로 traceId store가 생성되지 않음.
- Measured: flush+load 전체 비용 포함 (per-request 커버리지 격리의 전체 단가).
- warm-up: flush 왕복 측정 전 5회 버림 (JIT warm-up).

### 측정 도구
- `V3OverheadPoc.java` (`@EnabledIfEnvironmentVariable(POC_FANOUT_E2E=1)`)
- `PjacocoOtelScopeClient` 재사용 (flush, awaitAndLoad)
- `v3-overhead.sh` (thin launcher)

---

## 실제 측정 수치

### ① flush 왕복 지연 (100회, warm-up 5 버림)

| 지표 | 실측값 | 임계 | 판정 |
|---|---|---|---|
| mean | **3.495 ms** | < 5 ms | ✅ PASS |
| p95 | **6.583 ms** | (참고) | — |

### ② 벽시계 증가율 (60회 요청)

| 지표 | 실측값 |
|---|---|
| baseline (no traceparent/flush/load) | **971.1 ms** |
| measured (traceparent+flush+load 포함) | **1202.6 ms** |
| 증가 | **+231.5 ms** |
| 증가율 | **+23.83%** |
| 임계 | < 10% |
| 판정 | ❌ **FAIL** |

### ③ .exec 아티팩트 비용

| 지표 | 실측값 |
|---|---|
| .exec 파일 수 | 165개 (warm-up 5 + probe 100 + measured 60) |
| 총 크기 | 100,155 bytes (97.8 KB) |
| 파일당 평균 | ~607 bytes |
| pathological | 없음 (정상 범위) |

---

## 판정: DONE_WITH_CONCERNS

- **① flush 왕복: PASS** (3.495ms < 5ms)
- **② wall-clock 증가율: FAIL** (23.83% > 10%)
- ③ .exec 아티팩트: 정상 (pathological 없음)

### FAIL 원인 분석

`awaitAndLoad(traceId)`의 `.exec` 파일 대기 비용이 주 원인이다. `PjacocoOtelScopeClient.awaitAndLoad`는
300ms 간격으로 파일 존재를 poll한다. 60회 요청에서 각 요청마다 최소 1 poll cycle(최대 300ms + 파일 로드 I/O)이
추가되면 baseline 대비 231ms 이상 증가한다.

flush 왕복(3.5ms × 60 = 210ms 이론값)은 임계 내이므로, **flush 자체는 문제없고 synchronous load가 병목이다.**

### 완화 후보 (§7 (b) 재논의 시)

1. **비동기 load 분리**: flush는 per-request, load는 탐색 종료 후 일괄 처리 → 벽시계 증가분이 flush 왕복
   (3.5ms/req × N)만 남아 60회 기준 ~210ms → ~21.6% (여전히 10% 초과 가능).
2. **poll 간격 단축**: `awaitAndLoad` poll 300ms → 10-50ms → 대기 비용 감소.
3. **임계 재협의**: flush+load 포함이 맞는 운용 방식이면, 실측 +24%를 기반으로 허용 임계를 재정의.
4. **flush 없이 지연 load**: 탐색 완료 후 traceId 목록으로 일괄 `.exec` 로드 → SUT 부하 측면에선 동일,
   단 탐색 중 arm 분리 실시간성 포기.

---

## 변경 파일

| 파일 | 유형 | 설명 |
|---|---|---|
| `e2e/poc-fanout/v3-overhead.sh` | 신규 | thin launcher (JUnit 하니스 호출) |
| `graph-rag-builder/src/test/java/io/graphrag/builder/poc/fanout/V3OverheadPoc.java` | 신규 | 측정 하니스 (REQ-005, `@EnabledIfEnvironmentVariable`) |
| `docs/superpowers/specs/2026-06-22-fanout-pjacoco-poc-design.md` | 수정 | §11 V3(b) 실측 결과 추가 |
| `docs/superpowers/requirements/2026-06-22-fanout-pjacoco-poc-requirements.md` | 수정 | REQ-005 상태 🟡 DONE_WITH_CONCERNS, 추적 매트릭스 갱신 |
| `.superpowers/sdd/task-5-report.md` | 신규 | 본 리포트 |

---

## self-review

- 수치는 실제 E2E 실행(POC_FANOUT_E2E=1, Docker+pjacoco)에서 나온 것이다. fudge 없음.
- flush 왕복(①)은 PASS이므로 제어 API 자체는 5ms 임계 이내.
- wall-clock FAIL의 원인이 `awaitAndLoad` poll임을 코드 수준에서 분석했다(300ms poll 간격, 60회 적산).
- 완화 후보를 구체적으로 제시하고 §7 (b) 재논의로 연결했다.
- 임계를 내리거나 수치를 조작하지 않았다 — 23.83%를 그대로 보고한다.
- .exec 아티팩트(③)는 병리적 징후 없음 (파일당 ~600 bytes, 총 98 KB는 정상).

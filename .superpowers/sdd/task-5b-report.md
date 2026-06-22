# Task 5b Report — V3(b) production-model 재측정 (REQ-005)

## 배경 / 재측정 근거

이전 V3(b) 측정(`task-5-report.md`)은 per-request 루프 안에서 `awaitAndLoad(traceId)`를
동기 호출했다 — 이 메서드는 `.exec` 파일이 생성되기까지 300ms 간격으로 폴링한다(최대 5000ms).
60회 요청에서 poll 비용이 적산되어 wall-clock +23.83%(FAIL)가 나왔다.

그러나 이 300ms poll은 **production fan-out에 존재하지 않는다**. Production fan-out에서
`.exec` 로드는 run 종료 후 post-processing 단계에서 일괄 처리된다 — 요청당 critical-path에
포함되지 않는다. §7(b) 결정(secretary safe_default: escalate if still over)에 따라
production-accurate 모델로 재측정한다.

---

## Production-model 측정 범위

| | Baseline | Measured (production model) |
|---|---|---|
| HTTP 요청 | `GET /owners?lastName=` 60회 | 동일 60회 |
| traceparent | 없음 | 있음 (결정론적 traceId) |
| flush | 없음 | 있음 (`POST /__coverage__/test/stop?testId=<traceId>`) |
| load (awaitAndLoad) | 없음 | **없음** — 루프 밖(post-run)에서 호출 |
| pjacoco agent | 부착 상태 | 동일 |

- flush는 per-request critical-path에 포함.
- `.exec` load(awaitAndLoad)는 루프 종료 후 일괄 처리 → 별도 post-run load 시간 보고.
- warm-up: flush 왕복 측정 전 5회 버림.

---

## 실제 측정 수치

### ① flush 왕복 지연 (100회, warm-up 5 버림)

| 지표 | 실측값 | 임계 | 판정 |
|---|---|---|---|
| mean | **4.113 ms** | < 5 ms | ✅ PASS |
| p95 | **7.314 ms** | (참고) | — |

### ② 벽시계 증가율 — production-model (60회 요청)

| 지표 | 실측값 |
|---|---|
| baseline (traceparent/flush/load 없음) | **1028.4 ms** |
| measured production-model (traceparent+flush, load 제외) | **1190.9 ms** |
| 증가 | **+162.5 ms** |
| 증가율 | **+15.80%** |
| 임계 | < 10% |
| 판정 | ❌ **FAIL** |

### ③ post-run load (off critical path, 60개 .exec 일괄)

| 지표 | 실측값 |
|---|---|
| post-run load 시간 | **28.2 ms** |
| 적용 임계 | 없음 (off critical path) |
| 비고 | fan-out에서 run 이후 amortized, 사용자 대기 시간 아님 |

### ④ .exec 아티팩트 비용

| 지표 | 실측값 |
|---|---|
| .exec 파일 수 | 165개 (warm-up 5 + probe 100 + measured 60) |
| 총 크기 | 100,155 bytes (97.8 KB) |
| 파일당 평균 | ~607 bytes |
| pathological | 없음 (정상 범위) |

---

## 이전 모델과 비교

| 모델 | wall-clock 증가율 | 판정 |
|---|---|---|
| 이전 (flush+load 동기, task-5) | +23.83% | ❌ FAIL |
| **production-model (flush only, task-5b)** | **+15.80%** | ❌ **FAIL** |
| 임계 | < 10% | |

load off critical path 효과: 23.83% → 15.80% (-8.03%p). 그러나 여전히 10% 임계를 초과한다.

---

## 판정: STILL-OVER — 에스컬레이션

- **① flush 왕복: PASS** (4.113ms < 5ms)
- **② wall-clock 증가율 (production-model): FAIL** (15.80% > 10%)
- ③ post-run load: 28.2ms (off critical path, 임계 적용 없음)
- ④ .exec 아티팩트: 정상

### FAIL 원인 분석

Production-model에서 critical-path 비용은 flush 왕복뿐이다.
이론값: flush mean 4.113ms × 60 = 246.8ms. baseline 1028.4ms 대비 24% 이지만,
실측 증가는 162.5ms(15.80%)로 이론보다 낮다 — HTTP 연결 재사용과
pjacoco 내부 비동기 처리로 일부 중첩이 발생한 것으로 보인다.

따라서 10% 임계 초과의 원인은 **flush 왕복 자체의 누적 비용**이다:
- flush 1회 = ~4ms → 60회 × 4ms ≈ 240ms 순증
- baseline 1028ms 대비 ~23.3% (이론), 실측 15.80% (일부 중첩)
- load를 off critical path로 옮겨도 flush 비용 자체가 10% 임계를 초과한다.

### 에스컬레이션 필요 항목

safe_default 규칙(escalate if still over)에 따라 사용자와 재논의가 필요하다:

1. **임계 재협의**: flush 4ms × N이 production에서 수용 가능한지. fan-out 병렬도(N개 worker)가
   높아지면 각 worker의 요청 수가 줄어 총 overhead 비율도 줄어든다(단일 worker 60req 기준이라
   실제 fan-out에선 더 낮을 수 있음). 예: 10개 worker×6req = 각 ~24ms flush, baseline ~100ms → +24%.
2. **flush 비동기화**: flush를 fire-and-forget (응답 대기 없이)으로 하면 critical-path에서 제거
   가능. 단, flush 실패 감지가 불가하다.
3. **flush 배치화**: 요청마다 즉시 flush 대신, run 종료 시 일괄 flush. coverage 정확도(중간 실패
   시 누락) 트레이드오프 있음.
4. **임계 30% 이하 허용**: loopback 환경 특성상 flush 4ms가 절대적으로 작다. 실환경(LAN 대비
   loopback은 0.5ms 내외)에서도 비슷하거나 더 좋을 수 있다.

---

## 변경 파일

| 파일 | 유형 | 설명 |
|---|---|---|
| `graph-rag-builder/src/test/java/io/graphrag/builder/poc/fanout/V3OverheadProductionPoc.java` | 신규 | production-model 측정 하니스 (REQ-005, `@EnabledIfEnvironmentVariable`) |
| `docs/superpowers/specs/2026-06-22-fanout-pjacoco-poc-design.md` | 수정 | §11 V3(b) production-model 재측정 결과 추가 |
| `docs/superpowers/requirements/2026-06-22-fanout-pjacoco-poc-requirements.md` | 수정 | REQ-005 상태 🟡 STILL-OVER 에스컬레이션 추가 |
| `.superpowers/sdd/task-5b-report.md` | 신규 | 본 리포트 |

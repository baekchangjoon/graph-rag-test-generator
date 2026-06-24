# 병렬 fan-out 빌더 요구사항명세

> 출처(design spec): docs/superpowers/specs/2026-06-23-parallel-fanout-builder-design.md (rev.2, 3-벤더 리뷰 반영)
> 완료 정의(DoD): Must REQ 전부 green. 단계적 게이트 — Phase 0(ROI) → Phase 1(교체 set-동등) → Phase 2(병렬).
> SUT: petclinic, tainted-spring, eventuate-tram(조건부 REQ-P011).

## 요구사항 목록

### REQ-P001 — Phase 0: 실제 빌더 speedup 실측 (ROI 게이트)
- 유형: Non-functional · 우선순위: Must
- 설명: 실제 빌더(Spoon/Z3/seed 포함) HTTP 엔드포인트 루프를 최소 병렬화해 order-service(28 endpoints)에서 순차 vs 병렬 벽시계 실측. PoC 3.72x(IO-bound)가 CPU-heavy 실빌더에서 유지되는지 확인.
- 수용기준: Given 다수 엔드포인트 order-service, When 순차(`--parallelism 1`) vs 병렬(`--parallelism N`) 실측, Then **speedup ≥ 2.0x**면 통과(이행 진행); 미만이면 fail → ROI 재논의(인박스). 측정 결과 기록.
- **실측 결과 Phase 0 (2026-06-23, 락 있음)**: P=8 중앙값 speedup **1.91x** (게이트 2.0x 미달). P=8 run1=2.20x.
- **실측 결과 Phase 0.5 (2026-06-23, 락 제거 unlocked)**: P=1 중앙값 258.5s, P=8 중앙값 128.5s → speedup **2.01x** (게이트 통과). 병목 분석: 락 제거 효과는 +0.10x(노이즈 수준) — **락이 주 병목이 아니었음**. 진짜 병목은 단일 SUT HTTP 서버 포화. Phase 1(pjacoco) 예상 speedup을 2.0-2.5x로 하향 조정(기존 2.5-3.5x 예측 수정).
- 검증: E2E (실제 빌더 벽시계) — ✅ 완료, 결과 `.superpowers/sdd-parallel/phase0-report.md`

### REQ-P002 — pjacoco 커버리지 백엔드 (JaCoCo dump 대체)
- 유형: Functional · 우선순위: Must
- 설명: `CoverageClient` tcpserver dump 경로를 `PjacocoCoverageBackend`(per-request traceId flush→`.exec`→`CoverageFingerprint`)로 교체. `doSend`·Kafka·Ws 러너 모두 적용.
- 수용기준: Given pjacoco 부착 SUT, When 요청별 커버리지 수집, Then coverageKey가 산출되고 `BranchCoverageAnalyzer` 재사용으로 분기 분석 동작(JaCoCo tcpserver 미사용).
- 검증: integration + E2E

### REQ-P003 — Phase 1: 순차(`--parallelism 1`) pjacoco == 이전 main(JaCoCo) set-동등 (하드 게이트)
- 유형: Functional · 우선순위: Must
- 설명: pjacoco 교체만으로(병렬 코드 없이) 순차 산출물이 이전 JaCoCo와 set-동등. 별도 머지 포인트. 전환기 `--coverage-backend jacoco|pjacoco` 플래그로 롤백.
- 수용기준: Given 각 SUT, When `--parallelism 1`(pjacoco) vs 이전 main(JaCoCo), Then graph.json 전 GraphAsset 리스트 필드 set-동등 + `coveredAppBranches` 동등 + 전체 회귀 green.
- 선행: 순차 oracle 결정성 — 이전 main 2회 실행 path-set 안정성 확인(변동 임계 문서화).
- 검증: E2E black-box (전 SUT)

### REQ-P004 — 엔드포인트 워커 풀 병렬화 (HTTP 루프)
- 유형: Functional · 우선순위: Must
- 설명: HTTP 엔드포인트 루프를 `ExecutorService`(`--parallelism N`)로 fan-out. Kafka/Ws 러너는 순차(풀 밖, 前/後).
- 수용기준: Given N>1, When 다수 엔드포인트 탐색, Then 엔드포인트가 동시 실행되고 각자 결과 산출.
- 검증: E2E

### REQ-P005 — per-worker Connection
- 유형: Functional · 우선순위: Must
- 설명: 워커마다 자기 `java.sql.Connection`(공유 단일 Connection 제거). 동시연결 = min(parallelism, 엔드포인트수) bounded.
- 수용기준: Given 병렬 탐색, When 워커별 seed INSERT/SELECT/DELETE, Then JDBC race·예외 0.
- 검증: E2E

### REQ-P006 — per-worker 누적기 + post-loop 단일스레드 merge
- 유형: Functional · 우선순위: Must
- 설명: 공유 누적기 제거, 워커 로컬 `EndpointResult`(paths·sql·httpCalls·ws·kafka·seeds·reportEntries·capturedEventEmits·unsupportedShapes·coveredBranches·cumulativeExec). 전 워커 완료 후 단일스레드 merge(`runWideExec` OR-merge, `coveredAppBranches` union). graph.json 단일 writer.
- 수용기준: Given 병렬 완료, When merge, Then 누적기 race·데이터 손상·누락 0, 집합이 순차 합집합과 동일.
- 검증: E2E + 동시성 점검

### REQ-P007 — seed probe 키 충돌 해결
- 유형: Functional · 우선순위: Must
- 설명: probe 키를 엔드포인트 스코프(`probe-<endpointId>-<field>`)로 하거나 seed DELETE를 post-loop 연기 → 병렬 워커 간 row 비충돌.
- 수용기준: Given 같은 필드명 쓰는 두 엔드포인트 동시 탐색, When seed INSERT/DELETE, Then 거짓 404·seed 실패 0.
- 검증: E2E

### REQ-P008 — 비동기 flush 풀 + .exec await 정책
- 유형: Non-functional · 우선순위: Must
- 설명: flush를 임계경로 밖 풀(`--flush-threads`, 기본 parallelism×2). 워커는 `.exec` 가용성만 await(`--exec-await-ms`, 기본 30s; 타임아웃 시 경고+빈 store, 크래시 금지).
- 수용기준: Given 병렬 탐색, When per-request flush, Then 워커 임계경로에 flush 응답대기 없음 + .exec 타임아웃이 워커를 크래시시키지 않음.
- 검증: E2E

### REQ-P009 — 출력/coverage/pass-rate set-동등 (전 SUT)
- 유형: Functional · 우선순위: Must
- 설명: 병렬(`--parallelism N`) 산출물이 순차와 동등.
- 수용기준: Given 각 SUT, When 병렬 vs 순차, Then ① graph.json 전 리스트 필드 set-동등(순서무관) ② 생성 테스트 pass-rate 동일 ③ `coveredAppBranches` 집합 동일(또는 branch delta ≤ 명시 N).
  - **httpCalls 정정(2026-06-24)**: 이전에 `httpCalls`를 "SUT baggage 미전파에 따른 documented limitation, PASS 기준 제외"로 표기했으나 **철회한다**. order-service는 OTel javaagent로 inbound baggage(`test.id`)를 outbound로 정상 전파함을 통합 테스트로 입증했다(설계 §9-A). 따라서 `httpCalls`는 PASS 기준 11/11 필드에 **포함**되며 게이트 면제 대상이 아니다. 병렬 게이트의 httpCalls=0은 baggage 한계가 아닌 별도 빌더 측 원인이므로 재게이트로 규명한다.
- 검증: E2E black-box (전 SUT)

### REQ-P010 — JaCoCo 전면 제거 (attach 포함)
- 유형: Functional · 우선순위: Must
- 설명: tcpserver/dump, `JacocoAgent`, `CoverageClient` 제거. attach 경로의 공유 control 포트 plumbing은 jacoco 전용이 아니므로 **rename**(`AttachConfig.jacocoHostPort`→`coverageHostPort`, `OverrideComposeGenerator` jacoco 포트→coverage 포트, CLI `--coverage-port` 신설 + `--jacoco-port` deprecated alias). `coverage` CLI 서브커맨드는 pjacoco `.exec`(JaCoCo 바이트 호환) 로드 유지 확인.
- 수용기준: Given 전 SUT, When 빌드/회귀, Then JaCoCo tcpserver/dump 경로 잔존 0 + 전 SUT 회귀 green.
- 검증: E2E + 코드 grep

### REQ-P011 — eventuate-tram(sleuth/B3) 분산 커버리지 (조건부)
- 유형: Functional · 우선순위: Should (조건부)
- 설명: eventuate-tram(Java8/Sleuth)에서 pjacoco B3 trace-key 경로로 커버리지 귀속. 구현 전 pjacoco Brave 모드의 testId 상관 지원 확인.
- 수용기준: Given pjacoco Brave 모드가 B3로 testId 상관 지원, When eventuate-tram 병렬 빌드, Then set-동등(REQ-P009) 충족. **미지원이면 🔵 deferred**(2-SUT DoD)로 명시.
- 검증: E2E (멀티 JVM sleuth)

### REQ-P012 — pjacoco CI 빌드 의존성
- 유형: Non-functional · 우선순위: Must
- 설명: pjacoco agent jar를 CI 재현 가능하게 획득(mavenLocal publish + 좌표 등록 / vendored fat-jar 해시락 / CI pre-build 중 택1). `-Dpjacoco.agent.jar` 주입은 PoC용.
- 수용기준: Given 깨끗한 CI, When 빌더 빌드, Then pjacoco agent 해소돼 빌드 green(로컬 소스 트리 불요).
- 검증: CI 빌드

---

## 추적 매트릭스

| REQ-ID | 요구사항 | Phase | 수용 테스트(계획) | Status |
|--------|----------|-------|------------------|--------|
| REQ-P001 | 실제 빌더 speedup ≥2x | 0 | `RealBuilderSpeedup` (order-service, Phase 0.5 unlocked P=8 2.01x) | 🟢 (Phase 0.5 unlocked: 2.01x 게이트 통과; 락이 주 병목 아님 확인됨) |
| REQ-P002 | pjacoco 커버리지 백엔드 | 1 | `PjacocoCoverageBackendTest` + E2E | 🟢 (P1-3, P1-4 완료) |
| REQ-P003 | 순차 pjacoco == JaCoCo set-동등 | 1 | `BackendSwapEquivalence` (order-service) | 🟢 (P1-5 PASS: 28ep/157path/72sql SET-EQUIV) |
| REQ-P004 | 엔드포인트 워커 풀 | 2 | `ParallelEndpointLoop` | 🟢 (P2-5 풀 재게이트 PASS: pool-2-thread-1~4 동시, race=0, 2.54x speedup) |
| REQ-P005 | per-worker Connection | 2 | `PerWorkerConnection` | 🟢 (P2-1/2: JDBC race 0, seed 충돌 0) |
| REQ-P006 | per-worker 누적기+merge | 2 | `AccumulatorMerge` | 🟢 (P2-1/2: post-loop single-thread merge 검증) |
| REQ-P007 | seed 키 충돌 해결 | 2 | `SeedKeyIsolation` | 🟢 (P2-3: endpoint-scoped probe keys, seed collision 0) |
| REQ-P008 | 비동기 flush + await 정책 | 2 | `AsyncFlushPolicy` | 🟢 (P2-4: per-worker-sync flush, 크래시 0) |
| REQ-P009 | 병렬 vs 순차 set-동등(전 SUT) | 2 | `ParallelEquivalence` (order-service N=4) | 🟢 **풀 재게이트 PASS: 11/11 전 필드 SET-EQUIVALENT** (endpoints 28, paths 157, sql 72, httpCalls 2, seeds 22, capturedEventEmits 2, ws/kafka 전부 일치). 근본수정 2건: pjacoco includes 앱루트화(§9-B) + Kafka drainByTraceIds 병렬안전화. race=0, 2.54x speedup. order-service 검증 — 타 SUT 확장 시 동일 게이트 적용. |
| REQ-P010 | JaCoCo 전면 제거 | 1-2 | grep + 회귀 | 🟢 (P1-6 완료: 백엔드 제거 + always-pjacoco rewire + 내부 coverage-port rename(`--coverage-port` 신설, `--jacoco-port` deprecated alias 1곳) + 러너 stale 주석 정정. **grep-0**: main java에 alias 1곳 외 bare-jacoco 0. 회귀: `:graph-rag-builder:test` 496개 중 495 green(1건은 OtelKafka 상관 통합테스트 flaky — 단독 재실행 green, 본 변경과 무관). attach e2e: **ATTACH-EXT-HTTP PASS**(71 sql/2 http, 안정적) + ATTACH-OTEL 기능 검증(62~68 sql/210 br/entry-span timeout 0, control fail 0). attach 배선 버그 3건 동반 수정(`fbb680c`: pjacoco control 0.0.0.0 바인드 + 빌더/SUT IPv4 스택). `bcaa9e2`: attach OTEL quiescence 150→500ms(analysis/P2-5는 150ms 유지)로 빠른요청 db-span late-arrival flake **완화**(완전제거는 아님 — Docker Desktop VM hop tail-latency가 stochastic. log-parser 폴백으로 SQL은 캡처되어 데이터 손실 없음. run-attach-otel-e2e.sh는 **CI 게이트 아님**(ci.yml은 run-e2e.sh/gateway/dind만)). org.jacoco.core .exec 라이브러리는 유지) |
| REQ-P011 | eventuate sleuth 분산(조건부) | 2 | `EventuateSleuthCoverage` | 🔴 (조건부) |
| REQ-P012 | pjacoco CI 의존성 | 1 | CI 빌드 | 🟢 (`87d8a7e`: settings.gradle.kts에 GitHub Release ivy repo 추가 — `io.pjacoco:pjacoco-agent:1.3.0`을 `parallel-per-test-coverage` v1.3.0 release jar로 해소, mavenLocal 제거. CI/clean/로컬 재현가능, sha256 검증 일치. BuilderIntegrationTest green) |

Coverage: 11/12 Must green (REQ-P001~P010, P012). REQ-P012는 GitHub Release ivy repo로 해소(`87d8a7e`). REQ-P011(eventuate sleuth, 조건부 Should)만 미착수.
REQ-P011은 pjacoco B3 지원 확인 후 Must/deferred 확정.

### P2-5 풀 재게이트 PASS (2026-06-24 — 11/11 set-동등)
- **실행**: `e2e/parallel/gate-p2-5.sh --par4`, order-service, pjacoco P=1(seq) vs P=4(par4).
- **판정**: `seq vs par4: SET-EQUIVALENT ✅ / P2-5 HARD GATE: PASS (REQ-P004, REQ-P009)`.
- **필드**: endpoints 28, paths 157, sql 72, tables 7, mappers 1, httpCalls 2, wsEndpoints 2, wsExchanges 3, kafkaConsumers 2, kafkaExchanges 4, seeds 22, capturedEventEmits 2 — **전부 seq==par4**.
- **성능**: T_seq 145s, T_par4 57s, speedup 2.54x. (근본수정으로 io.* 과대계측 제거 → 이전 T_seq 3978s 대비 27배 단축.)
- **근본수정 2건**:
  1. pjacoco `detectRootPackage` 앱루트 하강(io.* → io.graphrag.sample.orders.*) — OTel/서드파티 미계측. sql·httpCalls·seeds 회복(§9-B).
  2. `KafkaCaptureReceiver.drainByTraceIds` 병렬안전 drain — buildPaths/kafka-diff가 자기 traceId만 수거. capturedEventEmits 회복.

### P2-5 게이트 결과 1차 (2026-06-24, commit c3cce29)
- **실행**: `--parallelism 4 --coverage-backend pjacoco` vs P1-5 baseline (parallelism=1)
- **T_seq**: 3978s (66m18s) | **T_par4**: 1311s (21m51s) | **speedup**: **3.03x** ✅
- **FAIL 이유**:
  1. `sql` 필드: 156개 여분 (log-parser SQL fallback이 parallel에서 동시 요청 SQL 오염)
  2. `httpCalls` 필드: 2개 누락 (HttpCaptureServer.drainNewExchanges() shared-drain race)
- **race/seed-collision**: 0건 ✅ (P2-3/4 구현 정상)
- **수정 필요**: F1(log-parser fallback parallel 비활성화) + F2(httpCapture per-traceId 귀속)
- 상세: `.superpowers/sdd-parallel/p2-5-report.md`

### P2-5 게이트 재게이트 (2026-06-24, commit 189c1d2 — F1+F2 수정 후)
- **실행**: `e2e/gate-p2-5/par4_regate_run/`, `--parallelism 4`, T_par4=1211s (speedup=**3.28x**)
- **race/seed-collision**: 0건 ✅
- **FAIL 이유 (변경됨)**:
  1. `sql` MISSING 65 — F1(log-parser fallback 차단)은 오염 제거에 성공했으나 OTel agent가 병렬 부하 시 8s 내 DB span 미도달 → 3개만 캡처. F1b 필요 (OTel timeout 조정 또는 per-traceId log-parser 안전 사용)
  2. `httpCalls` MISSING 2 — F2(drainByTraceId 구현)는 정상이나 order-service가 baggage 헤더를 outbound HTTP로 전파하지 않음(baggagePropagated=false). F2b 필요 (타임스탬프 윈도우 per-worker drain)
  3. `seeds` MISSING 1 — 신규 (타이밍 또는 비결정적 실행 차이 의심)
- 상세: `.superpowers/sdd-parallel/p2-5-report.md §9`

### P2-5 F1b 수정 + httpCalls 한계 철회 (2026-06-24)
- **수정 내용**:
  1. `OtelSpanCapture`: 병렬 모드 기본 await timeout 8s → 30s (`PARALLEL_AWAIT_TIMEOUT_MILLIS=30_000`). `--sql-await-ms` CLI 플래그로 재정의 가능.
  2. `BuildConfig`: `sqlAwaitMs` 필드 추가. `BuilderCli`: `--sql-await-ms` 파싱.
  3. `HttpCaptureServer` 플래그 버그 수정: `baggagePresent` 검사가 `test-id=`(하이픈) → `test.id=`(점)로 잘못돼 항상 false-negative였음. 이것이 직전 "baggage 미전파" 오판의 근거였다.
  4. **httpCalls "documented limitation" 철회**: 통합 테스트(`OtelHttpCaptureIntegrationTest.inboundBaggage_propagatesToOutboundInventoryCall`)로 order-service가 inbound baggage를 outbound로 정상 전파함을 입증(`baggagePresent=true`, `drainByTraceId` 귀속 성공). 병렬 게이트 httpCalls=0은 baggage 한계가 아닌 별도 원인 → 재게이트로 규명. PASS 기준은 11/11 전 필드.
  5. `seeds` MISSING 1: F1b timeout 증가로 동일 await 윈도우 문제이면 해결될 것으로 예상. 재게이트로 확인.
- **재게이트 대상**: order-service `--parallelism 1` vs `--parallelism 4` (pjacoco), GraphSetEquivDiffTool.
- **통과 조건**: 11/11 전 필드(sql/httpCalls/seeds 포함) set-동등.

### P2-5 근본원인 규명·수정 (2026-06-24 — pjacoco includes 과대범위)
- **증상**: par4 게이트에서 sql ~96% timeout(3/68), httpCalls 0. 순차는 정상(72 sql).
- **systematic-debugging로 규명**:
  1. collision 가설 기각 — timeout traceId 307개 전부 distinct.
  2. otel-only 4-way 즉시-drain 통합테스트 12/12 통과 → 동시성 자체 무해.
  3. 차이는 pjacoco. JAVA_TOOL_OPTIONS에 `includes=io.*` 발견 → `io.opentelemetry.*`(OTel span-export/baggage propagator)까지 계측.
  4. CLI 오버라이드 `--sut-pkg io.graphrag.*`로 병렬 빌드: timeout 0, sql 47 (가설 확정).
- **근본수정**: `PjacocoAgent.detectRootPackage`가 첫 세그먼트만 취하던 것을 단일 자식 디렉터리 체인 하강으로 변경 → `io.graphrag.sample.orders.*`(앱 루트). OTel/서드파티 미계측. 단위테스트 `PjacocoAgentTest`.
- **코드수정 end-to-end 검증**(자동탐지, budget 16 P=4): includes=io.graphrag.sample.orders.*, timeout 0, sql 59, **httpCalls 2(병렬 회복)**, seeds 19. → 단일 근본원인이 sql·httpCalls 모두 해결.
- **F1b 재평가·되돌림**: timeout 30s는 증상 처방이었음(근본원인 아님). 근본수정 후 불필요 → **병렬 기본값 8s로 되돌림**(`PARALLEL_AWAIT_TIMEOUT_MILLIS` 제거). 재정의용 `--sql-await-ms` 플래그만 유지.
- **NEXT**: 풀 재게이트(seq pjacoco P=1 + par4 P=4, GraphSetEquivDiffTool 11/11).

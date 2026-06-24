# 병렬 fan-out 빌더 — 설계 rev.2 (pjacoco 전면 교체, 단계적)

- 작성일: 2026-06-23 (rev.2 — 3-벤더 리뷰 반영)
- 상태: 설계 — 사용자 검토 대기
- 브랜치/worktree: `feat-parallel-fanout-builder`
- 선행: PoC `feat-fanout-pjacoco-poc`(PR #86, 11/11, IO-bound 3.72x)
- 결정(인박스 by:user): **B) pjacoco 전면 교체** — 단일 코드경로, JaCoCo 제거 + 전 SUT 회귀 재검증
- 경로 표기: 파일은 `graph-rag-builder/src/main/java/io/graphrag/builder/...` 기준

---

## 1. 목표 / 비목표

**목표**: 빌더 탐색을 **엔드포인트 단위 병렬 fan-out**으로 전환해 벽시계를 단축(순차 120분/피처패키지). 커버리지를 **pjacoco per-trace 격리로 전면 교체**(JaCoCo 제거), 산출물(graph.json·pass-rate·coverage)이 순차 대비 **순서무관 set-동등**임을 전 SUT에서 검증.

**비목표**: 입력 단위 병렬, 생성 테스트 실행 병렬화, SUT 내부 경합 제거(식별·보고만).

> **ROI 주의(리뷰 I2)**: PoC 3.72x는 **IO-bound HTTP-only 하니스** 측정값(Spoon/Z3/seeding 없음). 실제 빌더는 per-endpoint가 CPU-heavy(ConstraintExtractor Spoon AST, ConcolicOracle ASM+Z3, seed round-trip)라 코어 경합으로 **실측 speedup은 3.72x보다 낮을 수 있음**. 따라서 **Phase 0(아래 §6)에서 실제 빌더 speedup을 먼저 실측**해 ROI를 확인한 뒤 전면 이행한다.

## 2. 산출물 동등성 기준 (사용자 확정 + 조건화)

**순서무관 set-동등** — 병렬 graph.json이 순차와 byte-동일일 필요 없음. 각 엔드포인트의 **모든 `GraphAsset` 리스트 필드**(paths·sql·httpCalls·wsExchanges·kafkaExchanges·allSeeds·**capturedEventEmits**)가 집합으로 동일하면 OK.

> **조건(리뷰 I6/I9)**: set-동등은 **pjacoco partition이 해당 SUT에서 JaCoCo와 등가**임을 전제로 성립한다(절대 키는 다름 — PoC §5.1). pjacoco의 넓은 귀속(JPA/async)이 coverage-guided 입력 선택을 바꿔 **다른/적은 경로를 발견**할 수 있으므로, **§6 Phase 1의 교체-회귀 게이트가 SUT별로 이를 검증**한다(불일치 시 그 SUT는 fail로 드러남). §2는 "검증된 전제 하의" set-동등이다.

## 3. 현재 구조와 병렬화 장벽 (코드 근거)

순차 흐름(`cli/BuilderCli.java explore()`):
- 공유 가변 누적기: `paths/sql/httpCalls/wsExchanges/kafkaExchanges/allSeeds/reportEntries/capturedEventEmits/unsupportedShapes` (ArrayList) + **`coveredAppBranches`(LinkedHashSet)** + **`runWideExec`(jacoco `ExecutionDataStore`)** (BuilderCli.java 누적기 블록).
- **단일 `Connection`**: `try (Connection connection = env.openConnection())`(L546, `DriverManager.getConnection` per call — 풀 아님)을 **HTTP·Kafka·WS 러너 모두 공유**.
- **세 개의 순차 루프**: KafkaCaptureRunner(엔드포인트 루프 前) → HTTP 엔드포인트 루프 → WsCaptureRunner(後). 셋 다 같은 단일 `CoverageClient`·`connection` 사용.
- `run/EndpointExplorationRunner.doSend`: 요청마다 `coverage.dump(reset=true)`(`coverage/CoverageClient` tcpserver) → `CoverageFingerprint.of(delta, appClasses)` → `coverageKey`. (Kafka/Ws 러너도 `coverage.dump(true)` 사용.)
- 끝에 단일 `GraphAsset` → `JsonFileGraphStore.save`(graph.json 1회) + `PartitionedGraphStore.save`.

**병렬화 장벽**: ① 공유 누적기(ArrayList+LinkedHashSet+ExecutionDataStore) 동시쓰기 race ② 공유 단일 Connection JDBC race ③ JaCoCo dump(reset) 전역 오염 ④ **seed probe 키 충돌**(아래).

> **seed 키 충돌(리뷰 I3 — 신규 critical)**: probe 값이 `SampleInputSynthesizer`에서 `"probe-" + field.name()`로 **필드명 스코프**(엔드포인트 스코프 아님). `Seeds.java`(L19 주석)도 "여러 endpoint가 같은 probe row 공유 가능 → 멱등 INSERT" 명시. 즉 같은 필드명(예 `ownerId`)을 쓰는 두 엔드포인트가 **같은 row**를 seed. 병렬이면 워커 A의 cleanup DELETE가 워커 B가 막 INSERT한 row를 지워 **거짓 404/실패**. → §4.6에서 해결.

## 4. 설계 (B — pjacoco 전면 교체, 단계적)

### 4.1 커버리지 백엔드 교체 (JaCoCo → pjacoco) — trace-mode 조건부
- **agent attach**: `env/AnalysisEnvironment`(비-attach)와 `BuilderCli.runAttached`(attach, L374~)의 JaCoCo agent를 **pjacoco agent**로 교체. attach 경로의 `JacocoAgent.containerJavaToolOptions`·jar copy·`jacocoContainerPort`·`AttachConfig.jacocoHostPort`·`OverrideComposeGenerator` jacoco 포트 매핑도 제거/대체(리뷰 I3).
- **trace-mode 분기(리뷰 I5/I8 — eventuate 대응)**:
  - `otel` 모드: OTel→pjacoco, `traceKeyAutoCreate=true`, 요청 traceparent → OTel-scope store. (petclinic·tainted-spring)
  - `sleuth` 모드(eventuate-tram, Java8/Brave): OTel javaagent 미부착(Brave 충돌). pjacoco **Brave/B3 trace-key 경로** 사용 — testId 주입이 OTel baggage가 아니라 B3 헤더 기반인지 **구현 전 pjacoco 인터페이스 확인 필수**. 동일 `PjacocoCoverageBackend` 인터페이스로 흡수 가능하면 흡수, 아니면 `PjacocoSleuthCoverageBackend` 어댑터 신설.
  - `none` 모드: 병렬 비대상(직렬, 기존 동작).
- **per-request 백엔드**: `coverage/CoverageClient` 제거 → **`coverage/PjacocoCoverageBackend`** 신설(PoC `PjacocoOtelScopeClient` 프로덕션화): 요청 traceId → flush(`/__coverage__/test/stop?testId=<traceId>`) → `<traceId>.exec` → `ExecFileLoader` → `CoverageFingerprint.of`. `doSend`/Kafka/Ws 러너의 `coverage.dump(true)` 경로를 backend 호출로 치환. `CoverageFingerprint`/`BranchCoverageAnalyzer`는 `ExecutionDataStore` 입력이라 재사용.
- **.exec await 정책(리뷰 I4)**: backend는 요청별 `.exec`를 polling await(설정 `--exec-await-ms`, 기본 30s). 타임아웃 시 **경고 로그 + 빈 store 반환(=신규 커버리지 0)** 으로 워커 크래시 대신 탐색 계속. flush 풀 포화로 dispatch가 큐잉되면 await가 그만큼 대기하므로 풀 사이징(§4.5) 선행.
- **`coverage` CLI 서브커맨드(리뷰 I11)**: `BuilderCli`의 `coverage` 서브커맨드가 `org.jacoco.core.tools.ExecFileLoader`로 `.exec` 로드(L83·L817~). pjacoco `.exec`는 바닐라 JaCoCo 바이트 호환이라 **로더 유지 가능** — 단 jacoco-core gradle 의존성은 유지(에이전트만 pjacoco). 확인 후 명시.
- **빌드 의존성(리뷰 I5)**: pjacoco는 Maven Central 미배포(로컬 소스 빌드). CI 재현성 위해 **pjacoco agent jar 획득 방식 확정**: (선택) ①mavenLocal publish + `libs.versions.toml` 등록, ②repo에 vendored fat-jar + 해시락, ③CI pre-build 스텝. 본 PoC가 쓰던 `-Dpjacoco.agent.jar` 주입은 테스트용이므로 프로덕션은 ①/②중 택1(구현 전 결정 — 인박스).

### 4.2 엔드포인트 워커 풀 (병렬화) — HTTP 루프만
- **HTTP 엔드포인트 루프만** `ExecutorService`(크기 `--parallelism N`, 기본 `min(코어-2, 엔드포인트수)`)로 fan-out. `--parallelism 1` = 순차(코드 동일, P만 1).
- **Kafka/Ws 러너는 순차 유지(리뷰 I2/I4)**: KafkaCaptureRunner는 HTTP 루프 前, WsCaptureRunner는 後에 **순차 단일 스레드** 실행(워커 풀 밖). 각자 자기 Connection을 별도 open. HTTP 병렬 구간과 시간적으로 분리돼 pjacoco store·Connection race 없음. (이들도 pjacoco backend 사용하되 직렬이므로 안전.)

### 4.3 per-worker Connection (리뷰 I7/I12)
- 공유 단일 Connection 제거. 각 HTTP 워커가 task 시작 시 `env.openConnection()`로 **자기 Connection** 획득, 엔드포인트 탐색 전체(seed INSERT/SELECT/DELETE 포함)를 try-with-resources로 감싸 종료 시 close. 동시 연결 수 = `min(parallelism, 엔드포인트수)`로 bounded → 풀 불요(DriverManager-per-call 유지). Kafka/Ws도 각자 open.

### 4.4 per-worker 누적기 + post-loop merge (리뷰 I1/I6)
- 공유 누적기 제거. 각 워커는 `EndpointResult`(로컬)에 paths·sql·httpCalls·wsExchanges·kafkaExchanges·seeds·reportEntries·capturedEventEmits·**unsupportedShapes**·**coveredBranches(Set)**·**cumulativeExec(ExecutionDataStore)**를 담는다.
- 모든 워커 Future 완료 후 **단일 스레드 merge**(루프 종료 후): 리스트는 set-union(+기존 dedupe), `coveredAppBranches`는 union, `runWideExec`는 각 `cumulativeExec`를 **순차 OR-merge**(`ExecutionDataStore.accept` — thread-unsafe라 병렬 중 호출 금지, merge 단계에서만). graph.json은 기존대로 끝에 1회 write(단일 writer). 결정적 출력이 필요하면 merge 후 안정정렬(엔드포인트 id·key) 정규화(옵션).

### 4.5 coverage-guided ↔ 비동기 flush (리뷰 I9 명확화)
- 워커 *내부*는 순차(요청 N 커버리지로 N+1 선택). 병렬은 워커 *간*.
- 워커 inner loop 타임라인: (1) HTTP 요청 송수신 → (2) `flushPool.submit(flush(traceId))` **non-blocking** → (3) `backend.awaitExec(traceId, timeout)` **블록**(다음 입력 선택에 필요) → (4) coverageKey 계산. flush *HTTP 응답*은 풀에서, 워커는 `.exec` 가용성만 await. 워커 *간*엔 서로 flush 안 기다림 → 병렬성.
- flush 풀(워커 공유) 크기 `--flush-threads M`(기본 `parallelism×2`, 하한 `parallelism`). R×C 규칙 + 큐 대기 고려(리뷰 I8): `pool ≥ ceil(R×(C+queue-wait))`. 무거운 SUT는 확대.

### 4.6 seed 키 충돌 해결 (리뷰 I3 — neww)
병렬 워커가 같은 probe row를 INSERT/DELETE하지 않도록 **둘 중 하나**:
- **(권장) 엔드포인트 스코프 probe 키**: `SampleInputSynthesizer`/`ReadInputSynthesizer`의 probe 값을 `"probe-" + endpointId + "-" + field.name()`로 변경 → 워커별 키스페이스 비중첩. (단, 기존 시드 결정성·검증 영향 회귀 확인.)
- **(대안) seed DELETE를 post-loop로 연기**: 워커는 INSERT만, cleanup은 전 워커 완료 후 일괄. 멱등 INSERT는 충돌 무해.
구현 시 택1(인박스 결정 가능). DoD §6의 병렬 무사고 게이트가 이를 검증(거짓 404/실패 0).

## 5. 통합 지점 (수정 파일 — 모듈 경로)

| 파일 | 변경 |
|---|---|
| `coverage/JacocoAgent.java` | 제거(또는 pjacoco 대체) |
| `coverage/CoverageClient.java` | 제거 |
| `coverage/PjacocoAgent.java`(신설) | agent JVM 옵션 (PoC 이식) |
| `coverage/PjacocoCoverageBackend.java`(신설) | per-request flush+load+fingerprint + 비동기 풀 + await 정책 |
| `coverage/PjacocoSleuthCoverageBackend.java`(조건부 신설) | sleuth/B3 어댑터 (eventuate) |
| `env/AnalysisEnvironment.java` | agent attach(trace-mode별), coverageEndpoint 제거 |
| `run/EndpointExplorationRunner.java` | `coverage.dump`→backend, 워커별 Connection, 로컬 누적기 |
| `run/KafkaCaptureRunner.java`, `run/WsCaptureRunner.java` | `coverage.dump`→backend(직렬 유지), 자기 Connection |
| `explore/SampleInputSynthesizer.java`, `explore/ReadInputSynthesizer.java` | probe 키 엔드포인트 스코프(§4.6 권장안 시) |
| `cli/BuilderCli.java` (explore + runAttached) | 워커 풀, per-worker 누적기+merge, `--parallelism`/`--flush-threads`/`--exec-await-ms`, JaCoCo 배선 제거(attach 포함) |
| `env/OverrideComposeGenerator.java`, `AttachConfig` | jacoco 포트 매핑/필드 제거 |
| `build.gradle.kts`/`libs.versions.toml` | pjacoco agent 획득(§4.1) |

## 6. 단계 / E2E·수용 기준 (DoD) — 단계적 게이트 (리뷰 I1)

**Phase 0 — 실제 빌더 speedup 실측 (ROI 게이트, 리뷰 I2)** ✅ **완료 2026-06-23**: 최소 병렬화(HTTP 루프만, dump 직렬락)로 order-service(28 endpoints) 실제 빌더 순차 vs 병렬 벽시계 실측.

| P | 중앙값(s) | speedup |
|---|-----------|---------|
| 1 | 237.5 | 1.00x (baseline) |
| 2 | 190.5 | 1.25x |
| 4 | 138.5 | 1.72x |
| 8 | 124 | **1.91x** |

**Phase 0.5 — 락 제거 unlocked 측정 (REQ-P001 후속)** ✅ **완료 2026-06-23**: `COVERAGE_DUMP_LOCK` 제거 후 재측정. `dump()` IOException은 catch-and-return-empty로 처리.

| P | 중앙값(s) | speedup |
|---|-----------|---------|
| 1 | 258.5 | 1.00x (re-baseline) |
| 4 | 151 | 1.71x |
| 8 | 128.5 | **2.01x** |

**gate 판정(Phase 0.5)**: 중앙값 2.01x — **2.0x 게이트 통과**. 단, 락 제거 효과 델타 = +0.10x(노이즈 수준) → **락이 주 병목이 아니었음 확인**. 진짜 병목은 단일 SUT HTTP 포화. Phase 1(pjacoco) 예상 speedup을 **2.0-2.5x로 하향 조정**(기존 2.5-3.5x 예측 수정). 상세: `.superpowers/sdd-parallel/phase0-report.md`.

**Phase 1 — pjacoco 교체(순차, `--parallelism 1`) 별도 게이트(리뷰 I1)**: JaCoCo→pjacoco 교체만(병렬 코드 없음). **하드 게이트**: 전 SUT에서 `--parallelism 1`(pjacoco) 산출물이 **이전 main(JaCoCo)과 set-동등**(아래 1·3) + 전체 회귀 green. 별도 commit/머지 포인트. 가능하면 `--coverage-backend jacoco|pjacoco` 플래그로 전환기 롤백 보장. **이 게이트 통과 후에만 Phase 2.**
- 선행: **순차 oracle 결정성(리뷰 I7)** — 이전 main을 2회 실행해 path-set 안정성 확인(±변동 임계 문서화). 비결정적이면 "각 path가 M회 중 ≥N회 출현" 기준 정의.

**Phase 2 — 병렬화(`--parallelism N`)**: 워커 풀 + per-worker Connection/누적기 + seed 키 해결.

**DoD (전 SUT: petclinic·tainted-spring·eventuate-tram\*)**:
1. **출력 set-동등** — 병렬 graph.json이 순차 대비 엔드포인트별 전 GraphAsset 리스트 필드 집합 동일(순서무관). set-diff 도구.
2. **pass-rate 동등** — 병렬 산출물의 생성 테스트 pass-rate가 순차와 동일.
3. **coverage 동등(리뷰 I10)** — `coveredAppBranches` 집합 동일(또는 branch delta ≤ 명시 임계 N), run-wide 기준.
4. **speedup(리뷰 I10·I2)** — petclinic 실제 빌더 실측 **≥ 2.0x**(미만이면 ROI 재평가).
5. **병렬 무사고** — 누적기 race·Connection race·커버리지 오염·**seed 키 충돌(거짓 404)** 0. SUT 내부 경합 실패는 식별·구분 보고.
6. **JaCoCo 제거 완결** — tcpserver/dump·attach jacoco 배선 잔존 0, 전 SUT 회귀 green.

\* **eventuate-tram 조건부(리뷰 I8)**: sleuth/B3 pjacoco 경로가 동작하면 DoD 포함, pjacoco Brave 모드가 testId 상관을 지원 안 하면 **eventuate-tram은 deferred**(2 SUT로 DoD 축소)로 명시 — 구현 전 pjacoco sleuth 인터페이스 확인으로 확정.

## 7. 리스크

- **실제 speedup < PoC 3.72x(CPU 경합)** — Phase 0가 선검증. 2x 미만이면 ROI 재논의.
- **전면 교체 회귀** — Phase 1 하드 게이트가 isolation. 플래그 롤백.
- **seed 키 충돌** — §4.6로 해결, DoD 5가 검증.
- **eventuate-tram sleuth** — §4.1/§6 조건부, 구현 전 pjacoco B3 확인.
- **flush 큐 병목(무거운 SUT)** — 풀 사이징 + 모니터링.
- **순차 oracle 비결정성** — Phase 1 선행 확인.
- **pjacoco CI 의존성** — §4.1 획득 방식 확정.

## 8. 완료 정의

Phase 0(speedup ≥2x) → Phase 1(pjacoco `--parallelism 1` set-동등 + 회귀 green, 별도 머지) → Phase 2(병렬 N, DoD 1~6 전 SUT) → JaCoCo 제거 완결. 각 Phase는 회귀 + 코드리뷰(spec-compliance + quality) 후 진행. eventuate-tram은 §6 각주대로 조건부.

## 9. 알려진 한계 (Documented Limitations)

### 9-A. `httpCalls` 병렬 귀속 — baggage 전파 전제 (order-service는 충족 ✅)

`--parallelism > 1` 모드에서 외부 HTTP 캡처(`httpCalls`)의 per-traceId 귀속은
`HttpCaptureServer.drainByTraceId(traceId)`가 WireMock 이벤트의 `baggage: test.id=<traceId>`를
읽는 방식이다. 따라서 SUT가 inbound baggage를 아웃바운드 HTTP 호출로 전파하는 것이 전제다.

**검증 결과(2026-06-24, 정정)**: order-service는 이 전제를 **충족한다**. OTel javaagent
(`OTEL_PROPAGATORS=tracecontext,baggage`)가 부착된 상태에서 inbound `baggage: test.id=<id>`가
`InventoryClient`의 `RestTemplate` 아웃바운드 호출로 정상 전파되어 WireMock ServeEvent에 기록됨을
통합 테스트 `OtelHttpCaptureIntegrationTest.inboundBaggage_propagatesToOutboundInventoryCall`로
직접 입증했다(`exchanges=1 paths=[/inventory/stock] baggagePresent=true`, `drainByTraceId` 귀속 성공).

**이전 "SUT-dependent documented limitation" 판정은 철회한다.** 그 근거였던 sequential 그래프의
`baggagePropagated:false`는 `HttpCaptureServer`의 플래그가 잘못된 토큰(`test-id=`, 하이픈)을
검사하던 버그로 인한 **false-negative**였다(실제 주입/전파 토큰은 `test.id=`, 점). 플래그는
`test.id=`로 수정했다.

**남은 실제 이슈(빌더 측 규명 대상)**: 그럼에도 P2-5 병렬 게이트에서 `httpCalls`가 0으로 관측된 것은
**baggage 미전파가 아닌 다른 원인**이다(부하 하 EXPRESS-주문 요청 처리 또는 통합 경로). baggage 한계로
호도해 게이트 FAIL을 면제하지 않는다 — 재게이트로 원인을 규명한다. 일반적으로 baggage를 전파하지 않는
*다른* 가설적 SUT라면 귀속이 제한될 수 있으나, 이는 order-service에는 해당하지 않는다.

### 9-B. 병렬 SQL/httpCalls 누락의 진짜 근본원인 — pjacoco includes 과대범위 (해결됨)

**증상**: `--parallelism 4` 게이트에서 `sql`이 ~96% timeout(par4_regate 3/68)이고 `httpCalls`가 0이었다.
순차(P1-5)는 정상(72 sql)이었다.

**근본원인(2026-06-24 규명)**: pjacoco `includes`가 SUT 앱이 아니라 `io.*`로 과대 설정돼 있었다.
`PjacocoAgent.detectRootPackage`가 sutSrc의 **첫 디렉터리 세그먼트만** 취해(`io/graphrag/sample/orders`
→ `io`) `io.*`를 만들었기 때문이다. `io.*`는 OTel javaagent의 span-export 클래스(`io.opentelemetry.*`)와
서드파티(`io.netty.*` 등)까지 계측 대상에 넣는다. 병렬 실행에서 4 워커가 동시에 SUT를 두드리면,
계측된 OTel export hot-path가 pjacoco 프로브 오버헤드로 starve돼 ① OTLP span이 시간 내 export되지
않아 SQL entry-span timeout, ② OTel **baggage propagator**도 계측·간섭돼 `test.id` baggage가 outbound로
전파되지 않아 httpCalls 귀속 실패가 동시에 발생했다. 순차에서는 오버헤드를 감내해 드러나지 않았다.

**검증(반증 소진)**: otel-only 4-way 동시 즉시-drain 통합 테스트는 12/12 통과(동시성 자체는 무해).
includes를 `io.graphrag.sample.orders.*`로 좁힌 병렬 빌드는 **timeout 0, sql 59, httpCalls 2**(이전 0)로
회복. 단일 근본원인이 sql·httpCalls 두 실패를 모두 설명한다.

**수정**: `detectRootPackage`가 단일 자식 디렉터리 체인을 하강해 앱 코드의 최장 공통 패키지 prefix를
탐지하도록 변경(`io.graphrag.sample.orders.*`). OTel/서드파티 `io.*`는 계측되지 않는다.
단위 테스트 `PjacocoAgentTest`로 가드.

**F1b(SQL await timeout)의 위치와 되돌림**: F1b는 병렬 await 기본값을 8s→30s로 늘려 증상을 완화하려던
1차 시도였으나 근본원인이 아니었다. 근본수정 후 span은 ~100ms 내 도착하므로 **병렬 기본값을 다시 8s로
되돌렸다**(순차·병렬 공통 `AWAIT_TIMEOUT_MILLIS=8_000`, `PARALLEL_AWAIT_TIMEOUT_MILLIS` 제거). 진짜 부하
spike가 있는 SUT를 위한 재정의 수단으로 `--sql-await-ms`(`BuildConfig.sqlAwaitMs`) 플래그는 유지한다.

### 9-C. capturedEventEmits 병렬 drain race (해결됨)

`KafkaCaptureReceiver.drainAllByTraceId`는 정착 후 큐 **전체를 clear**한다. 병렬 워커가 각자
엔드포인트 `buildPaths`에서 동시 호출하면 먼저 lock을 잡은 워커가 다른 워커의 Kafka emit 레코드까지
drain·clear해 소실시킨다 → `capturedEventEmits` par4=0(seq=2). httpCalls의 공유-drain race와 동종.

**수정**: `drainByTraceIds(Set<traceId>, settle)` 추가 — 지정 traceId 레코드만 제거하고 나머지는 큐에
남긴다. `buildPaths`는 그 엔드포인트 candidate의 traceId만, kafka-diff 2차 invoke는 secondTraceId만
수거한다. 단위 테스트 `KafkaCaptureReceiverParallelDrainTest`(격리+동시성). 재게이트에서 capturedEventEmits 2=2 회복.

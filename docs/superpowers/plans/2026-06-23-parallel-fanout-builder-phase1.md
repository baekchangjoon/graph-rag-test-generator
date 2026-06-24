# 병렬 fan-out 빌더 — Phase 1 구현 계획 (pjacoco 교체 + 순차 set-동등 게이트)

> REQUIRED SUB-SKILL: superpowers:subagent-driven-development. 체크박스 추적.
> 출처: design rev.2 §4.1/§6 Phase 1, requirements REQ-P002·P003·P010·P012.
> **Phase 1 목표**: JaCoCo dump → pjacoco 백엔드 전면 교체. `--parallelism 1`(순차) 산출물이 이전 main(JaCoCo)과 **set-동등** + 회귀 green. **이 하드게이트 통과 후에만 Phase 2(병렬).** 병렬 코드(spike의 ExecutorService)는 존재하나 Phase 1 게이트는 순차만 본다.

**전제(인박스 결정)**: pjacoco CI 의존성(REQ-P012) = mavenLocal publish + `libs.versions.toml` 등록(택1 기본). seed 키(REQ-P007)·eventuate(P011)는 Phase 2.

## Global Constraints
- pjacoco 좌표 `io.pjacoco:pjacoco-agent:1.3.0`, agent 빌드 `:agent:shadowJar`→`pjacoco-agent.jar`. PoC 자산(`PjacocoAgent`/`PjacocoCoverageBackend`(=PjacocoOtelScopeClient 프로덕션화)) 이식.
- agent 순서 OTel→pjacoco, `traceKeyAutoCreate=true`, `includes=<sut pkg>`.
- coverageKey 의미 = PoC §5.1 partition-등가(절대 키는 다름). `CoverageFingerprint`/`BranchCoverageAnalyzer` 재사용.
- 전환기 `--coverage-backend jacoco|pjacoco`(기본 pjacoco) — 롤백·비교용.
- 커밋 author `baekchangjoon <changjoon.baek@icloud.com>`.

---

### Task P1-1: PjacocoCoverageBackend + PjacocoAgent를 builder main으로 이식
**REQ-IDs:** REQ-P002, REQ-P012
- Create: `coverage/PjacocoAgent.java`, `coverage/PjacocoCoverageBackend.java`(PoC `PjacocoOtelScopeClient`+async flush+await 정책 이식), 단위 테스트.
- pjacoco를 mavenLocal publish + `gradle/libs.versions.toml`·`graph-rag-builder/build.gradle.kts`에 `io.pjacoco:pjacoco-agent` 등록(에이전트 jar 추출은 OtelAgent.prepare 패턴 — 리소스 번들 또는 의존성 추출).
- 단위: fixture `.exec` round-trip(PoC `PjacocoCoverageClientTest` 이식) + `CoverageFingerprint.of`로 partition 산출.
- 게이트: 단위 green. Commit.

### Task P1-2: agent attach 교체 (AnalysisEnvironment + runAttached)
**REQ-IDs:** REQ-P002, REQ-P010
- `env/AnalysisEnvironment.start(...)`: JaCoCo agent JVM 옵션 → pjacoco(OTel→pjacoco 순서, traceKeyAutoCreate). `coverageEndpoint()` 제거.
- `cli/BuilderCli.runAttached`: `JacocoAgent.containerJavaToolOptions`·jar copy·`jacocoContainerPort` → pjacoco. `AttachConfig.jacocoHostPort`·`OverrideComposeGenerator` jacoco 포트 제거/대체.
- 게이트: 컴파일 + 기동 smoke(SUT 부팅, pjacoco `[pjacoco] agent installed` 로그). Commit.

### Task P1-3: 커버리지 수집 경로 교체 (doSend + Kafka/Ws 러너)
**REQ-IDs:** REQ-P002
- `run/EndpointExplorationRunner.doSend`: `coverage.dump(true)` → `PjacocoCoverageBackend`(요청 traceId flush→`.exec`→`CoverageFingerprint`). per-request 고유 traceId 생성(doSend L1342 상수 `test-id=explore` → 동적). await 정책(타임아웃 시 빈 store).
- `run/KafkaCaptureRunner`·`run/WsCaptureRunner`: 동일 교체(직렬 유지).
- `--coverage-backend` 플래그로 jacoco/pjacoco 선택(기본 pjacoco; jacoco는 비교/롤백용 일시 유지).
- 게이트: 단일 엔드포인트 빌드가 pjacoco로 coverageKey 산출. Commit.

### Task P1-4: 순차 oracle 결정성 + set-동등 비교 도구
**REQ-IDs:** REQ-P003
- 도구: `graph.json` set-diff(엔드포인트별 paths·sql·httpCalls·ws·kafka·seeds·capturedEventEmits 집합 비교, 순서무관) + `coveredAppBranches` 집합 비교. 셸/JUnit.
- 이전 main(JaCoCo) 2회 실행 → path-set 안정성(결정성) 확인, 변동 임계 문서화.
- 게이트: 도구 동작 + oracle 안정성 기록. Commit.

### Task P1-5: Phase 1 하드게이트 — `--parallelism 1` pjacoco == 이전 main(JaCoCo) set-동등 (전 SUT)
**REQ-IDs:** REQ-P003, REQ-P010
- 각 SUT(order-service 우선, 그다음 petclinic/tainted-spring): `--parallelism 1 --coverage-backend pjacoco` graph.json vs 이전 main(JaCoCo) graph.json을 P1-4 도구로 set-비교 + coveredAppBranches 비교.
- 전 모듈 회귀(`./gradlew test`) green.
- **불일치 시**: partition 차이 원인 규명(pjacoco 넓은 귀속 등). 허용 가능(각 항목 존재)하면 통과, 누락이면 fail→수정.
- 게이트: 전 SUT set-동등 + 회귀 green. **통과해야 Phase 2.** Commit + §11 결과 기록.

### Task P1-6: JaCoCo 잔존 제거 (Phase 1 범위)
**REQ-IDs:** REQ-P010
- set-동등 확정 후 `--coverage-backend` jacoco 분기·`JacocoAgent`·`CoverageClient`·tcpserver/dump 배선 제거(`coverage` CLI 서브커맨드의 jacoco-core ExecFileLoader는 pjacoco `.exec` 호환이라 유지 확인).
- 게이트: grep으로 tcpserver/dump 잔존 0 + 회귀 green. Commit.

## Self-Review / DoD (Phase 1)
- REQ-P002 pjacoco 백엔드 동작, REQ-P003 순차 set-동등(전 SUT) + 회귀 green, REQ-P010 JaCoCo 제거, REQ-P012 CI 의존성. 각 Task spec+quality 리뷰. Phase 1 게이트 통과 후 Phase 2 plan 상세화.

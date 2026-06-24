# Task P1-1 완료 보고서 — PjacocoCoverageBackend + PjacocoAgent + 빌드 의존성

## 빌드 상태
- `compileJava`: GREEN
- `compileTestJava`: GREEN
- `PjacocoCoverageBackendTest` (5 tests): GREEN (failures=0, errors=0)

## 생성/변경 파일

### 신규 생성 (MAIN source set)
- `graph-rag-builder/src/main/java/io/graphrag/builder/coverage/PjacocoAgent.java`
  - OtelAgent.prepare 패턴 그대로 — `/agents/pjacoco-agent.jar` 리소스 추출 → workDir에 copy
  - `javaToolOptions(destfileDir, controlPort, includes, traceKeyAutoCreate)` 및 `containerJavaToolOptions(...)` 제공
- `graph-rag-builder/src/main/java/io/graphrag/builder/coverage/PjacocoCoverageBackend.java`
  - PoC `PjacocoOtelScopeClient` + `PjacocoCoverageClient` 통합 프로덕션화
  - `generateTraceId(int requestIndex)` — 결정적 32-hex traceId (high 64=index, low 64=discriminator)
  - `traceparentFor(String traceId)` — W3C traceparent 생성
  - `flushAsync(String traceId)` — 비동기 stop POST (ExecutorService 기반)
  - `flush(String traceId)` — 동기 stop POST
  - `awaitExec(String traceId, long timeoutMs)` — .exec 폴링 (타임아웃 시 경고 로그 + 빈 store 반환, throw 없음)
  - `loadExec(String traceId)` — 동기 로드 (파일 존재 전제)
  - `shutdown()` — flushExecutor 드레인 (최대 5s)

### 신규 생성 (TEST source set)
- `graph-rag-builder/src/test/java/io/graphrag/builder/coverage/PjacocoCoverageBackendTest.java`
  - 5개 테스트: fixture .exec round-trip, awaitExec 파일 존재 케이스, 타임아웃 빈 store, traceId 결정성, traceparent 포맷

### 변경 (빌드 파일)
- `settings.gradle.kts` — `mavenCentral()` 뒤에 `mavenLocal()` 추가 (순서 중요: 앞에 두면 `junit-platform-launcher` BOM 버전 해석 실패)
- `gradle/libs.versions.toml` — `pjacoco = "1.3.0"` + `pjacoco-agent` 라이브러리 항목 추가
- `graph-rag-builder/build.gradle.kts` — `pjacocoAgent` Configuration + `pjacocoAgent(libs.pjacoco.agent)` + `processResources`에 `pjacoco-agent.jar` 번들 추가

## pjacoco 의존성 해결 방법: mavenLocal publish (번들 리소스 방식)

- `publishToMavenLocal` 실행: `cd ~/github_parallel-per-test-coverage/parallel-per-test-coverage && JAVA_HOME=.../corretto-17... ./gradlew :agent:publishToMavenLocal`
- 결과: `~/.m2/repository/io/pjacoco/pjacoco-agent/1.3.0/pjacoco-agent-1.3.0.jar` (4.8MB shadow jar)
- 빌드 시 `pjacocoAgent` Configuration으로 의존성 다운로드 → `processResources`에서 `src/main/resources/agents/pjacoco-agent.jar`로 번들
- `PjacocoAgent.prepare(workDir)` 호출 시 `getResourceAsStream("/agents/pjacoco-agent.jar")`로 추출 (OtelAgent 패턴과 동일)
- CI 재현성: `pjacoco` 프로젝트가 로컬 빌드 전용(mavenLocal)이므로 **CI에서도 pjacoco 빌드 단계 필요** (README 또는 CI 스크립트에 추가 필요 — P1-6 전 TODO)

## 우려사항 / TODO
1. **CI mavenLocal 제약**: pjacoco가 Maven Central 미출판 상태. CI에서 `./gradlew :graph-rag-builder:build` 전 pjacoco `:agent:publishToMavenLocal` 단계를 CI 스크립트에 추가해야 함 (REQ-P012의 "CI-reproducible" 조건).
2. `mavenLocal()` 위치: `mavenCentral()` 뒤에 두어야 junit-platform-launcher BOM 해석 실패를 피함. 검증 완료.
3. `PjacocoAgent`와 `PjacocoCoverageBackend`는 현재 AnalysisEnvironment/EndpointExplorationRunner에 미연결 — Task P1-2/P1-3에서 연결 예정.

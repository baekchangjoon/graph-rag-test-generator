# 인수인계 — 병렬 fan-out 빌더 P1-6 잔여 작업 (2026-06-24)

> **대상**: 다른 호스트/세션. 이 호스트는 다른 e2e job 대기 중이라 attach e2e를 여기서 못 돌린다.
> **브랜치**: `feat-parallel-fanout-builder` (origin에 push 완료, HEAD=`68bde78`).
> **베이스 정책**: repo는 rebase-only 머지(squash·merge commit 비활성). 머지 시 `gh pr merge --rebase`.
> 이 브랜치는 origin/main보다 26 커밋 뒤 — PR 전 rebase 필요.

---

## 1. 지금까지 완료 (이번 세션, 검증됨)

REQ-P009(병렬==순차 set-동등)을 **11/11 PASS**로 완성하고 P1-6(JaCoCo 제거) 백엔드를 제거했다.

| 커밋 | 내용 |
|---|---|
| `1b6be87` | F1b SQL await + **httpCalls "SUT 한계" 결론 철회**(baggage 전파 통합테스트로 입증) |
| `8b975c8` | gitignore `e2e/gate-*`·`bench-out-*` scratch |
| `e121bd0` | **근본수정①**: pjacoco `includes=io.*` → 앱루트(`io.graphrag.sample.orders.*`). `io.opentelemetry.*`(OTel export/baggage) 계측 중단 → 병렬 sql/httpCalls/seeds 회복 + 빌드 27배 단축 |
| `e7d91a1` | **근본수정②**: `KafkaCaptureReceiver.drainByTraceIds`(per-traceId 안전) → capturedEventEmits 회복. **P2-5 풀 게이트 11/11 PASS** |
| `e5dddce` | F1b 병렬 await 30s→8s 되돌림(근본수정 후 불필요), `--sql-await-ms` 유지 |
| `d625586` | **P1-6 일부**: JaCoCo 백엔드 제거(`JacocoAgent`/`JacocoCoverageProbe`/`CoverageClient`+테스트2 삭제, always-pjacoco rewire, `--coverage-backend` 폐기) |
| `68bde78` | 선존 통합테스트 2건 수정(WS probe-key 스코프화, Kafka emit noopProbe traceparent) |

**검증 상태**:
- `:graph-rag-builder:test` 전체 **493 tests, 0 failures** (unit+integration, Docker). ✅
- P2-5 풀 게이트(`e2e/parallel/gate-p2-5.sh --par4`): seq vs par4 **SET-EQUIVALENT 11/11**, speedup 2.54x, race=0. ✅
- REQ 매트릭스(`docs/superpowers/requirements/2026-06-23-parallel-fanout-builder-requirements.md`): **9/12 Must green**(P001~P009). P010=🟡(아래), P011=조건부, P012=🔴.

**핵심 교훈(다음 세션 주의)**:
- pjacoco `includes`는 **반드시 SUT 앱 패키지로 좁혀야** 한다. `io.*`는 OTel agent를 계측해 병렬에서 깨진다(`PjacocoAgent.detectRootPackage` 단일자식 하강으로 해결, `PjacocoAgentTest` 가드).
- `org.jacoco.core`(ExecutionDataStore/ExecFileLoader)와 jacoco-core gradle 의존성은 **.exec 포맷 라이브러리**라 유지(제거 대상 아님).
- **동시 gradle 실행 금지**: 같은 daemon에 두 빌드를 띄우면 "daemon stopped"로 실패한다(이 세션에서 probe 중 test 동시 실행으로 한 번 겪음).
- 테스트 자원 정리 규칙(`~/.claude/dev-workflow.md` 신규 섹션): Testcontainers는 Ryuk 자동 reap, 호스트 SUT 프로세스는 PID 캡처 후 그 PID만 종료. 무차별 정리 금지, 타 세션/공유 인프라 불가침.

---

## 2. 남은 P1-6 작업 (REQ-P010 grep-0 완결)

### 2-A. 공유 coverage-port 이름 rename — **결정: 내부만 rename + CLI alias** (사용자 승인)

`--jacoco-port`/`jacocoHostPort`/`jacocoContainerPort`는 jacoco 전용이 아니라 **pjacoco control port로 공유**되는 plumbing이다. 따라서 제거가 아니라 rename. 사용자 결정 = **내부 심볼만 rename하고 CLI는 비파괴**:

1. **내부 심볼 rename** (`jacoco*` → `coverage*`):
   - `env/OverrideComposeGenerator.java`: `jacocoContainerPort`/`jacocoHostPort` 파라미터 + Spec 필드 + 주석(L15,24,25,105 등) → `coverageContainerPort`/`coverageHostPort`.
   - `env/AttachedComposeEnvironment.java`: `coverageHost`/`coveragePort` 주석의 "jacoco" 문구(L19,28,29) → "pjacoco control".
   - `cli/AttachConfig`(record): `jacocoHostPort` 필드 → `coverageHostPort` (호출부 `BuilderCli` L484,494,506, 생성자 L117 갱신).
2. **CLI alias** (비파괴): `cli/BuilderCli.java` L117 attach 파싱에서
   `--coverage-port`를 신설하고, 없으면 `--jacoco-port`를 deprecated alias로 fallback 수락
   (예: `required(options, "--coverage-port", "--jacoco-port")` 헬퍼 또는 `options.getOrDefault`).
   `--jacoco-port` 사용 시 deprecation 경고 로그 1회.
3. **e2e 스크립트는 무중단**: `--jacoco-port`를 계속 써도 동작(alias). 점진적으로 `e2e/run-attach-*.sh`를
   `--coverage-port`로 바꿔도 됨(선택).
4. grep 결과: `--jacoco-port` alias 1곳만 남기고 주석으로 "deprecated alias" 명시 → REQ-P010 실질 충족.

### 2-B. attach 경로 e2e 검증 (**다른 호스트에서 필수**)

`runAttached`를 always-pjacoco로 rewire(`d625586`)했으나 **attach docker-compose e2e는 미검증**(compile+unit만). 다른 호스트에서:
```
e2e/run-attach-otel-e2e.sh        # attach + OTel SQL capture
e2e/run-attach-ext-http-e2e.sh    # attach 외부 HTTP 캡처
```
attach 모드에서 pjacoco 커버리지 + OTel SQL + 외부 HTTP/Kafka 캡처가 정상인지 확인. 실패 시
`BuilderCli.runAttached`(L411~)의 pjacoco JTO/포트/볼륨 배선부터 systematic-debug.

### 2-C. 러너 stale 주석/dead 분기 정리 (저위험)

`run/EndpointExplorationRunner.java`(L268,1464,1470,1502,1559), `run/KafkaCaptureRunner.java`(L141)에
"null = jacoco 백엔드" 류 주석 + `probeTraceparent==null` 방어 분기가 남아 있다. pjacoco는 항상
non-null traceparent를 주므로 dead path. 주석을 "traceparent 미주입(WS 등) 폴백"으로 정정하거나
분기를 정리(단, WS 등 traceparent 없는 경로가 실제로 null을 줄 수 있으니 동작 확인 후).

### 2-D. 완결 확인
- `grep -rn "jacoco\|Jacoco" graph-rag-builder/src/main/java | grep -viE "org\.jacoco\.core|pjacoco|coverage"` → alias 1곳 외 0.
- `:graph-rag-builder:test` 493 green 재확인 + attach e2e green.
- requirements 매트릭스 REQ-P010 → 🟢, `docs/.../design.md` §9-B 갱신.

---

## 3. P1-6 이후 잔여 REQ

- **REQ-P012** (pjacoco CI 의존성, 🔴): pjacoco agent jar를 CI 재현 가능하게 획득. 현재 mavenLocal
  `io.pjacoco:pjacoco-agent:1.3.0` → `build.gradle.kts` processResources가 `/agents/pjacoco-agent.jar`로 번들.
  CI에서 mavenLocal 부재 시 빌드 실패하므로, CI에 pjacoco publishToMavenLocal 스텝 또는 vendored jar+해시락 필요.
- **REQ-P011** (eventuate sleuth 분산, 조건부): pjacoco Brave/B3 testId 상관 지원 확인 후 Must/deferred 확정.
- **전 SUT 확장**: REQ-P009는 order-service만 검증. petclinic/tainted-spring 등 추가 SUT에 동일 게이트 적용 시
  회귀 + 커버리지 보고(사용자 지침: 새 SUT 확장 시 이전 대상 전체 회귀).

---

## 4. PR 게이트 (REQ-P010 완결 후, 머지 전)

CLAUDE.md/dev-workflow 필수 게이트:
1. **코드리뷰**: spec-compliance(먼저) + code-quality(`pr-review-toolkit:code-reviewer`). Layer3 교차벤더는 고위험 시.
2. **회귀 green**: unit+integration(493) + attach e2e + 가능하면 P2-5 게이트 재확인. 누수 검증(컨테이너/PID 0).
3. **문서 동기화**: design/requirements/progress가 코드와 일치(이미 대부분 갱신됨; REQ-P010 status만 남음).
4. **문서 usability 체크**(마무리/릴리스 게이트): README가 pjacoco 단일 백엔드·`--coverage-port`를 반영하는지.

> **⚠️ P1-6 PR/머지 단계는 에스컬레이션 지점**(사용자 결정 Q3): PR 열기/머지 전 사용자 승인 필요.
> 이 세션은 secretary 인박스 중단으로 **직접질문(AskUserQuestion)** 방식으로 전환됐으나, 다른 세션은
> 자기 환경의 위임 규칙을 따른다(인박스 복구 시 인박스, 아니면 직접질문).

---

## 5. 참고 경로
- 요구사항: `docs/superpowers/requirements/2026-06-23-parallel-fanout-builder-requirements.md`
- 설계: `docs/superpowers/specs/2026-06-23-parallel-fanout-builder-design.md` (§9-A/B/C = 근본원인·한계)
- 진행로그: `.superpowers/sdd-parallel/progress.md` (gitignore이지만 로컬 보존), `.remember/remember.md`
- 게이트: `e2e/parallel/gate-p2-5.sh`, `e2e/parallel/graph-diff.sh`
- 근본수정 가드 테스트: `coverage/PjacocoAgentTest`, `run/KafkaCaptureReceiverParallelDrainTest`,
  `capture/OtelHttpCaptureIntegrationTest`(baggage 전파/병렬 즉시-drain)

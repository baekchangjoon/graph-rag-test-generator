# path↔커버리지 trace 매핑 요구사항명세
> 출처(design spec): docs/superpowers/specs/2026-06-25-path-coverage-trace-mapping-design.md
> 완료 정의(DoD): 커버리지 대상 요구사항(Must + 미연기 Should)이 모두 ≥1개의 통과 수용 테스트를 가짐 (대상 매트릭스 전부 🟢)

## 범위 판단 (비례성)

graph.json(외부 관측 가능한 산출물 계약) 스키마에 `coverageTraceIds` 필드를 추가하고, 새 산출물 `coverage-by-path.json`을 생성한다 — 사용자 대면/계약 변경이므로 요구사항명세 대상이다.

## E2E 하네스 (중요)

E2E REQ(001/003/004/006)는 **`BuilderCli.build`를 컨테이너 SUT에 대해 전 구간 실행하고 `<out>/work/pjacoco-exec/*.exec`를 생성하는** full-build 하네스를 필요로 한다. 후보: `OtelKafkaBuildIntegrationTest`, `EgressStubBodyFidelity*E2E` 계열(모두 `BuilderCli.build` 호출). **`SleuthEgressDiscoveryE2E`는 부적합** — traceId를 수동 주입해 egress 발견만 검증하고 `build`를 돌리지 않아 graph.json·pjacoco-exec를 만들지 않는다(설계 §8의 초기 지정 오류, 정정됨). 따라서 본 명세의 E2E는 **신규 클래스 `CoverageTraceMappingE2E`** 로 두되, 기존 full-build E2E의 SUT 기동/티어다운 하네스(compose + pjacoco 에이전트 주입)를 재사용한다. Docker 미가용 환경에서는 skip하고 명시 기록한다.

## kafkaTraceId 와의 관계

`coverageTraceId`는 **신규 별도 필드**다. 현재 `InvocationOutcome.kafkaTraceId`/`PathCandidate.kafkaTraceId`가 같은 effective traceId(`doSendWithScope:2521`)를 담지만 의미(카프카 상관)가 다르므로 재사용·치환하지 않고 별도 필드를 추가한다(설계 §4.1). 값은 같을 수 있다.

## 요구사항 목록

### REQ-001 — graph.json의 각 path가 coverageTraceIds를 보유
- 유형: Functional
- 우선순위: Must
- 설명: `ExploredPath`에 `List<String> coverageTraceIds`를 추가하고 graph.json에 직렬화한다.
- 수용기준:
  - Given traceparent가 주입되는 pjacoco full-build, When 빌드가 graph.json을 저장하면, Then 모든 path 객체가 `coverageTraceIds` 필드(배열)를 가진다.
- 검증 레벨: E2E black-box

### REQ-002 — 대표 traceId = dedup 생존 probe의 traceId
- 유형: Functional
- 우선순위: Must
- 설명: 동일 `(kind:status:coverageKey)` 지문으로 중복된 probe 중 `ExplorationOrchestrator`의 생존자(첫 번째)의 `coverageTraceId`가 그 path의 대표로 기록된다(접근 A — 버려진 중복 probe 미복원). **단 tie-break:** 생존자의 traceId가 null이고 같은 키의 후속 probe가 non-null이면 non-null을 대표로 채택한다(매핑 손실 방지 — 설계 §1.1).
- 수용기준:
  - Given 동일 지문의 invoke 2건(traceId t1 먼저, t2 나중, 둘 다 non-null), When 오케스트레이터가 path를 만들면, Then 결과 `ExploredPath.coverageTraceIds() == ["t1"]`이고 t2는 포함되지 않는다.
  - Given 동일 지문의 invoke 2건(생존자 t=null 먼저, t3 non-null 나중), When path를 만들면, Then `ExploredPath.coverageTraceIds() == ["t3"]`.
- 검증 레벨: integration (orchestrator). 관측 지점은 최종 산출인 `ExploredPath.coverageTraceIds()`.

### REQ-003 — coverageTraceIds가 실재 .exec 파일을 가리킴 (dangling 0건)
- 유형: Functional
- 우선순위: Must
- 설명: graph.json·리포트에 기록된 traceId(effective id, OTel override 반영)는 `<out>/work/pjacoco-exec/<traceId>.exec`로 실재한다.
- 수용기준:
  - Given pjacoco full-build 산출물, When 모든 path의 `coverageTraceIds`를 순회하면, Then 각 traceId에 대응하는 `.exec` 파일이 디스크에 존재한다(dangling 0건).
- 검증 레벨: E2E black-box

### REQ-004 — 빌드시 coverage-by-path.json 생성
- 유형: Functional
- 우선순위: Must
- 설명: `<out>/work/pjacoco-exec`에 `.exec`가 1건 이상이면 graph 저장 직후 `<out>/coverage-by-path.json`을 생성한다.
- 수용기준:
  - Given pjacoco full-build, When 빌드가 끝나면, Then `<out>/coverage-by-path.json`이 존재한다.
  - And 그 파일은 유효 JSON으로 top-level `sutId`, `execDir`, `paths` 배열을 가지며, 각 `paths` 엔트리는 `pathId`, `endpointId`, `coverageTraceIds`, `execFiles`를 가진다.
- 검증 레벨: E2E black-box

### REQ-005 — 리포트가 pathId→traceIds→상대경로를 매핑
- 유형: Functional
- 우선순위: Must
- 설명: 리포트는 각 path에 대해 `pathId`, `endpointId`, `coverageTraceIds`, 그리고 traceId별 `exec`/`sidecar` 상대경로(`<out>` 기준)를 담는다.
- 수용기준:
  - Given 페이크 graph(path A의 coverageTraceIds=[x])와 페이크 exec 디렉터리, When 리포트를 생성하면, Then path A 엔트리의 `execFiles[0].exec == "work/pjacoco-exec/x.exec"`, `execFiles[0].sidecar == "work/pjacoco-exec/x.json"`, `traceId == "x"`이고, 엔트리에 `pathId`/`endpointId`/`coverageTraceIds`가 존재한다.
- 검증 레벨: integration (CoverageByPathReport 단위)

### REQ-006 — 사이드카 존재 시 summary 투영
- 유형: Functional
- 우선순위: Must
- 설명: `<traceId>.json` 사이드카가 있으면 `summary`에 `classCount`, `result`(있을 때), `status`, `durationMs`를 투영한다.
- 수용기준:
  - Given `x.json` 사이드카(classCount=5,status=complete,durationMs=2065,result=passed), When 리포트를 생성하면, Then traceId x의 `summary`가 그 값과 일치한다.
  - Given `result` 필드 없는 사이드카, When 리포트를 생성하면, Then `summary.result`는 null/부재이고 나머지 필드는 정상 채워진다.
- 검증 레벨: E2E black-box + integration

### REQ-007 — 사이드카/exec 누락·손상 시 graceful (빌드 실패 없음)
- 유형: Non-functional (robustness)
- 우선순위: Must
- 설명: 사이드카·exec가 없거나 사이드카가 손상(malformed JSON)이면 `summary: null` + 경고 로그로 처리하고 절대 throw하지 않는다.
- 수용기준:
  - Given coverageTraceIds=[y]이나 `y.json` 사이드카 부재, When 리포트를 생성하면, Then traceId y의 `summary == null`이고 예외가 발생하지 않는다.
  - Given coverageTraceIds=[z]이나 `z.exec` 부재, When 리포트를 생성하면, Then 예외 없이 진행하고 리포트 생성이 계속된다.
  - Given `w.json`이 손상된 JSON, When 리포트를 생성하면, Then traceId w의 `summary == null` + 경고 로그, 예외 없음.
- 검증 레벨: integration (단위)

### REQ-008 — traceparent 미주입 traceId는 빈 리스트
- 유형: Functional
- 우선순위: Must
- 설명: traceparent 미주입(WS·테스트 폴백)으로 effective traceId가 null인 probe로 만들어진 path는 `coverageTraceIds`가 빈 리스트다.
- 수용기준:
  - Given effective traceId가 null인 생존 proto, When path가 생성되면, Then `coverageTraceIds == []`이고 그 path의 `execFiles == []`이다.
- 검증 레벨: integration (orchestrator/단위)

### REQ-009 — 후방호환 역직렬화
- 유형: Non-functional
- 우선순위: Must
- 설명: 구버전 graph.json(필드 없음 또는 `null` 값)을 `ExploredPath`로 역직렬화하면 `coverageTraceIds`가 빈 리스트로 정규화된다.
- 수용기준:
  - Given `coverageTraceIds` 키가 없는 path JSON, When 역직렬화하면, Then `coverageTraceIds()`가 빈 리스트를 반환한다(예외 없음).
  - Given `coverageTraceIds: null`인 path JSON, When 역직렬화하면, Then `coverageTraceIds()`가 빈 리스트를 반환한다.
- 검증 레벨: integration (단위). 기존 `ExploredPathCompatTest`에 **신규 메서드**로 추가한다.

### REQ-010 — exec 부재 시 리포트 미생성
- 유형: Functional
- 우선순위: Must
- 설명: `<out>/work/pjacoco-exec`가 없거나 `.exec`가 0건이면 `coverage-by-path.json`을 생성하지 않는다.
- 수용기준:
  - Given exec 디렉터리 부재, When 빌드가 끝나면, Then `coverage-by-path.json`이 존재하지 않고 빌드는 정상 종료한다.
- 검증 레벨: integration (단위)

### REQ-011 — copy/rewrite 사이트의 필드 보존
- 유형: Non-functional (data integrity / regression)
- 우선순위: Must
- 설명: `withSeedIds`·np 재작성(PK rewrite) 등 `ExploredPath` 재구성 경로에서 `coverageTraceIds`가 보존된다(조용한 손실 금지).
- 수용기준:
  - Given coverageTraceIds=[x]인 path, When `withSeedIds`(또는 np 재작성)를 적용하면, Then 결과 path의 `coverageTraceIds == [x]`이다.
- 검증 레벨: integration (단위)

### REQ-012 — OR-병합 arm path의 다중 traceId
- 유형: Functional
- 우선순위: Should
- 설명: 여러 invoke의 커버리지를 OR-병합해 1 path를 만드는 `-responsevar` arm 사이트는 기여 arm들의 traceId를 모두 담는다(1:N 노출의 실제 케이스).
- 수용기준:
  - Given arm invoke 2건(traceId a1, a2)이 1개 responsevar path로 병합, When path가 생성되면, Then `coverageTraceIds`가 a1·a2를 모두 포함한다.
- 검증 레벨: integration (단위/통합)

### REQ-013 — egress-assertion path는 빈 리스트(의도)
- 유형: Functional
- 우선순위: Should
- 설명: 별도 probe가 아니라 트리거 path 파생 단언인 `egress-assertion` path(2141)는 `coverageTraceIds`가 빈 리스트다(대응 exec 없음 — 의도된 동작).
- 수용기준:
  - Given egress-assertion path 생성, When graph가 저장되면, Then 그 path의 `coverageTraceIds == []`이다.
- 검증 레벨: integration (단위)

### REQ-014 — 비-orchestrator 직접 생성 path가 invoke traceId를 적재
- 유형: Functional
- 우선순위: Must
- 설명: orchestrator·responsevar·egress-assertion 외에 `EndpointExplorationRunner`가 직접 생성하는 path(`-negauth`:572, `-negval-*`:707, `-formref-*`:929, state-guard:1021)는 그 invoke의 effective `coverageTraceId`(non-null이면 `[tid]`, null이면 `[]`)를 적재한다(설계 §4.2).
- 수용기준:
  - Given non-null effective traceId tid로 만들어진 negauth/negval/formref/state-guard path, When path가 생성되면, Then `coverageTraceIds() == [tid]`이다.
- 검증 레벨: integration (생성 사이트별 단위 또는 파라미터화 테스트)

### REQ-015 — 중간 레코드 신규 필드 round-trip 직렬화
- 유형: Non-functional
- 우선순위: Should
- 설명: `InvocationOutcome`·`VariantOutcome`·`PathCandidate`·`ExploredPath`의 신규 `coverageTraceId(s)` 필드가 직렬화/역직렬화 왕복에서 보존된다(plumbing 회귀 가드, 설계 §8).
- 수용기준:
  - Given coverageTraceId(s)가 설정된 각 레코드 인스턴스, When round-trip(직렬화→역직렬화)하면, Then 값이 보존된다.
- 검증 레벨: integration (단위)

## 추적 매트릭스

| REQ-ID | 요구사항 | 수용 테스트 | Level | Status |
|--------|----------|-------------|-------|--------|
| REQ-001 | path가 coverageTraceIds 보유 | `CoverageTraceMappingE2E#graphPathsHaveCoverageTraceIds` | E2E | 🔴 planned |
| REQ-002 | 대표 traceId = 생존 probe | `ExplorationOrchestratorTraceTest#representativeIsSurvivingProbe` | integration | 🔴 planned |
| REQ-003 | dangling 0건 | `CoverageTraceMappingE2E#traceIdsResolveToExecFiles` | E2E | 🔴 planned |
| REQ-004 | coverage-by-path.json 생성 | `CoverageTraceMappingE2E#reportFileExists` | E2E | 🔴 planned |
| REQ-005 | pathId→traceIds→상대경로 매핑 | `CoverageByPathReportTest#mapsPathToExecRelativePaths` | integration | 🔴 planned |
| REQ-006 | 사이드카 summary 투영 | `CoverageByPathReportTest#projectsSidecarSummary` / E2E `#summaryMatchesSidecar` | E2E+integration | 🔴 planned |
| REQ-007 | 누락 graceful | `CoverageByPathReportTest#missingSidecarYieldsNullSummaryNoThrow` | integration | 🔴 planned |
| REQ-008 | 미주입 traceId → 빈 리스트 | `ExplorationOrchestratorTraceTest#nullTraceIdYieldsEmptyList` | integration | 🔴 planned |
| REQ-009 | 후방호환 역직렬화 | `ExploredPathCompatTest#legacyJsonYieldsEmptyCoverageTraceIds` | integration | 🔴 planned |
| REQ-010 | exec 부재 시 미생성 | `CoverageByPathReportTest#noExecDirSkipsReport` | integration | 🔴 planned |
| REQ-011 | copy 사이트 보존 | `ExploredPathCopyPreservationTest#withSeedIdsPreservesTraceIds` | integration | 🔴 planned |
| REQ-012 | arm path 다중 traceId | `ResponseVariantTraceTest#armPathCarriesAllArmTraceIds` | integration | 🔴 planned |
| REQ-013 | egress-assertion 빈 리스트 | `EgressAssertionTraceTest#egressAssertionPathHasEmptyTraceIds` | integration | 🔴 planned |
| REQ-014 | 직접 생성 path가 invoke traceId 적재 | `DirectPathSiteTraceTest#nonOrchestratorPathsCarryInvokeTraceId` | integration | 🔴 planned |
| REQ-015 | 중간 레코드 round-trip | `CoverageTraceIdRoundTripTest#recordsPreserveCoverageTraceId` | integration | 🔴 planned |

> 비고: REQ-012는 설계 §4.4의 `VariantOutcome`/arm 누적 필드가 선행 구현되어야 테스트 가능(현재 `VariantOutcome`은 2-arg). plan에서 모델 변경 task를 REQ-012 테스트 task의 선행으로 둔다.

Coverage: 0/15 green (0%) — target 100% (대상: Must 13 + 미연기 Should 2). 연기/제외 없음.

## 자기검토

1. **고아 행위 없음** — design spec의 §4.2 생성 사이트 6종이 모두 매핑됨(orchestrator→REQ-002, responsevar→REQ-012, egress-assertion→REQ-013, negauth/negval/formref/state-guard→REQ-014). copy 보존→REQ-011, 호환 생성자/round-trip→REQ-015, §6 리포트→REQ-004/005/006, §7 에러·항상-pjacoco→REQ-007/010.
2. **원자성** — 각 REQ는 단일 행위. "매핑+요약"은 REQ-005/006으로 분리, "생성+가드"는 REQ-004/010으로 분리.
3. **수용기준 완비** — 전 REQ Given-When-Then 보유, 측정 가능.
4. **커버리지 규칙 명시** — 분모(Must 11 + Should 2), 제외 없음 명시.

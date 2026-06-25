# path↔커버리지 trace 매핑 요구사항명세
> 출처(design spec): docs/superpowers/specs/2026-06-25-path-coverage-trace-mapping-design.md
> 완료 정의(DoD): 커버리지 대상 요구사항(Must + 미연기 Should)이 모두 ≥1개의 통과 수용 테스트를 가짐 (대상 매트릭스 전부 🟢)

## 범위 판단 (비례성)

graph.json(외부 관측 가능한 산출물 계약) 스키마에 `coverageTraceIds` 필드를 추가하고, 새 산출물 `coverage-by-path.json`을 생성한다 — 사용자 대면/계약 변경이므로 요구사항명세 대상이다.

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
- 설명: 동일 `(kind:status:coverageKey)` 지문으로 중복된 probe 중 `ExplorationOrchestrator`의 `putIfAbsent` 생존자(첫 번째)의 `coverageTraceId`가 그 path의 대표로 기록된다(접근 A — 버려진 중복 probe 미복원).
- 수용기준:
  - Given 동일 지문의 invoke 2건(traceId t1 먼저, t2 나중), When 오케스트레이터가 path를 만들면, Then `PathCandidate.coverageTraceId == t1`이고 t2는 포함되지 않는다.
- 검증 레벨: integration (orchestrator)

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
- 검증 레벨: E2E black-box

### REQ-005 — 리포트가 pathId→traceIds→상대경로를 매핑
- 유형: Functional
- 우선순위: Must
- 설명: 리포트는 각 path에 대해 `pathId`, `endpointId`, `coverageTraceIds`, 그리고 traceId별 `exec`/`sidecar` 상대경로(`<out>` 기준)를 담는다.
- 수용기준:
  - Given 페이크 graph(path A의 coverageTraceIds=[x])와 페이크 exec 디렉터리, When 리포트를 생성하면, Then path A 엔트리의 `execFiles[0].exec == "work/pjacoco-exec/x.exec"`이고 `traceId == "x"`이다.
- 검증 레벨: integration (CoverageByPathReport 단위)

### REQ-006 — 사이드카 존재 시 summary 투영
- 유형: Functional
- 우선순위: Must
- 설명: `<traceId>.json` 사이드카가 있으면 `summary`에 `classCount`, `result`(있을 때), `status`, `durationMs`를 투영한다.
- 수용기준:
  - Given `x.json` 사이드카(classCount=5,status=complete,durationMs=2065), When 리포트를 생성하면, Then traceId x의 `summary`가 그 값과 일치한다.
- 검증 레벨: E2E black-box + integration

### REQ-007 — 사이드카/exec 누락 시 graceful (빌드 실패 없음)
- 유형: Non-functional (robustness)
- 우선순위: Must
- 설명: 사이드카 또는 exec가 없으면 `summary: null` + 경고 로그로 처리하고 절대 throw하지 않는다.
- 수용기준:
  - Given coverageTraceIds=[y]이나 `y.json` 사이드카 부재, When 리포트를 생성하면, Then traceId y의 `summary == null`이고 예외가 발생하지 않는다.
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
- 설명: 구버전 graph.json(필드 없음)을 `ExploredPath`로 역직렬화하면 `coverageTraceIds`가 빈 리스트로 정규화된다.
- 수용기준:
  - Given `coverageTraceIds` 키가 없는 path JSON, When 역직렬화하면, Then `coverageTraceIds()`가 빈 리스트를 반환한다(예외 없음).
- 검증 레벨: integration (단위)

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

Coverage: 0/13 green (0%) — target 100% (대상: Must 11 + 미연기 Should 2). 연기/제외 없음.

## 자기검토

1. **고아 행위 없음** — design spec의 §4(모델·생성사이트·copy·호환생성자), §6(리포트), §7(에러·항상-pjacoco), §8(테스트)이 모두 ≥1 REQ로 매핑됨.
2. **원자성** — 각 REQ는 단일 행위. "매핑+요약"은 REQ-005/006으로 분리, "생성+가드"는 REQ-004/010으로 분리.
3. **수용기준 완비** — 전 REQ Given-When-Then 보유, 측정 가능.
4. **커버리지 규칙 명시** — 분모(Must 11 + Should 2), 제외 없음 명시.

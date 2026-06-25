# path↔커버리지 trace 매핑 설계

- **작성일:** 2026-06-25
- **상태:** 설계(승인 대기)
- **주제:** graph.json의 각 `ExploredPath`에 그 path를 정의한 빌더 탐색 probe의 `coverageTraceId`를 기록해, `pjacoco-exec/<traceId>.exec` 파일과 역참조 가능하게 한다.

## 1. 배경과 문제

graph-rag-builder는 SUT를 탐색하며 엔드포인트별로 여러 probe 요청을 보내고, 그 결과를 `graph.json`의 `ExploredPath`(path)로 증류한다. 각 probe 요청에는 결정적 W3C traceId가 붙고(`TraceParent.next()`), pjacoco가 그 traceId로 per-request 커버리지를 귀속해 `<out>/work/pjacoco-exec/<traceId>.exec`(+ `<traceId>.json` 사이드카)를 쓴다.

문제는 **graph.json의 path와 pjacoco-exec 파일을 연결할 키가 없다**는 것이다. 실측(attach-sleuth-egress 산출물 기준):

- exec 파일 50개 vs `graph.json` path 4개.
- path 객체에 traceId 필드가 전혀 없음(`grep traceId graph.json` = 0건).

즉 "어떤 path가 어떤 커버리지 probe에서 나왔는가"를 역추적할 수 없어, path별 커버리지 시각화·디버깅이 불가능하다.

### 1.1 개수 불일치의 구조적 원인 (버그가 아님)

50 ≠ 4는 정상이다. 두 가지 메커니즘 때문이다.

1. **dedup 드롭.** `ExplorationOrchestrator`(`explore/ExplorationOrchestrator.java:80`)는 `candidates.putIfAbsent(key, ...)`로 같은 `(kind:status:coverageKey)` 지문의 probe 중 **첫 번째 1개만 대표로 남기고 나머지를 버린다.**
2. **비-path probe.** seed/검증/negative 등 다수 probe는 어떤 path로도 retained되지 않는다.

따라서 본 설계는 **대표 traceId(접근 A)** 만 기록한다. 버려진 중복 지문 probe를 되살리지 않는다. 단 응답 변종 arm(`-responsevar`, `EndpointExplorationRunner.java:2077`)처럼 여러 invoke의 커버리지를 OR-병합해 1 path를 만드는 경우는 진짜 N:1이므로, 그 사이트에서는 기여 arm들의 traceId가 자연스럽게 여러 개 담긴다. 그래서 데이터 타입은 단수가 아니라 `List<String>`이다.

## 2. 목표 / 비목표

### 목표
- `graph.json`의 각 path가 자신을 정의한 대표 probe의 `coverageTraceId`(들)를 보유한다(graph.json-native).
- 빌드 종료 시 `<out>/coverage-by-path.json` 리포트를 생성해, `pathId → [traceId…] → exec 파일 상대경로 + 커버리지 요약`을 정적으로 제공한다.
- probe exec 파일은 현행대로 보존한다(정리·삭제하지 않음).

### 비목표
- 버려진 중복 지문 probe(접근 B)의 traceId 누적 — 하지 않는다.
- `test-state-dashboard` 시각화 구현 — 본 작업 범위 밖(후속 spec).
- on-demand CLI 조회 서브커맨드 — 범위 밖(빌드시 정적 리포트로 충분).
- pjacoco 외 백엔드(otel/sleuth 단독)에서의 exec 연계 — exec가 없으므로 리포트를 생성하지 않는다.

## 3. 결정사항 (브레인스토밍 합의)

| 항목 | 결정 |
| --- | --- |
| traceId 집합 의미 | (A) 대표 traceId만. 버려진 중복 probe 미복원 |
| 노출 형태 | `List<String>`(보통 1개, OR-병합 arm path만 N개) |
| 작업 범위 | 데이터 방출 + 얇은 역추적 도구(빌드시 리포트) |
| 도구 형태 | 빌드시 `<out>/coverage-by-path.json` 자동 생성 |
| 리포트 내용 | 매핑 + 요약(사이드카 `<id>.json`에서) |
| 구현 방식 | 접근 1: 모델 관통(graph.json-native) + 리포트는 모델의 파생 |
| probe exec | 일단 보존 |

## 4. 데이터 모델 변경

모두 가산(additive) 변경이며 기존 호환 생성자는 빈 값으로 위임하고, compact-constructor에서 `null`을 정규화한다. 저장소의 기존 패턴(`egressCalls`, `responseHeaders` 추가)과 동일하다.

| 레코드 | 추가 필드 | 채우는 지점 |
| --- | --- | --- |
| `explore/InvocationOutcome` | `String coverageTraceId` | `EndpointExplorationRunner.doSend`(~2521)·`sendVariantAndDumpDelta`(~2261)에 이미 지역 변수로 존재 → 반환 시 적재 |
| `EndpointExplorationRunner.VariantOutcome` | `String coverageTraceId` | `sendVariantAndDumpDelta` 반환(~2292) |
| `explore/PathCandidate` | `String coverageTraceId`(대표 1개) | `ExplorationOrchestrator.toOutcome`에서 생존 `Proto`의 `outcome().coverageTraceId()` |
| `model/ExploredPath` | `List<String> coverageTraceIds` | PathCandidate→ExploredPath 변환 시 `tid != null ? List.of(tid) : List.of()`; OR-병합 arm 사이트는 기여 arm traceId들을 모아 적재 |

- **null 케이스**(WS·테스트 폴백 등 traceparent 미주입): 리스트에서 제외(빈 리스트). `ExploredPath`의 단일 String 값이 null이면 빈 리스트로 정규화.
- 직렬화: `JsonFileGraphStore`·`PartitionedGraphStore` 모두 Jackson 기반이라 신규 필드가 자동 직렬화된다.

## 5. 데이터 흐름

```
doSend / sendVariantAndDumpDelta
  └─ coverageTraceId (지역) ──▶ InvocationOutcome.coverageTraceId / VariantOutcome.coverageTraceId
        └─ ExplorationOrchestrator.toOutcome (putIfAbsent 대표 1개)
              └─ PathCandidate.coverageTraceId
                    └─ ExploredPath.coverageTraceIds = [대표]  (arm-merge면 [arm1, arm2, …])
                          └─ JsonFileGraphStore / PartitionedGraphStore (Jackson 자동 직렬화)
                                └─ graph.json  ✦ 여기서 역참조 가능
```

exec 파일은 이미 `<out>/work/pjacoco-exec/<traceId>.exec`(+ `<traceId>.json`)로 존재 → traceId만 있으면 규약으로 경로 도출.

## 6. 빌드시 리포트: `<out>/coverage-by-path.json`

신규 클래스 `coverage/CoverageByPathReport`가 저장된 graph 모델(`AnalysisAsset`)을 투영해 작성한다. `BuilderCli`의 graph 저장 직후(현 `exploration-report.json` 작성 근처, `BuilderCli.java:~345`)에서, **pjacoco 백엔드가 활성이고 exec 디렉터리가 존재할 때만** 호출한다.

### 스키마

```json
{
  "sutId": "legacy-tram-sleuth-egress",
  "execDir": "work/pjacoco-exec",
  "paths": [
    {
      "pathId": "post-orders-s202-1",
      "endpointId": "post-orders",
      "coverageTraceIds": ["08797591d8a2e60cefda7f9669f8a55e"],
      "execFiles": [
        {
          "traceId": "08797591d8a2e60cefda7f9669f8a55e",
          "exec": "work/pjacoco-exec/08797591d8a2e60cefda7f9669f8a55e.exec",
          "sidecar": "work/pjacoco-exec/08797591d8a2e60cefda7f9669f8a55e.json",
          "summary": { "classCount": 5, "result": "passed", "status": "complete", "durationMs": 2065 }
        }
      ]
    }
  ]
}
```

- 경로는 `<out>` 기준 상대경로로 기록(이식성).
- `summary`는 기존 `<traceId>.json` 사이드카를 읽어 채운다(신규 파싱 의존성 없음). 사이드카가 가진 필드(`classCount`, `result`, `status`, `durationMs`)를 그대로 투영한다.
- path의 `coverageTraceIds`가 빈 리스트면 `execFiles`도 빈 리스트.

## 7. 에러처리 · 후방호환

- **구버전 graph.json**(필드 없음) → `ExploredPath` compact-constructor가 `null → List.of()` 정규화. 역직렬화 안전.
- **사이드카/exec 누락** → 해당 `execFiles` 엔트리의 `summary: null` + 경고 로그. **빌드 실패 없음**(커버리지 미수집이 빌드를 깨선 안 된다는 기존 원칙과 일치).
- **traceId null**(traceparent 미주입) → 리스트에서 제외.
- **비-pjacoco 모드**(otel/sleuth 단독): `coverageTraceIds`는 커버리지 상관 traceId로 그대로 채우되, exec를 참조하는 `coverage-by-path.json`은 **생성하지 않는다**. README/모드 문서에 명시.

## 8. 테스트 (이중 루프)

### E2E / 수용 (바깥 루프 — 최고 가능 수준)

기존 pjacoco/attach E2E(예: `SleuthEgressDiscoveryE2E` 계열 또는 pjacoco fanout PoC E2E)를 확장한다. 빌드 1회 후:

- **REQ-E1:** `<out>/coverage-by-path.json`이 존재한다.
- **REQ-E2:** 모든 path의 `coverageTraceIds`가 디스크에 실재하는 `.exec` 파일을 가리킨다(dangling 0건).
- **REQ-E3:** 각 `execFiles[].summary`가 사이드카 `<id>.json`의 값과 일치한다.
- **REQ-E4:** `graph.json`의 각 path가 `coverageTraceIds` 필드를 보유한다(non-WS·pjacoco 모드).

### 단위 (안쪽 루프 TDD)

- `InvocationOutcome`/`VariantOutcome`/`PathCandidate`/`ExploredPath` 신규 필드 round-trip 직렬화.
- `ExploredPath` 구버전 JSON(필드 누락) 역직렬화 → 빈 리스트.
- `ExplorationOrchestrator`: 동일 coverageKey 중복 시 대표 path의 traceId = 생존 proto의 traceId.
- `CoverageByPathReport`: 페이크 graph + 페이크 exec 디렉터리 → 매핑·요약 정확; 사이드카 누락 → `summary: null`, throw 없음; 빈 `coverageTraceIds` → 빈 `execFiles`.

## 9. 영향 받는 문서

- `README.md`: `<out>` 산출물 목록에 `coverage-by-path.json` 추가.
- 산출물 레이아웃/모드 문서(있으면): 비-pjacoco 모드에서 리포트 미생성 명시.
- `ExploredPath`/graph.json 스키마 설명 문서: `coverageTraceIds` 필드 추가.

## 10. 리스크 / 트레이드오프

- **레코드 생성자 가산 변경의 파급:** `InvocationOutcome`(7개)·`PathCandidate`(5개)·`ExploredPath`(4개) 호환 생성자. 전부 additive·기계적이며 컴파일러가 누락 호출부를 잡아 준다. 위험 낮음.
- **graph.json 비대화:** path당 보통 traceId 1개(32-hex)라 무시 가능. arm-merge path만 소폭 증가.
- **대표 traceId의 정보 손실:** 같은 지문으로 버려진 중복 probe는 역추적 불가(접근 A의 의도된 한계). 디버깅에서 "이 path를 만든 모든 probe"가 필요해지면 접근 B로 후속 확장.

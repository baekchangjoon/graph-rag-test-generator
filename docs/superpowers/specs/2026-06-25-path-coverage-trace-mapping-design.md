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
| `explore/InvocationOutcome` | `String coverageTraceId` | `EndpointExplorationRunner.doSendWithScope`의 effective traceId(아래 4.1) → 반환 시 적재 |
| `EndpointExplorationRunner.VariantOutcome` | `String coverageTraceId` | `sendVariantAndDumpDelta` 반환(~2292)의 `coverageTraceId` 지역 변수 |
| `explore/PathCandidate` | `String coverageTraceId`(대표 1개) | `ExplorationOrchestrator.toOutcome`에서 생존 `Proto`의 `outcome().coverageTraceId()` |
| `model/ExploredPath` | `List<String> coverageTraceIds` | PathCandidate→ExploredPath 변환 시 `tid != null ? List.of(tid) : List.of()`; OR-병합 arm 사이트는 기여 arm traceId들을 모아 적재 |

- 직렬화: `JsonFileGraphStore`·`PartitionedGraphStore` 모두 Jackson 기반이라 신규 필드가 자동 직렬화된다. 영속 모델 루트는 `GraphAsset`(`shared-model/.../GraphAsset.java`)이다.

### 4.1 effective traceId의 정확한 정의 (nullability)

`doSendWithScope`에는 두 지역 변수가 있다(`EndpointExplorationRunner.java`):

- `coverageTraceId`(~2475): 항상 non-null. `probeTraceId`로 초기화되고, OTel scope가 traceparent를 주입하면 **OTel traceId로 덮어쓴다**(~2487). pjacoco flush/await의 키이자 `<traceId>.exec` 파일명.
- `traceId`(~2521): `probeTraceparent != null ? coverageTraceId : null`. traceparent 미주입(WS·테스트 폴백)이면 null.

**`InvocationOutcome.coverageTraceId`에는 `traceId`(~2521)를 적재한다** — 즉 traceparent가 실제 주입된 probe만 non-null. 이유: traceparent 미주입 probe는 `<traceId>.exec`가 생성되지 않으므로, `coverageTraceId`(non-null)를 쓰면 graph.json·리포트에 dangling 참조가 생긴다. effective traceId는 OTel override를 반영하므로(probe id가 아닌 OTel traceId일 수 있음) REQ-E2의 dangling 검증도 이 값 기준이다.

기존 `kafkaTraceId` 슬롯도 같은 `traceId`(~2521) 값을 받지만 의미(카프카 상관)가 다르므로 별도 필드를 둔다. 값이 같을 수 있음을 코드 주석에 명시한다.

### 4.2 ExploredPath 생성 사이트 분류

`ExploredPath`는 orchestrator 경로(`PathCandidate`→변환) 외에도 `EndpointExplorationRunner`에서 직접 다수 생성된다. 각 사이트의 `coverageTraceIds` 처리를 명시한다:

| 라인 | path 종류 | coverageTraceIds 처리 |
| --- | --- | --- |
| (orchestrator) | 대표 path | `PathCandidate.coverageTraceId` → `[tid]` 또는 `[]` |
| 2077 | `-responsevar` OR-병합 arm | 변종 루프에서 `vo.coverageTraceId()`를 `List<String> armTraceIds`에 누적 → `List.copyOf(armTraceIds)` |
| 572 | `-negauth` | 그 invoke의 `InvocationOutcome.coverageTraceId` → `[tid]`/`[]` |
| 707 | `-negval-*` | 동일(그 variant invoke의 traceId) |
| 929 | `-formref-*` | 동일 |
| 1021 | state-guard 변종 | 동일 |
| 2141 | `egress-assertion` | **빈 리스트**(별도 probe가 아니라 트리거 path 파생 단언이므로 exec 없음). 문서·주석에 의도 명시 |

### 4.3 copy/rewrite 사이트의 필드 보존 (데이터 손실 방지)

기존 코드는 `ExploredPath`를 재구성하는 copy 헬퍼가 있다. 신규 필드를 **반드시 보존**해야 한다(호환 생성자로 위임하면 빈 리스트로 덮여 조용히 손실):

- `withSeedIds`(~2677): 원본 `p.coverageTraceIds()`를 그대로 전달.
- np 재작성(~1579, PK rewrite): `np.coverageTraceIds()` 보존.

### 4.4 호환 생성자

신규 필드 추가 시 컴파일 깨짐을 막기 위해, 각 레코드에 **기존 시그니처를 유지하는 delegating 생성자**를 둔다(신규 필드는 null/빈 리스트로 위임):

- `VariantOutcome`: 2-arg `(ExecutionDataStore, int)` 보존(테스트 `new VariantOutcome(coverage, status)` 다수 존재).
- `InvocationOutcome`: 기존 delegating 생성자들이 새 canonical로 위임하도록 갱신.
- `PathCandidate`·`ExploredPath`: 동일. 구버전 graph.json(필드 없음) 역직렬화는 compact-constructor가 `null → List.of()`로 정규화.

## 5. 데이터 흐름

effective traceId(§4.1: traceparent 주입 시 non-null, OTel override 반영)를 관통시킨다.

```
doSendWithScope / sendVariantAndDumpDelta
  └─ effective traceId (지역) ──▶ InvocationOutcome.coverageTraceId / VariantOutcome.coverageTraceId
        └─ ExplorationOrchestrator.toOutcome (putIfAbsent 대표 1개)
              └─ PathCandidate.coverageTraceId
                    └─ ExploredPath.coverageTraceIds = [대표]  (arm-merge면 [arm1, arm2, …])
                          └─ JsonFileGraphStore / PartitionedGraphStore (Jackson 자동 직렬화)
                                └─ graph.json  ✦ 여기서 역참조 가능
```

exec 파일은 이미 `<out>/work/pjacoco-exec/<traceId>.exec`(+ `<traceId>.json`)로 존재 → traceId만 있으면 규약으로 경로 도출.

## 6. 빌드시 리포트: `<out>/coverage-by-path.json`

신규 클래스 `coverage/CoverageByPathReport`가 저장된 graph 모델(`GraphAsset`)을 투영해 작성한다. **호출 순서:** `exploration-report.json` 작성(~345) → `GraphAsset` 생성 + `JsonFileGraphStore.save`/`PartitionedGraphStore.save`(~363–364) → **그 직후** `CoverageByPathReport`. graph가 디스크에 영속된 다음 리포트를 만들어야 하므로 삽입 지점은 `save(asset)` **이후**다(~345 아님).

**생성 가드:** `Files.isDirectory(config.out().resolve("work/pjacoco-exec"))` 가 참이고 그 안에 `.exec`가 1개 이상일 때만. 이 디렉터리는 attach·non-attach 경로 공통으로 `<out>/work/pjacoco-exec`에 쓴다(`BuilderCli.java:286, 482`). 부재(=traceparent 미주입 빌드, 예: WS 전용)면 리포트를 생성하지 않는다.

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
- `summary`는 기존 `<traceId>.json` 사이드카를 읽어 채운다(신규 파싱 의존성 없음). 사이드카는 pjacoco 에이전트가 `<out>/work/pjacoco-exec/`에 `.exec`와 함께 쓰며, attach-sleuth-egress 실제 산출물에서 관측된 스키마는 `{ testId, exec, precision, startedAtMillis, stoppedAtMillis, durationMs, result?, classCount, retryCount, status }`이다. 본 리포트는 그중 `classCount`, `result`(없을 수 있음), `status`, `durationMs`를 투영한다. 사이드카 스키마는 이 저장소 Java가 아니라 pjacoco 에이전트가 정의하므로, 구현 시 실제 산출물 1건으로 필드명을 재확인하고 단위 테스트 fixture로 고정한다.
- path의 `coverageTraceIds`가 빈 리스트면 `execFiles`도 빈 리스트.

## 7. 에러처리 · 후방호환

- **구버전 graph.json**(필드 없음) → `ExploredPath` compact-constructor가 `null → List.of()` 정규화. 역직렬화 안전.
- **사이드카/exec 누락** → 해당 `execFiles` 엔트리의 `summary: null` + 경고 로그. **빌드 실패 없음**(커버리지 미수집이 빌드를 깨선 안 된다는 기존 원칙과 일치).
- **traceId null**(traceparent 미주입) → 리스트에서 제외.
- **커버리지 백엔드는 항상 pjacoco다**(`BuilderCli.java:199` — JaCoCo 백엔드 제거, `--coverage-backend` 폐기). otel/sleuth는 SQL/span **캡처 모드**(`--trace-mode`)일 뿐 커버리지 백엔드 분기가 아니다. 따라서 "비-pjacoco 모드"라는 구분은 없다. 리포트 생성/미생성은 §6의 가드(`work/pjacoco-exec`에 `.exec` 존재 여부)로만 결정한다. OTel scope가 traceparent를 주입하는 빌드에서는 effective traceId가 OTel traceId이고 exec 파일명도 그 값이므로(§4.1) 매핑이 그대로 성립한다.

## 8. 테스트 (이중 루프)

### E2E / 수용 (바깥 루프 — 최고 가능 수준)

실재하는 full-build E2E를 확장한다 — `graph-rag-builder/src/test/java/io/graphrag/builder/capture/SleuthEgressDiscoveryE2E.java`(또는 `OtelKafkaBuildIntegrationTest`). 둘 다 `BuilderCli.build`를 컨테이너 SUT에 대해 전 구간 실행하고 pjacoco exec를 생성한다. (앞 초안이 언급한 fanout PoC 클래스는 이 worktree에 없으므로 대상에서 제외.) 빌드 1회 후:

- **REQ-E1:** `<out>/coverage-by-path.json`이 존재한다.
- **REQ-E2:** 모든 path의 `coverageTraceIds`가 디스크에 실재하는 `.exec` 파일을 가리킨다(dangling 0건). traceId는 effective id(OTel override 반영, §4.1) 기준.
- **REQ-E3:** 사이드카 `<id>.json`이 존재하는 traceId에 한해, `execFiles[].summary`가 그 사이드카 값과 일치한다(사이드카 없는 traceId는 `summary: null` 허용 — pjacoco 에이전트가 모든 stop에 대해 사이드카를 쓰지 않을 수 있으므로 존재하는 것만 대조).
- **REQ-E4:** `graph.json`의 각 path가 `coverageTraceIds` 필드를 보유한다(traceparent 주입 빌드 기준).

### 단위 (안쪽 루프 TDD)

- `InvocationOutcome`/`VariantOutcome`/`PathCandidate`/`ExploredPath` 신규 필드 round-trip 직렬화.
- `ExploredPath` 구버전 JSON(필드 누락) 역직렬화 → 빈 리스트.
- `ExplorationOrchestrator`: 동일 coverageKey 중복 시 대표 path의 traceId = 생존 proto의 traceId.
- `ExplorationOrchestrator`: 생존 proto의 `coverageTraceId`가 null(traceparent 미주입 probe가 putIfAbsent 승자)일 때 → `PathCandidate.coverageTraceId == null` → `ExploredPath.coverageTraceIds == []`.
- copy 보존: `withSeedIds`·np 재작성 후 `coverageTraceIds`가 유지된다(§4.3 회귀 가드).
- `CoverageByPathReport`: 페이크 graph + 페이크 exec 디렉터리 → 매핑·요약 정확; 사이드카 누락 → `summary: null`, throw 없음; 빈 `coverageTraceIds` → 빈 `execFiles`; 사이드카 fixture로 필드명 고정.

## 9. 영향 받는 문서

- `README.md`: `<out>` 산출물 목록에 `coverage-by-path.json` 추가.
- 산출물 레이아웃/모드 문서(있으면): 비-pjacoco 모드에서 리포트 미생성 명시.
- `ExploredPath`/graph.json 스키마 설명 문서: `coverageTraceIds` 필드 추가.

## 10. 리스크 / 트레이드오프

- **레코드 생성자 가산 변경의 파급:** `InvocationOutcome`·`PathCandidate`·`ExploredPath`의 기존 delegating 생성자(현재 각각 다수)를 새 canonical로 위임하도록 갱신하고, `VariantOutcome`엔 2-arg 호환 생성자를 추가한다(§4.4). 전부 additive·기계적이며 컴파일러가 누락 호출부를 잡아 준다. 단 §4.3의 copy 사이트는 컴파일은 통과하되 값을 조용히 잃을 수 있으므로 회귀 단위 테스트로 가드한다. 위험 낮음~중간.
- **graph.json 비대화:** path당 보통 traceId 1개(32-hex)라 무시 가능. arm-merge path만 소폭 증가.
- **대표 traceId의 정보 손실:** 같은 지문으로 버려진 중복 probe는 역추적 불가(접근 A의 의도된 한계). 디버깅에서 "이 path를 만든 모든 probe"가 필요해지면 접근 B로 후속 확장.

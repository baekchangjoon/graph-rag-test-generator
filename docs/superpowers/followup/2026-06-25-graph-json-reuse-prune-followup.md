# graph.json 재활용 — 외부 평가·prune 도구 설계 (후속 작업)

> 상태: **추후 진행용 설계 노트(follow-up)**. 구현 착수 전 단계.
> 이 문서는 "이전 턴에서 유용함이 입증된 테스트 케이스만 graph.json에 남기고, 나머지는 버린 뒤
> 다음 턴에서 이어서/다른 방향으로 탐색한다"는 아이디어를 추후 독립 세션에서 그대로 집어 진행할 수
> 있도록 **현황·gap·설계 방향·리스크·미해결 질문**을 정리한 것이다. 구현 spec/plan은 아니며,
> 착수 시 `brainstorming → requirements-spec → writing-plans` 순서로 별도 작성한다.

작성일: 2026-06-25 · 3-벤더 design-doc 리뷰 1회 반영

---

## 1. 동기

현재 graph-rag 도구의 데이터 흐름은 **단방향**이다.

```
builder(탐색)  →  graph.json
generator      →  graph.json → 테스트
( 테스트 실행·평가 결과  →  graph.json  ← 이 역방향 피드백이 없다 )
```

`graph.json`(= `GraphAsset`)에는 탐색 사실(path/sql/httpCall/seed 등)만 담기고, **"이 path가
테스트로 만들어져 유용했는가(통과·커버리지 기여·회귀 검출력)"** 라는 가치 신호가 없다. 따라서
"유용했던 것만 유지"를 판단할 근거가 그래프 안에 존재하지 않는다.

목표(추후): 생성 테스트의 평가를 **별개 도구**에서 수행하고, 그 도구가 `graph.json`을 입력받아
**유효한 케이스만 추린 graph 디렉터리를 출력**하면, 다음 빌드가 그것을 `--incremental-base`로 받아
유용한 부분은 이어가고 비워진 부분만 다른 방향으로 재탐색하는 **닫힌 루프**를 만든다.

## 2. 현재 이미 되는 것 (재활용 인프라)

추가 구현 없이 이미 동작하는 재활용 메커니즘:

- **run 내 Spoon 1회 공유** — `io.graphrag.builder.index.SharedSpoonModel.build(srcDir)`. 공유
  `CtModel` 위에서 다수의 정적 인덱서/추출기(`EndpointIndexer`, `RouterFunctionIndexer`,
  `GatewayRouteIndexer`, `WsEndpointIndexer`, `KafkaListenerIndexer`, `ResponseDtoIndexer`,
  `EnumConstantExtractor` 등 — `BuilderCli.indexStatically` 참조)가 동작(예전 인덱서별 개별 파싱
  → 1회 파싱).
- **run 간 정적 인덱스 영속 캐시** — `io.graphrag.builder.store.IndexCache` +
  `BuilderCli.staticIndexWithCache(config)`. 신선도 판정(`IndexCache.isFresh`)은 **① 스키마 버전
  ② SUT 소스 파일별 SHA-256 ③ auth 지문**이 모두 동일할 때만 hit. hit면
  `out/index-cache/{manifest.json, static-index.json}`에서 복원하고 **Spoon을 0회**로 건너뛴다
  (로그: `static index: cache hit (no source change) — skipping Spoon parse`). `--no-incremental`
  /`--reindex`로 강제 리빌드. 스키마 포맷 변경 시 `SCHEMA_VERSION`이 올라 자동 무효화. 캐시는
  `--out` 경로에 사니 **out을 고정해야** run 간 살아남는다.
- **graph carry-over (이어서/다른 방향 탐색)** — `BuilderCli.build`가 `--incremental-base
  <prev-graph-dir>`을 받아 처리. **이 인자는 graph.json 파일이 아니라 그 파일을 담은 graph
  디렉터리다**(`JsonFileGraphStore`가 `dir/graph.json`을 로드; 공식 문서 `docs/03-graph-rag-builder.md`
  도 `<prev-graph-dir>`). `IncrementalBuildPlanner`가 두 모드로 carry 한다:
  - **변경 파일 기반**(`--changed-files`, `plan(...)`): 더티 파티션의 endpoint만 재탐색, 클린
    파티션은 이전 graph의 **paths/sql/httpCalls/wsExchanges/capturedEventEmits를 이월**.
    ⚠️ **seeds와 kafkaExchanges는 이 모드에서 이월하지 않는다**(`plan(...)`이 해당 인자를
    `List.of()`로 반환) — 재탐색 시 새로 생성됨.
  - **엔드포인트 선택 기반**(`--endpoint`, `planForEndpoints(...)`): 지정 endpoint만 재탐색,
    나머지는 base에서 **paths/sql/httpCalls/wsExchanges/kafkaExchanges/seeds/eventEmits 전부
    이월**(kafka의 `capturedSqlIds`·path의 `requiredSeedIds` 역참조까지 포함). = "base 유지 +
    특정 지점만 다른 방향으로 탐색".

즉 "기존 graph에서 이어서, 또는 다른 방향으로 탐색"은 **플래그 조합으로 이미 가능**하다. 단, 두
모드의 참조 폐포 완성도가 다르다는 점은 §5에서 prune 설계에 직접 영향을 준다.

## 3. 현재와 목표의 차이 (gap)

| 목표(이 문서) | 현재 동작 | gap |
|---|---|---|
| keep/버림 기준 = **테스트 유용성(가치)** | 기준 = **소스 변경 / endpoint 선택** | 가치 신호가 그래프에 없음 |
| keep/버림 단위 = **개별 path** | 단위 = **endpoint 전체**(재탐색 시 그 endpoint의 path 통째 덮어씀) | path 단위 prune 부재 |
| "나머지는 삭제" | "나머지는 재탐색/이월" | 능동적 prune 개념 부재 |
| 평가가 그래프를 **편집**해 되먹임 | 단방향(builder→graph→generator) | 피드백 채널 부재 |

본질적 gap은 **피드백 채널 + path 단위 가치 prune** 두 가지다.

## 4. graph.json의 참조 구조 (삭제 전 반드시 이해할 것)

`graph.json`은 단순 리스트 묶음이 아니라 **ID로 서로 참조하는 그래프**다
(`shared-model`의 `GraphAsset`, `ExploredPath`, `WsExchange` 등 record).

```
endpoints[id] ◄── paths[].endpointId
paths[id] ──── capturedSqlIds[]        ──► sql[id]
          ├──── capturedHttpCallIds[]  ──► httpCalls[id]
          ├──── requiredSeedIds[]      ──► seeds[id]
          └──── capturedEventEmitIds[] ──► capturedEventEmits[id]

sql[id].pathId                ──► paths[id]   (역참조)
httpCalls[id].pathId          ──► paths[id]
seeds[id].pathId              ──► paths[id]   (※ read-path seed는 pathId가 null일 수 있음 — §8)
capturedEventEmits[id].pathId ──► paths[id]
wsExchanges[id].wsEndpointId  ──► wsEndpoints[id]
wsExchanges[id].capturedSqlIds[]   ──► sql[id]
kafkaExchanges[id].kafkaConsumerId ──► kafkaConsumers[id]
kafkaExchanges[id].capturedSqlIds[]──► sql[id]
```

`tables` / `mappers`는 path 참조가 없어 독립적이다. 평가(§8)에 쓰일 가치 신호 필드도 그래프에
이미 있다: `ExploredPath.outcome`/`semanticStatus`/`branchesTaken`, `GraphAsset`의 에러 계약
디스크립터(`semanticStatusField`/`errorDetailField`/`errorDetailContains`, nullable).

**관대한 점:** `GraphAsset`·`ExploredPath`의 canonical constructor는 **일부 리스트 필드만**
null→빈 값으로 정규화한다(예: `GraphAsset.mappers/httpCalls/seeds/…`, `ExploredPath.
capturedHttpCallIds/requiredSeedIds/…`). 그래서 "그 필드들의 누락"은 후방 호환으로 안전하다.
**관대하지 않은 점:** ① `endpoints`/`paths`/`sql`/`tables`나 `ExploredPath.capturedSqlIds`는
정규화 대상이 아니며, ② 정규화는 **dangling ID 참조**(남긴 path가 가리키는 seed를 지움 / 지운
path를 가리키는 sql을 남김)를 전혀 걸러주지 않는다.

## 5. 삭제 규칙 — "참조 폐포(referential closure)"

"포맷만 맞으면 아무거나 삭제 OK"가 **아니다**. 다음 불변식을 지켜야 한다.

- **path를 지우면** → 그 path를 `pathId`로 가리키는 `sql`/`httpCalls`/`seeds`/
  `capturedEventEmits`를 **cascade 삭제**.
- **path를 남기면** → 그 path가 `requiredSeedIds`/`capturedSqlIds`/`capturedHttpCallIds`/
  `capturedEventEmitIds`로 가리키는 대상을 **모두 보존**, `endpointId`가 가리키는 endpoint도 보존.
- **ws/kafka도 동일** — `wsEndpointId`/`kafkaConsumerId`로 endpoint/consumer를 보존하고,
  **ws의 `capturedSqlIds`와 kafka의 `capturedSqlIds`가 가리키는 sql도 함께 보존**(cascade).

요약: **남긴 path/exchange 집합의 transitive closure만 남기고 나머지는 전부 제거.**

재사용 주의: 이 join 의미의 일부가 `IncrementalBuildPlanner`에 이미 있지만 **두 메서드의 폐포
완성도가 다르다.** `planForEndpoints(...)`만 kafka `capturedSqlIds`·path `requiredSeedIds`
역참조 기반 seed 보존을 수행하고, `plan(...)`은 seeds·kafkaExchanges를 이월하지 않는다(§2).
따라서 prune 엔진은 `plan(...)`을 그대로 미러링하면 **불완전한 pruned graph**가 된다. §5의 전체
불변식을 구현해야 하며, 코드 재사용 시 `planForEndpoints(...)`의 seed/kafka 분기를 포함한 **공용
subsetter(예: `GraphAssetSubsetter`)로 추출**해 builder와 prune 도구가 공유하는 것이 바람직하다.

## 6. 두 소비 모드 (출력 요건이 다름)

1. **pruned graph → generator 직접 소비**: 완전한 참조 폐포 필요(endpoints/tables/mappers 포함).
   자기완결적이어야 한다. generator는 pathId로 sql/http/seeds를 필터만 하고 **dangling 참조 시
   조용히 빈 리스트/누락 테스트를 생성**하므로(§8), 이 모드가 가장 엄격하다.
2. **pruned graph → builder `--incremental-base <dir>`**: builder가 **endpoints는 static index,
   mappers는 인덱싱, tables는 당회 탐색 결과에서 재계산**하므로(`BuilderCli.build`) pruned graph의
   해당 메타는 사용되지 않는다. 즉 이 모드는 path/sql/http/seed 등 **carry 사실만** base에서 가져온다.
   더 관대하지만(현재 인덱스에 없는 endpoint의 path는 carry에서 자동 제외), carry될 path의 seed/sql
   참조는 온전해야 유용하다.

설계 시 prune 도구의 출력이 어느 모드를 겨냥하는지 먼저 못 박을 것. 안전책은 **항상 모드 1 수준의
참조 폐포**를 만족시키는 것(모드 2도 자동 충족).

## 7. 제안하는 닫힌 루프

```
builder(탐색)                       →  <out>/graph.json (+ 파티션 샤드)
        ↓
평가 도구(별개)
  · graph 디렉터리 입력
  · 각 path로 테스트 생성·실행, 유용성 판정
  · 유효 path만 keep + 참조 폐포 + validator
                                    →  <prune-out-dir>/graph.json (자기완결)
        ↓
builder build --incremental-base <prune-out-dir>  [--endpoint … | --changed-files …]
  · keep된 path는 carry
  · 비워진/선택된 endpoint만 "다른 방향"으로 재탐색
                                    →  <out'>/graph.json  (다음 라운드)
```

builder 쪽은 `--incremental-base`가 이미 있어 **신규 builder 변경 없이** 루프가 닫힌다(평가 도구가
신설 컴포넌트). 단 builder는 **graph 디렉터리**를 받으므로 prune 출력은 `<dir>/graph.json` 레이아웃
이어야 하며, builder가 함께 쓰는 **파티션 샤드**(`PartitionedGraphStore`)와의 정합(샤드 재생성/무시)
정책을 정해야 한다(§9).

## 8. 신규로 필요한 것

- **가치 신호(유용성) 정의** — 선결 과제. 후보: ① 생성 테스트 통과 여부, ② path가 기여한 신규
  분기/라인 커버리지(`branchesTaken` 활용), ③ 회귀 검출력(mutation/변경 민감도). 통과만으로는 부족
  (통과하지만 커버리지 0인 path는 가치 낮음). 신호를 잘못 잡으면 그래프가 편향 누적된다.
- **path 단위 prune 엔진** — §5의 참조 폐포를 보장하는 공용 subsetter(가능하면 builder와 공유).
- **참조 무결성 validator** — 출력 게이트. acceptance 수준 요건: **(a)** 남긴 모든 path/exchange의
  ID 참조가 resolve되는지 검사, **(b)** `RequiredSeed.pathId == null`인 read-path seed(orphan으로
  보이지만 유효) keep/prune 정책 명시, **(c)** generator의 silent-failure(dangling 참조 → 빈
  리스트·누락 테스트) 회귀 테스트. 현재 로드 경로(`JsonFileGraphStore`/`PartitionedGraphStore`)에는
  무결성 검증이 **없어** dangling 참조가 조용히 통과 → generator가 깨진/불완전한 테스트를 silent하게
  생성할 위험이 있다.

## 9. 리스크 / 미해결 질문

- **Stale 우선순위.** 소스가 바뀐 endpoint는 "이전에 유효했음"과 무관하게 재탐색되어야 안전
  (변경이 keep을 이김). 가치-기반 keep이 더티 파티션 무효화를 우회하면 낡은 path로 잘못된 테스트가
  생성된다. → keep과 변경-무효화의 우선순위를 명시.
- **유효성 정의의 모호성** (§8) — 루프 품질을 좌우하는 핵심 변수.
- **무결성 검증기 부재** (§8) — 현재 silent 실패 위험.
- **파티션 샤드 정합.** builder는 `graph.json`과 함께 `PartitionedGraphStore`로 샤드를 저장한다
  (`BuilderCli.build`). prune가 `graph.json`만 갱신하면 샤드와 불일치할 수 있다. → prune 출력 시
  샤드 재생성/무시 정책 결정. **확인 필요(별도):** 리뷰에서 `PartitionedGraphStore`의 save/load가
  `GraphAsset`의 에러 계약 디스크립터(`semanticStatusField` 등)를 compat 생성자 때문에 떨어뜨릴
  수 있다는 지적이 있었다 — 사실이면 평가 도구가 이 필드를 신뢰하기 전에 별도 버그로 다뤄야 한다.
- **멀티 루트 SUT 제약.** 멀티 루트 SUT는 v1에서 `--incremental-base`와 동시 사용이 거부된다
  (`BuilderCli`; `docs/03-graph-rag-builder.md`). 이 워크플로에서는 루프 자체가 성립하지 않으므로
  적용 범위에서 명시적으로 제외하거나 제약을 풀 것.
- **재탐색 중복(dedup) 정책.** 같은 endpoint에서 일부 path만 keep하고 나머지를 재탐색할 때, 재탐색이
  keep된 것과 중복 path를 다시 만들지 않도록 정책 필요. 저장소에 이미 set-동등 비교 자산이 있다:
  `graph-rag-builder/.../parallel/GraphSetEquivDiffTool`(`exploredPathKey` = endpointId +
  semanticStatus + branches). "id 중복"을 막을지 "의미적 중복(set key)"을 막을지 선결.
- **스키마 소유권.** 평가 도구가 `shared-model`의 `GraphAsset` 스키마에 의존 → 스키마 변경 시 동반
  갱신. 스키마 버전/검증을 도입할지 검토.

## 10. 추후 착수 시 워크플로우

1. `brainstorming`으로 §8·§9의 미해결 질문(특히 "유효성" 정의, 단위, stale 우선순위, 샤드 정합)을 확정.
2. `requirements-spec`으로 REQ-ID + Given-When-Then + 추적 매트릭스 작성.
3. `writing-plans`로 구현 계획(이중루프: E2E 먼저 → unit TDD).
4. 각 문서 3-벤더 design-doc 리뷰 1회.
5. 전용 worktree+브랜치, PR 전 게이트(코드 리뷰·회귀 green·문서 갱신), repo는 rebase-only.

### 착수용 세션 프롬프트(복붙용 초안)

```
graph-rag-test-generator에서 "graph.json 외부 평가·prune 도구"를 진행해줘.

배경/설계: docs/superpowers/followup/2026-06-25-graph-json-reuse-prune-followup.md 참고.
현재 builder는 --incremental-base <prev-graph-dir>(파일 아닌 graph 디렉터리)로 carry-over
(IncrementalBuildPlanner.plan/planForEndpoints)와 정적 인덱스 캐시(IndexCache)를 이미 지원한다
(단방향: builder→graph→generator, 가치 피드백 없음). 두 carry 모드의 참조 폐포 완성도가 다름에 주의
(plan()은 seeds/kafka 미이월; planForEndpoints()만 전체 이월).

목표: 생성 테스트의 유용성을 평가하는 별개 도구를 만들어, graph를 입력받아 유효 path만 참조 폐포를
지켜 추린 graph 디렉터리(<dir>/graph.json, 자기완결)를 출력한다. 그것을 builder --incremental-base로
넣어 유용한 부분은 carry, 비워진 부분만 다른 방향으로 재탐색하는 닫힌 루프를 완성한다.

선결: "유효성(가치)" 신호 정의(통과/신규 커버리지 기여/회귀 검출력 중), path 단위 prune의 참조 폐포
규칙(GraphAsset의 endpointId/pathId/requiredSeedIds, ws·kafka의 capturedSqlIds 포함 — 문서 §4~5),
참조 무결성 validator(ID resolve + pathId-null seed orphan 정책 + generator silent-failure 회귀),
stale 우선순위(소스 변경이 keep을 이김), 파티션 샤드 정합, 멀티 루트 제약, dedup(GraphSetEquivDiffTool
set key 재사용).

재사용: 참조 subsetting은 planForEndpoints()의 seed/kafka 분기를 포함한 공용 GraphAssetSubsetter로
추출해 builder와 공유. 워크플로우: brainstorming → requirements-spec → writing-plans → 이중루프
구현. 각 문서 3-벤더 리뷰. 전용 worktree+브랜치, PR 전 게이트, rebase-only. 코드네임은 코드·문서·
커밋에 남기지 마라.
```

## 11. 관련 코드/문서 포인터

- 모델: `shared-model/src/main/java/io/graphrag/model/{GraphAsset,ExploredPath,CapturedSql,
  CapturedHttpCall,RequiredSeed,WsExchange,KafkaExchange,CapturedEventEmit,Outcome}.java`
- carry-over: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/IncrementalBuildPlanner.java`,
  `.../cli/BuilderCli.java`(`build`, `staticIndexWithCache`, `indexStatically`)
- 캐시: `graph-rag-builder/src/main/java/io/graphrag/builder/store/IndexCache.java`,
  `.../index/SharedSpoonModel.java`
- 그래프 저장/로드: `graph-rag-builder/src/main/java/io/graphrag/builder/store/{JsonFileGraphStore,
  PartitionedGraphStore,GraphPartitioner}.java`
- dedup/set-동등: `graph-rag-builder/src/test/java/io/graphrag/builder/parallel/GraphSetEquivDiffTool.java`
- builder 사용·제약: `docs/03-graph-rag-builder.md`(`--incremental-base <prev-graph-dir>`, 멀티 루트 제약)

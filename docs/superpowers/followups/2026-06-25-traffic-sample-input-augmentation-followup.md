# 트래픽 샘플 기반 입력 보강 — 어댑터 설계 (후속 작업)

> 상태: **추후 진행용 설계 노트(follow-up)**. 구현 착수 전 단계.
> 실제 호출 트래픽에서 추출한 샘플(엔드포인트별 method/path/queryParam/count/sampleRequests)이
> 주어질 때, 이를 graph-rag-builder의 입력 합성·탐색 우선순위에 **힌트로 주입**하는 어댑터와 사용법을
> 정리한다. 구현 spec/plan은 아니며, 착수 시 `brainstorming → requirements-spec → writing-plans`
> 순서로 별도 작성한다.
>
> 짝 문서: [`2026-06-25-graph-json-reuse-prune-followup.md`](./2026-06-25-graph-json-reuse-prune-followup.md)
> — 그쪽은 builder **출력(graph)을 평가해 거르는** 축, 이 문서는 **입력을 트래픽으로 시드/우선순위화**
> 하는 대칭 축이다.
>
> 범위: **HTTP REST 엔드포인트 전용.** builder가 탐색 단위로 다루는 WebSocket(`WsEndpoint`)·
> Kafka(`KafkaConsumer`)는 이 샘플 포맷의 대상이 아니다(무시·경고).

작성일: 2026-06-25 · 3-벤더 design-doc 리뷰 1회 반영

---

## 1. 입력 샘플 포맷

가정하는 입력(JSON 배열, 엔드포인트별 1 객체):

```json
[
  {
    "method": "GET",
    "path": "/a/b/c/d",
    "queryParam": ["id", "blah"],
    "count": 123,
    "sampleRequests": [
      "/a/b/c/d?id=123xxx",
      "/a/b/c/d?id=124xxx"
    ]
  }
]
```

- `method` + `path` — 엔드포인트 식별 키.
- `queryParam` — 관측된 쿼리 파라미터 이름들.
- `count` — 호출 빈도(우선순위 신호).
- `sampleRequests` — 실제 요청 예시(실제 값 포함).

이 포맷에 **없는 것**(중요): 응답 status·body, request body, path variable 구조 표기. 따라서 이
샘플은 **입력(요청) 측만** 보강하며, oracle/외부 stub body 합성에는 직접 기여하지 못한다.

## 2. builder의 현재 입력 합성 (통합 지점)

- **정적 파라미터 추출**: `Endpoint.params()` → `EndpointParam(String name, String javaType,
  ParamKind kind)`, `ParamKind.PATH|QUERY|...`. path/query 파라미터를 Spoon 정적 분석으로 이미 추출.
  값 합성이 `javaType`에 의존(정수/bool/날짜/enum 분기)하므로 트래픽 값도 `javaType`에 맞춰
  검증/변환해야 한다.
- **read(GET) 입력·시드 합성**: `io.graphrag.builder.run.ReadInputSynthesizer.synthesize(endpoint,
  tables, ResolutionHint hint)`. path/query param을 WHERE 제약으로 보고 타깃 테이블에 매칭 행을
  시드. 파라미터 값은 **private** 헬퍼 `scalarFor(param, probeId)`가 **합성**한다(엔드포인트별
  결정적 probe id: `PROBE_ID_BASE(90001)` + `floorMod(endpoint.id().hashCode(), 9000)` →
  90001–98999. SUT 선시드 행과 비충돌하도록 큰 값. 실제 값 아님).
- **외부 힌트 주입 지점(기존)**: `ResolutionHint(String table, Map<String,String> paramColumn)` —
  **탐색 중 캡처한 SELECT SQL에서 도출**해 path-string 휴리스틱을 대체하는 용도(테이블·컬럼 매핑).
  트래픽 샘플에는 테이블/컬럼 정보가 없어 `ResolutionHint`를 직접 채울 수 없다 → **별도의 "파라미터
  값 힌트"(§4)가 필요**하고, 그 힌트를 받을 **public 주입 슬롯도 신설**해야 한다(아래 feasibility).
- **body(POST/PUT) 입력 합성**: `SampleInputSynthesizer.synthesize(shape, tables,
  fieldConstraints)` — BodyShape + FK seed 기반. 샘플에는 body가 없어 이 경로는 보강 대상 아님.
- **완성 path 주입(기존)**: `BuilderCli.mergeManualPaths`(`--manual-paths` 디렉터리) — 외부에서
  작성한 **완성 `ExploredPath`**(sampleInput+expectedStatus+sampleResponse…)를 병합(id 충돌 시
  수동본 우선). 입력만 있는 트래픽 샘플은 완성 path가 아니므로 이 경로에 그대로 못 넣는다(응답 없는
  path는 generator가 깨진 테스트를 만든다 — 짝 문서 §8 silent-failure).
- **탐색 budget**: `--budget-requests`(기본 60)는 **엔드포인트마다 독립적으로** 적용된다(전체 budget을
  endpoints 수로 나누는 "균등 배분"이 아님 — 각 endpoint에 동일 값을 그대로 전달). 또한 HTTP
  엔드포인트 루프는 `--parallelism`(기본 1)으로 병렬 fan-out된다.

> **Feasibility(착수 첫 단계 전제):** `scalarFor`는 `ReadInputSynthesizer`의 **private** 메서드이고
> `ResolutionHint`에는 값 슬롯이 없다. 따라서 트래픽 값 힌트는 현재 API로는 주입 불가 — 먼저
> `synthesize(endpoint, tables, ResolutionHint, TrafficValueHint)` 오버로드를 추가하거나 통합 Hint
> 타입으로 확장하는 **공개 API 변경이 선행**되어야 한다. private `scalarFor` 직접 호출에 의존하지 말 것.

## 3. 트래픽 샘플이 기여할 수 있는 것

1. **쿼리 파라미터 발견 보강** — 샘플 `queryParam` ∖ 정적 `EndpointParam(QUERY)` = 정적이 놓친
   옵셔널/동적 파라미터. 합성기가 채울 파라미터 목록을 넓힌다.
2. **현실적 valid 입력 힌트** — `sampleRequests`의 실제 값(`id=123xxx`)은 happy-path valid 입력의
   강한 힌트. 형식 제약(prefix·길이·패턴)을 형상에서 못 뽑을 때 특히 유용.
3. **탐색 우선순위(budget 배분)** — `count`로 빈도 높은 엔드포인트를 우선 탐색(가중 budget).
4. **엔드포인트 커버리지 대조** — 정적 발견 endpoint ↔ 실제 호출 path 비교로, 정적이 놓친 실사용
   경로(동적 라우팅)나 호출되지 않는 dead route를 식별.

## 4. 어댑터 설계

신규 컴포넌트 `TrafficHintLoader`(가칭)와 빌드 옵션 `--traffic-samples <file>`. builder 내부에서
샘플을 로드해 입력 합성·budget에 주입한다(외부에서 graph를 후편집하는 짝 문서와 달리, 이쪽은 build
입력 단계에 native로 들어가는 것이 자연스럽다 — read 합성기에 힌트 슬롯을 신설해 넣는다).

### 4.1 처리 단계

1. **엔드포인트 매칭 + path 변수 값 추출** — 샘플 `(method, path)` → 정적 `Endpoint`. 정적
   `Endpoint.path`는 Spring 템플릿(`/a/b/{id}/d`, `{id:\d+}` 등)이고 샘플은 concrete path다.
   저장소에 concrete↔template 매칭 유틸이 없으므로(`EndpointSelector.matchMethodPath`는
   `path().equals(...)` exact match만, `EndpointIndexer.extractPlaceholders`는 placeholder 추출만)
   **세그먼트 매칭 알고리즘을 신설**해야 한다: 세그먼트 수 일치 + placeholder 세그먼트는 와일드카드
   (정규식 suffix `{id:\d+}` 고려) + trailing slash/context-path/대소문자 정규화. 매칭 시 placeholder
   세그먼트에 대응하는 concrete 값이 곧 **PATH 파라미터 관측값**이다(쿼리만 파싱하면 path 변수 값이
   누락된다). 매칭 실패는 **버리지 말고 로그**(정적 미발견 = 동적 라우팅 후보 또는 dead route — §3.4).
2. **쿼리 파라미터 보강** — 샘플 `queryParam` 중 정적에 없는 것을 신규 `QUERY` 파라미터 후보로 기록.
   정책 결정 필요: (a) 경고만, (b) 탐색에 반영. `Endpoint`는 shared-model의 **immutable record**라
   런타임 추가가 불가하므로, (b) 채택 시 `Endpoint.withAugmentedParams(...)` 복사본 또는 runner에
   overlay `Map<String,EndpointParam>`을 전달하는 설계가 필요하다.
3. **파라미터 값 힌트 생성** — `sampleRequests`를 URL 파싱(쿼리)하고 §4.1-1의 세그먼트 매칭(path 변수)
   결과를 합쳐 `paramName → 관측값 목록`을 만든다. 신규 레코드 `TrafficValueHint(Map<String,
   List<String>> paramValues)`를 `synthesize`의 신설 슬롯(§2 feasibility)으로 주입해 합성값을 대체/보완.
   **다중 관측값 정책**(`id=123xxx`, `124xxx`): 변종 폭발/budget 초과를 막기 위해 **대표값 1개 또는
   상한 K개**로 제한(기본 1). **값 사용 정책**(§4.2)이 핵심.
4. **budget 가중치** — `count`를 정규화해 엔드포인트별 탐색 예산을 가중. budget이 per-endpoint
   독립값(§2)이므로, 가중은 **per-endpoint `budgetRequests` 승수** 또는 **탐색 순서 정렬 가중치** 중
   하나로 의미를 확정해야 한다. 희귀 endpoint를 굶기지 않도록 `--traffic-budget-floor`/
   `--traffic-budget-cap`로 상·하한. `--parallelism>1` 시 가중을 사전 정렬에 적용할지 워커 큐
   우선순위로 적용할지도 결정.

### 4.2 값 사용 정책 (`--traffic-value-mode`)

`sampleRequests`의 실제 값(`id=123xxx`)은 production DB 상태에 valid한 값이다. 테스트 SUT의 seed된
DB엔 그 행이 없어 **그대로 재생하면 404/빈 결과**(valid 입력이 아님). 3가지 모드:

| 모드 | 동작 | 적합 |
|---|---|---|
| `pattern`(기본·안전) | 값에서 **형식/패턴만** 추출(숫자/문자 구성, 길이, prefix)해 합성값 형태를 `param.javaType()`에 맞게 맞춘다. 실제 값은 저장·전송하지 않음 | 형식 제약이 까다로운 파라미터 |
| `seed` | 관측 값으로 **타깃 테이블에 행을 시드**한 뒤 그 값으로 조회 | 조회 키가 DB 행에 직접 대응할 때 |
| `verbatim` | 값을 그대로 사용 | 외부 의존 없는 stateless 파라미터에 한정 |

- `seed` 모드는 `ReadInputSynthesizer`의 기존 시드 경로(타깃 테이블 + FK 부모 시드)에 관측 값을 PK/키
  컬럼 값으로 흘려보내는 확장이다. **용어 주의:** 기존 "2-pass"는 `EndpointExplorationRunner`의
  SQL-기반 pass-2(캡처한 SELECT → `ResolutionHint` 재시드·재탐색)를 뜻한다. 이 traffic seed는
  탐색 **전** 관측 키로 초기 시드를 넣는 별개 개념이므로 **"traffic-observed seed"**로 명명하고,
  기존 SQL pass-2와의 실행 순서(선행/병행)를 brainstorming에서 확정한다.
- `verbatim`은 위험하므로 **적용 대상을 선별**(특정 param/endpoint를 지정하는 설정)할 수 있어야 한다.
  전역 적용 시 stateful 의존으로 404가 다수 발생한다.

### 4.3 출력/주입 형태 + 추적

- `--traffic-samples`로 들어온 샘플은 **graph.json에 직접 쓰지 않는다**(입력 힌트일 뿐). build 중
  read 합성·budget에만 영향을 주고, 결과 path는 평소대로 탐색이 생성한다.
- **추적**: 현재 `exploration-report.json`(shared-model `ExplorationReport` record)에는 traffic
  필드가 없다. 매칭/미매칭 샘플, 추가된 queryParam, 적용 value-mode, budget 가중을 기록하려면
  `ExplorationReport`를 (compat 생성자 패턴으로) 확장하거나 **별도 `traffic-hints-report.json`**을
  내보낸다 — 둘 중 하나를 확정.

## 5. 사용 방법 (예상 CLI)

```bash
# 1) 트래픽 샘플 파일 준비 (§1 포맷, JSON 배열)
#    access log / APM 익스포트를 이 포맷으로 변환. PII는 주입 전 전처리로 익명화(§6).

# 2) build 시 주입
graph-rag-builder build \
  --sut-src <src> --sut-jar <jar> --out <out-dir> \
  --traffic-samples traffic.json \
  --traffic-value-mode pattern \    # pattern(기본) | seed | verbatim
  --traffic-weight-budget \         # count로 엔드포인트 budget 가중(옵션)
  --traffic-budget-floor <n> --traffic-budget-cap <n>   # 가중 상·하한(옵션)

# 3) 결과 확인: exploration-report.json(또는 traffic-hints-report.json)에서
#    매칭/보강/가중 내역. out/graph.json은 평소대로(힌트는 입력에만 작용)
```

앞 문서의 graph 재활용 루프와 조합하면:

```
traffic.json ──(입력 힌트)──┐
                            ▼
builder build --traffic-samples …  →  graph.json
                            │
                  (평가·prune 도구, 짝 문서)
                            ▼
builder build --incremental-base <pruned-dir> --traffic-samples …  (다음 라운드)
```

## 6. 한계·리스크 (반론)

- **응답/oracle/body 없음** — 입력 측만. 기대 응답·외부 stub body·POST body 합성엔 무용.
- **값의 DB 의존성** — §4.2. 그대로 재생(verbatim) 시 invalid 입력 위험 → 기본 `pattern`.
- **path variable 모호성** — `/a/b/c/d`가 정적/path-var인지 샘플만으론 불명 → 정적 endpoint와
  세그먼트 매칭 필수(§4.1-1), 실패 시 폐기 말고 로그.
- **분기 편중** — production 트래픽은 happy-path에 쏠려 에러/경계/희귀 분기 신호가 거의 없다.
  concolic/mutation이 여전히 분기 탐색 주력이고, 샘플은 happy-path 시드·우선순위 보강 역할.
- **budget 가중의 역효과** — `count` 우선이 희귀 분기/endpoint 탐색을 굶길 수 있다 → floor/cap 필수.
  `--parallelism>1`과의 상호작용도 정의(§4.1-4).
- **PII/민감정보(모순 주의)** — `seed`/`verbatim`은 실제 값으로 SUT를 호출하므로, builder의 캡처
  엔진(OTel/로그 파서)이 그 값을 `httpCalls`/`sql` 바인딩에 **graph.json으로 영속화**한다 → "graph에
  실제 값 안 남김"과 모순. 해소책: **(a) 주입 전 traffic.json 전처리 익명화를 사전조건으로 명시**,
  그리고/또는 **(b) 캡처 시점 마스킹 필터**. `pattern` 모드는 값 비저장이라 이 문제를 회피한다.
  어느 경우든 코드네임 위생과 동일하게 코드·문서·커밋에 실제 값을 남기지 않는다.
- **ResolutionHint 오해 금지** — 기존 `ResolutionHint`는 SELECT 도출 table/column 매핑이라 트래픽
  샘플로 못 채운다. 신규 `TrafficValueHint`는 별개 경로(§4.1-3).
- **WS/Kafka 범위 밖** — HTTP REST 전용(§1).

## 7. 검증(E2E) 방향

- 매칭: 알려진 endpoint를 가진 샘플 SUT(예: petclinic류)에서 샘플의 GET·queryParam·path 변수가 정적
  endpoint와 매칭되고 보강 내역이 리포트에 기록됨을 out-of-process로 확인.
- 값 정책: `pattern`에서 합성값 형식이 샘플 패턴·`javaType`을 따르는지, `seed`에서 관측 키로 시드된
  행이 조회에 반영되는지 검증.
- budget: `count` 가중이 탐색 순서/예산에 반영되되 floor가 희귀 endpoint 탐색을 보장하는지.
- 회귀: 트래픽 미주입 시 기존 동작 불변(옵션 off가 no-op).

## 8. 추후 착수 시 워크플로우

1. `brainstorming` — (i) `synthesize` 힌트 슬롯 시그니처, (ii) path-var 세그먼트 매칭 규칙,
   (iii) queryParam 보강 정책(경고 vs augmented-endpoint), (iv) §4.2 값 정책 + traffic-observed seed
   순서, (v) budget 가중 의미·floor/cap·parallelism, (vi) 추적 리포트 형태(확장 vs 별도 파일),
   (vii) PII 처리(전처리 vs 캡처 마스킹)를 확정.
2. `requirements-spec` — REQ-ID + Given-When-Then + 추적 매트릭스.
3. `writing-plans` — 이중루프(E2E 먼저 → unit TDD).
4. 각 문서 3-벤더 design-doc 리뷰 1회.
5. 전용 worktree+브랜치, PR 전 게이트(코드 리뷰·회귀 green·문서 갱신), repo는 rebase-only.

### 착수용 세션 프롬프트(복붙용 초안)

```
graph-rag-test-generator에서 "트래픽 샘플 기반 입력 보강 어댑터"를 진행해줘.

배경/설계: docs/superpowers/followups/2026-06-25-traffic-sample-input-augmentation-followup.md.
입력은 엔드포인트별 {method, path, queryParam[], count, sampleRequests[]} JSON 배열(응답·body 없음,
HTTP REST 전용).

목표: 신규 --traffic-samples <file> 옵션 + TrafficHintLoader로 (1) (method,path)↔정적 Endpoint
세그먼트 매칭(Spring template ↔ concrete; placeholder 세그먼트 값 = PATH 파라미터 관측값), (2)
queryParam 발견 보강(Endpoint는 immutable record → augmented copy/overlay), (3) sampleRequests 값을
TrafficValueHint로 ReadInputSynthesizer에 주입, (4) count를 budget 가중(floor/cap, parallelism 고려).
힌트는 graph.json에 직접 쓰지 않고 build 입력에만 작용.

전제(첫 단계): scalarFor는 private이고 ResolutionHint엔 값 슬롯이 없다 → synthesize(endpoint, tables,
ResolutionHint, TrafficValueHint) 오버로드(또는 통합 Hint 타입) 공개 API를 먼저 추가. private 직접
호출 금지.

값 정책 --traffic-value-mode {pattern(기본)|seed|verbatim}: pattern=형식/패턴만 차용(값 비저장,
param.javaType()에 맞춤), seed=관측 키로 타깃 테이블 시드("traffic-observed seed" — 기존 SQL pass-2와
구분), verbatim=그대로(stateless 한정·선별 지정). 다중 관측값은 대표 1개/상한 K개.

PII: seed/verbatim은 캡처 엔진이 httpCalls/sql에 실제 값을 graph로 영속화 → 주입 전 익명화 전처리를
사전조건으로 두거나 캡처 마스킹 필터 추가. 추적: ExplorationReport 확장 또는 traffic-hints-report.json.

통합 지점: shared-model {Endpoint,EndpointParam(name,javaType,kind),ParamKind}, ReadInputSynthesizer.
synthesize/(신설 힌트 슬롯), EndpointSelector.matchMethodPath(현재 exact)·EndpointIndexer.
extractPlaceholders(재사용 검토), --budget-requests/--parallelism, exploration-report.json. 옵션 off는
no-op(회귀 불변).

워크플로우: brainstorming → requirements-spec → writing-plans → 이중루프 구현. 각 문서 3-벤더 리뷰.
전용 worktree+브랜치, PR 전 게이트, rebase-only. 실제 트래픽 값·코드네임은 코드·문서·커밋·graph에
남기지 마라.
```

## 9. 관련 코드/문서 포인터

- 정적 파라미터: `shared-model/src/main/java/io/graphrag/model/{Endpoint,EndpointParam,ParamKind}.java`
  (`EndpointParam(name, javaType, kind)`)
- read 합성·힌트: `graph-rag-builder/src/main/java/io/graphrag/builder/run/ReadInputSynthesizer.java`
  (`synthesize(endpoint, tables, ResolutionHint)`, private `scalarFor`), `.../run/ResolutionHint.java`
- endpoint 매칭/placeholder: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/EndpointSelector.java`
  (`matchMethodPath`), `.../index/EndpointIndexer.java`(`extractPlaceholders`)
- body 합성: `graph-rag-builder/src/main/java/io/graphrag/builder/run/SampleInputSynthesizer.java`
- 완성 path 주입: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java`
  (`mergeManualPaths`, `--manual-paths`)
- budget/옵션: `graph-rag-builder/.../cli/BuilderCli.java`, `.../cli/BuildConfig.java`(`budgetRequests`,
  `parallelism`)
- 추적 리포트: `shared-model/src/main/java/io/graphrag/model/ExplorationReport.java`
- 짝 문서(출력 평가·prune 축): `docs/superpowers/followups/2026-06-25-graph-json-reuse-prune-followup.md`
- builder 사용: `docs/03-graph-rag-builder.md`

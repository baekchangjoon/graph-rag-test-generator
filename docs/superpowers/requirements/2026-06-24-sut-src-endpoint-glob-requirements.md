# `--sut-src` 멀티 루트 + `--endpoint` glob 요구사항명세

> 출처(design spec): docs/superpowers/specs/2026-06-24-sut-src-endpoint-glob-design.md
> 완료 정의(DoD): 커버리지 대상(Must + 미연기 Should) 요구사항이 각각 ≥1개의 통과
> 수용 테스트를 가짐 (대상 매트릭스 전부 🟢).
> 대상 모듈: `graph-rag-builder` · 워크트리: `.claude/worktrees/feat-multi-sut-endpoint`

검증 레벨 표기: **E2E** = out-of-process 빌더 실행(또는 정적 인덱싱 하니스) 후 산출
`graph.json`/정적 인덱스 어설션. **integration** = `BuilderCli`/`EndpointSelector`/
`IndexCache` 등 컴포넌트 직접 호출. **doc** = 도움말/문서 문자열 존재 검증.

## 요구사항 목록

### REQ-001 — `--sut-src` 멀티 루트: 선택 루트 합집합만 정확히 파싱(부분 그래프)
- 유형: Functional
- 우선순위: Must
- 설명: 여러 명시적 소스 루트를 받아 그 합집합만 정적 인덱싱한다. 공통 조상으로
  끌어올리지 않으며, 선택되지 않은 형제 패키지는 정적 인덱스에서 제외된다.
- 수용기준:
  - Given 형제 패키지 `feature`/`common`/`other`(각 컨트롤러 1개), When `--sut-src
    '…/{feature,common}'`로 정적 인덱싱, Then 산출 endpoints id 집합 = `{post-api-
    feature, get-api-common}`이고 `get-api-other`는 **부재**.
- 검증 레벨: E2E (정적 인덱싱)

### REQ-002 — `--sut-src` brace = 콤마-리스트 = 단독 빌드 동치
- 유형: Functional
- 우선순위: Must
- 설명: brace 표현, 콤마 구분 리스트, 개별 단독 빌드가 동일한 루트 집합(→동일 endpoints)
  을 만든다. (dedup은 REQ-017, brace-aware 토큰화는 REQ-018로 분리.)
- 수용기준:
  - Given 루트 `feature`,`common`, When `--sut-src '…/feature, …/common'` /
    `--sut-src '…/{feature,common}'` / (`feature` 단독 ∪ `common` 단독), Then 세
    경우의 endpoints id 집합이 동일(= `{post-api-feature, get-api-common}`).
- 검증 레벨: E2E (정적 인덱싱)

### REQ-003 — `--sut-src` 리터럴 디렉터리 + glob 혼용(한 플래그)
- 유형: Functional
- 우선순위: Must
- 설명: glob 메타문자 없는 패턴은 그 디렉터리 자체를 단일 루트로 채택하고, glob
  패턴과 한 플래그에 섞어 쓸 수 있다(합집합).
- 수용기준:
  - Given 픽스처(design spec §9: `…/multiroot/{feature,common,other}`)에서 `--sut-src
    'a/b/c/feature, a/b/c/common/**'`(리터럴 + 재귀 glob), When 정적 인덱싱, Then
    산출 endpoints id 집합 ⊇ `{post-api-feature, get-api-common}` 이고 `get-api-other`
    는 **부재**.
- 검증 레벨: E2E (정적 인덱싱)

### REQ-004 — `--sut-src` 0매칭 시 안내와 함께 실패
- 유형: Functional
- 우선순위: Must
- 설명: **패턴별 fail-fast** — 명시된 각 패턴이 ≥1 디렉터리에 매칭돼야 한다. 어느
  한 패턴이라도 0매칭(또는 전부 비-디렉터리)이면 그 패턴을 지목해 비정상 종료한다(오타
  즉시 감지). 혼합 입력(`feature, ghost/**`)에서 `ghost/**`가 0이면 전체 실패.
- 수용기준:
  - Given 0매칭 패턴(단독 또는 유효 패턴과 혼합), When 빌드, Then `"--sut-src '<pat>'
    matched no source directory"`로 그 패턴을 지목해 종료(0 그래프 산출 안 함).
  - Given 형식이 깨진 glob(불균형 brace/bracket), When 빌드, Then 원시
    `PatternSyntaxException`이 아니라 사용자용 메시지로 감싼 에러.
- 검증 레벨: integration

### REQ-005 — `--endpoint` glob 매칭(id + `"METHOD /path"`), 정적 목록은 풀 유지
- 유형: Functional
- 우선순위: Must
- 설명: glob 셀렉터는 각 단위의 id와 HTTP `"METHOD /path"` 양쪽에 매칭한다. `--endpoint`
  는 탐색만 스코프하고 정적 endpoints 목록은 풀로 유지한다.
- 수용기준:
  - Given 단일 풀 루트, When `--endpoint 'POST /api/orders/**'`, Then 매칭 엔드포인트만
    탐색 사실(path)을 갖고 비매칭은 탐색 사실 없음. **정적 endpoints 목록은 풀 유지**.
- 검증 레벨: E2E (풀 빌드)

### REQ-006 — `--endpoint` 정확 나열 + glob 혼용(한 플래그)
- 유형: Functional
- 우선순위: Must
- 설명: 각 셀렉터를 **정확 id → 정확 `"METHOD /path"` → glob(메타문자 있을 때)** 3단계
  로 독립 해석해, 정확 나열과 glob을 한 플래그에 섞어 쓸 수 있다(합집합).
- 수용기준:
  - Given `--endpoint 'post-api-orders, GET /api/users/**'`(정확 id + glob), When
    resolve, Then 정확 단위 + glob 매칭 단위의 합집합이 선택됨.
- 검증 레벨: integration

### REQ-007 — `--endpoint` 비-glob 셀렉터의 정확 매칭 하위호환
- 유형: Functional
- 우선순위: Must
- 설명: glob 메타문자가 없는 셀렉터는 기존 정확 매칭(id, `"METHOD /path"`)과 fail-fast
  미스 동작을 그대로 유지한다.
- 수용기준:
  - Given glob 메타문자 없는 정확 셀렉터, When resolve, Then 기존과 동일하게 해석되고
    동작 변화 없음.
- 검증 레벨: integration

### REQ-008 — `--endpoint` glob 0매칭 시 후보와 함께 실패
- 유형: Functional
- 우선순위: Must
- 설명: glob 셀렉터가 아무 단위에도 매칭되지 않으면 침묵·빈 결과가 아니라 후보 목록과
  함께 명시적으로 실패해 사용자가 글로브 오타를 즉시 인식하게 한다(기존 정확-미스 톤).
  형식이 깨진 glob은 원시 `PatternSyntaxException`이 아니라 사용자용 메시지로 감싼다.
- 수용기준:
  - Given 어떤 단위에도 매칭되지 않는 glob 셀렉터, When resolve, Then 후보 목록과 함께
    `IllegalArgumentException`(기존 정확-미스 톤).
  - Given 불균형 brace/bracket glob, When resolve, Then 감싼 사용자용 에러(원시 스택
    트레이스 아님).
- 검증 레벨: integration

### REQ-009 — 표준 glob 문법(플랫폼 독립)
- 유형: Functional
- 우선순위: Must
- 설명: `*`=세그먼트 내(`/` 미포함), `**`=`/` 횡단(재귀), `?`=한 문자, `{a,b}`=택일,
  `[abc]`=문자 클래스. 두 적용 경로로 검증한다: (i) **`--endpoint`** = 문자열
  glob-to-regex 매처(OS 구분자 무관, Windows `\` 비의존), (ii) **`--sut-src`** = 파일
  시스템 NIO `PathMatcher`(패턴의 `/`는 매칭 전 시스템 구분자로 정규화, 디렉터리만
  채택).
- 수용기준:
  - (endpoint) Given `/`-경로를 가진 `"METHOD /path"` glob, When 매칭, Then 플랫폼과
    무관하게 동작(`Path.of` 예외 없음). `a/*` vs `a/**` 구분.
  - (sut-src) Given `…/a/*`(직속) vs `…/a/**`(재귀), When glob 확장, Then 각각 직속
    자식 디렉터리 / 재귀 후손 디렉터리만 채택(비-디렉터리 제외).
- 검증 레벨: integration

### REQ-010 — 두 플래그 동시 사용 = 교집합
- 유형: Functional
- 우선순위: Must
- 수용기준:
  - Given `--sut-src '…/{feature,common}'` + `--endpoint 'GET *'`, When 빌드, Then
    `feature`·`common` 중 GET 단위(= `get-api-common`)만 탐색 대상.
- 검증 레벨: E2E

### REQ-011 — `--sut-resources` 우선, 미지정 시 sibling resources 처리
- 유형: Functional
- 우선순위: Must
- 설명: primary 결정과 resources 스캔을 구분한다. **primary 경로**(로그·경로 파생)는
  `--sut-resources` 부모(있으면) 또는 첫 매칭 루트. **resources 스캔**(MapperXml 등)은
  `--sut-resources`(있으면) 또는 **전 `parseRoots`의 sibling resources**를 순회한다
  (비-primary 루트 mapper XML 포함 — REQ-019와 짝). 미지정 시 첫 루트 sibling 폴백을
  INFO 로그로 고지한다.
- 수용기준:
  - Given 멀티 루트 + 명시 `--sut-resources`, When 빌드, Then **그 디렉터리의** mapper/
    resources가 정적 인덱스에 반영됨(관측: 해당 mapper statement 존재).
  - Given 멀티 루트 + `--sut-resources` 미지정, When 빌드, Then 첫 루트 sibling 폴백을
    INFO 로그로 고지하고 정상 빌드(REQ-019로 전 루트 resources 포함 검증).
- 검증 레벨: integration / E2E

### REQ-012 — 멀티 루트(>1) + `--incremental-base` 동시 지정 거부
- 유형: Functional
- 우선순위: Must
- 수용기준:
  - Given `--sut-src` 멀티 루트(>1) + `--incremental-base`, When 빌드, Then `"--sut-src
    multi-root is not supported with --incremental-base (v1)"` 취지로 명시 거부.
- 검증 레벨: integration

### REQ-013 — `IndexCache` 멀티 루트 freshness(비-primary 루트 변경 감지)
- 유형: Functional
- 우선순위: Must
- 설명: 캐시 지문이 모든 `parseRoots`의 `.java` **및** 스캔 대상 resources(REQ-011)를
  합산하므로, 비-primary 루트(소스 또는 mapper XML)만 변경돼도 stale 캐시 hit가
  발생하지 않는다. manifest는 루트별 prefix로 라벨링하고, 기존 단일 루트 캐시와
  스키마가 다르면 무효화(재인덱싱)한다.
- 수용기준:
  - Given 멀티 루트(`feature`+`common`)로 1차 빌드 후 캐시 존재, When 비-primary
    루트(`common`)의 `.java`만 변경하고 2차 빌드, Then 캐시 **miss**(재인덱싱).
  - Given 비-primary 루트 sibling resources의 mapper XML만 변경, When 2차 빌드, Then
    캐시 **miss**.
- 검증 레벨: integration

### REQ-014 — 비-primary 루트 핸들러의 제약/리터럴 추출 정확성
- 유형: Functional
- 우선순위: Must
- 설명: 탐색 단계의 per-endpoint 소스 분석(`ConstraintExtractor`,
  `LiteralCandidateExtractor`, `ValidationConstraintExtractor`, `HandlerSourceExtractor`,
  `InputOracle.SutCode`/`StaticLiteralOracle`)이 전 `parseRoots`를 파싱하므로, 비-primary
  루트에 사는 핸들러의 제약·리터럴·소스 리터럴 후보가 누락되지 않는다(silent oracle
  약화 방지). MyBatis mapper XML 경로는 비-Spoon이라 REQ-019로 분리.
- 수용기준:
  - Given 핸들러가 비-primary 루트에 있고 비교 가드 + 소스 리터럴이 존재(픽스처:
    비-primary 루트에 `Guards`/literal 패턴 배치), When 멀티 루트 빌드로 그 엔드포인트를
    탐색, Then 그 제약/리터럴 유래 탐색 사실이 **단일 그 루트만으로 빌드했을 때와
    동등**(빈 결과 아님).
- 검증 레벨: integration

### REQ-015 — 하위호환: 단일 루트 회귀(기존 e2e 무수정 통과)
- 유형: Non-functional (호환성)
- 우선순위: Must
- 설명: glob/멀티 루트 미사용 시 기존 코드 경로를 그대로 타며 정적 인덱스가 기존과
  동일하다. 기존 `run-e2e.sh`는 단일 `--sut-src` + (엔드포인트 셀렉터 없이) 전체 탐색을
  수행하므로 이 시나리오로 검증한다. 정확 `--endpoint` 셀렉터의 하위호환은 REQ-007이
  별도로 커버한다.
- 수용기준:
  - Given 단일 `--sut-src <dir>`(엔드포인트 셀렉터 없음), When 기존 `run-e2e.sh`
    무수정 실행, Then tests>0, failures=0, errors=0 (정적 인덱스 기존과 동일).
- 검증 레벨: E2E (CLI 게이트 — JUnit 클래스 아님, 매트릭스에 그 취지 명기)

### REQ-016 — CLI 사용법/문서에 정확 나열 + glob + 혼용 명시(예시 포함)
- 유형: Non-functional (문서/사용성)
- 우선순위: Must (사용자 명시 요구)
- 설명: 빌더 CLI 도움말과 `docs/03-graph-rag-builder.md`가 `--sut-src`·`--endpoint`
  각각의 (1) 정확 나열, (2) glob 패턴, (3) 혼용을 **구체 예시**와 glob 문법(`*`/`**`/
  `{a,b}`)과 함께 안내한다.
- 수용기준:
  - Given 빌더 usage/help 출력, Then `--sut-src`·`--endpoint` 각각에 정확/glob/혼용
    예시 문자열과 glob 문법 설명이 포함됨.
  - Given `docs/03-graph-rag-builder.md`, Then 멀티 루트 절 + glob·혼용 예시가 반영됨.
- 검증 레벨: doc

### REQ-017 — `--sut-src` 매칭 루트 dedup + 결정적 정렬
- 유형: Functional
- 우선순위: Must
- 설명: 여러 패턴이 같은 디렉터리를 매칭해도 `parseRoots`에 1회만 채택하고, canonical
  경로 기준 안정 정렬해 "첫 루트"(primary 폴백)가 결정적이다.
- 수용기준:
  - Given 같은 디렉터리를 두 번 매칭하는 패턴 집합, When 확장, Then `parseRoots`에 그
    디렉터리가 정확히 1회.
  - Given 순서가 다른 동일 패턴 집합, When 확장, Then `parseRoots` 순서가 동일(결정적).
- 검증 레벨: integration

### REQ-018 — brace-aware 토큰화(`--sut-src`·`--endpoint` 공통)
- 유형: Functional
- 우선순위: Must
- 설명: 콤마는 **brace 깊이 0에서만** 리스트 구분자다. `{...}` 안의 콤마는 보존한다
  (현 `parseCsv`의 무조건 `split(",")` 교체). 두 플래그에 동일 규칙.
- 수용기준:
  - Given `'a/b/c/{e,common}'`, When 토큰화, Then 1개 패턴(2개 디렉터리로 확장)이지
    `{e`/`common}` 두 조각으로 쪼개지지 않음.
  - Given `'a, b'`, When 토큰화, Then 2개 패턴.
- 검증 레벨: integration

### REQ-019 — `MapperXmlIndexer` 멀티 루트 resources 스캔(비-primary mapper XML 포함)
- 유형: Functional
- 우선순위: Must
- 설명: `--sut-resources` 미지정 멀티 루트에서, 각 `parseRoots`의 sibling resources를
  순회해 비-primary 루트의 MyBatis mapper XML도 정적 인덱스에 포함한다(REQ-011·013과
  짝). 단일 루트일 때는 기존 동작(단일 resources)과 동일.
- 수용기준:
  - Given 비-primary 루트의 sibling resources에만 존재하는 mapper XML, When `--sut-
    resources` 미지정 멀티 루트 빌드, Then 그 mapper statement가 정적 인덱스에 포함.
- 검증 레벨: integration

### REQ-020 — E2E 테스트 자원 정리(teardown + 자기 스코프 한정 + 누수 0 게이트)
- 유형: Non-functional (테스트 인프라 — 전역 dev-workflow 게이트)
- 우선순위: Must
- 설명: docker compose·`docker run`·백그라운드 SUT/프로세스를 띄우는 모든 신규/수정
  E2E(예: 멀티 루트 E2E, `run-e2e.sh` 파생)는 (1) 모든 종료 경로(성공·실패·예외·
  타임아웃·시그널)에서 teardown 보장, (2) 고유 project name·label로 **자기 것만** 정리
  (무차별 `prune`/`pkill -f` 금지), (3) 스위트 종료 후 자기 컨테이너/프로세스 잔존 0.
  공유·장수명 인프라는 불가침.
- 수용기준:
  - Given 멀티 루트/회귀 E2E가 띄운 docker compose 스택(고유 `-p grb-…`), When 성공·
    실패 어느 종료 경로든, Then 스위트 종료 후 그 project의 컨테이너·볼륨 잔존 0
    (`docker compose -p … down -v --remove-orphans` 또는 Ryuk/`@AfterAll`로 보장).
  - Given 백그라운드 프로세스, Then 캡처한 PID만 종료, 잔존 0.
- 검증 레벨: integration (누수 검증 게이트) — **PR 전 green 게이트의 일부**

## 추적 매트릭스

| REQ-ID | 요구사항 | 수용 테스트 (예정 이름) | Level | Status |
|--------|----------|--------------------------|-------|--------|
| REQ-001 | 멀티 루트 부분 그래프(형제 제외) | `MultiRootStaticE2E#selectedRootsOnly` | E2E | 🔴 planned |
| REQ-002 | brace=콤마=단독 동치 | `MultiRootStaticE2E#braceEqualsCommaList` | E2E | 🔴 planned |
| REQ-003 | sut-src 리터럴+glob 혼용 | `MultiRootStaticE2E#mixLiteralAndGlob` | E2E | 🔴 planned |
| REQ-004 | sut-src 0매칭 fail-fast(혼합 포함) | `SutSrcResolveTest#zeroMatchFailsFast`,`#malformedGlobWrapped` | integration | 🔴 planned |
| REQ-005 | endpoint glob, 정적 풀 유지 | `EndpointGlobE2E#globScopesExploreNotStatic` | E2E | 🔴 planned |
| REQ-006 | endpoint 정확+glob 혼용(3단계) | `EndpointSelectorTest#mixExactAndGlob` | integration | 🔴 planned |
| REQ-007 | endpoint 정확 매칭 하위호환 | `EndpointSelectorTest#exactBackwardCompat` | integration | 🔴 planned |
| REQ-008 | endpoint glob 0매칭/형식오류 실패 | `EndpointSelectorTest#globZeroMatchFails`,`#malformedGlobWrapped` | integration | 🔴 planned |
| REQ-009 | 표준 glob 문법(endpoint+sut-src) | `GlobMatcherTest#starVsDoubleStar`,`#pathStringPortable`; `SutSrcGlobResolveTest#starVsDoubleStarDirsOnly` | integration | 🔴 planned |
| REQ-010 | 두 플래그 교집합 | `MultiRootStaticE2E#sutSrcIntersectEndpoint` | E2E | 🔴 planned |
| REQ-011 | sut-resources 우선/폴백/로그 | `MultiRootResourcesTest#explicitAndFallbackAndLog` | integration | 🔴 planned |
| REQ-012 | 멀티 루트 + incremental-base 거부 | `BuilderCliArgsTest#multiRootRejectsIncremental` | integration | 🔴 planned |
| REQ-013 | IndexCache 멀티 루트 freshness(소스+resources) | `IndexCacheMultiRootTest#nonPrimaryJavaMiss`,`#nonPrimaryMapperXmlMiss` | integration | 🔴 planned |
| REQ-014 | 비-primary 핸들러 제약/리터럴 추출 | `MultiRootConstraintTest#nonPrimaryHandlerConstraintsEquivalent` | integration | 🔴 planned |
| REQ-015 | 하위호환 회귀(run-e2e.sh, 셀렉터 없음) | `e2e/run-e2e.sh` (CLI 게이트 — JUnit 아님) | E2E | 🔴 planned |
| REQ-016 | CLI 사용법/문서 혼용 명시 | `BuilderCliUsageTest#documentsListGlobMix` + docs/03 갱신 | doc | 🔴 planned |
| REQ-017 | sut-src dedup + 결정적 정렬 | `SutSrcResolveTest#dedupAndStableOrder` | integration | 🔴 planned |
| REQ-018 | brace-aware 토큰화(양 플래그) | `BraceAwareCsvTest#bracePreservedCommaSplit` | integration | 🔴 planned |
| REQ-019 | MapperXml 멀티 루트 resources 스캔 | `MultiRootMapperXmlTest#nonPrimaryMapperIncluded` | integration | 🔴 planned |
| REQ-020 | E2E 자원 teardown + 누수 0 게이트 | `E2EResourceLeakTest#noResidualAfterSuite` | integration | 🔴 planned |

Coverage: 0/20 green (0%) — target 100% (대상: Must 20개. 미연기 Should 없음.)

### 제외(분모 외 — 🔵)
- N1 진짜 다중 애플리케이션(마이크로서비스) 통합 그래프 — 🔵 out-of-scope
- N3 `--sut-jar` 분할 — 🔵 out-of-scope (jar은 풀 앱 유지)
- N4 attach 모드 전용 멀티 루트 E2E — 🔵 deferred. 분석 모드 E2E(REQ-001~003, 010)가
  `--sut-src` 공통 파싱 경로를 커버하므로 attach 모드 멀티 루트는 암묵적으로 커버되나,
  attach 전용 E2E는 v2에서 별도 추적(알려진 갭으로 명시).
- N2 멀티 루트 × incremental-base **상호작용**(이월 의미) — 🔵 deferred (단, **거부**는
  REQ-012로 분모 포함)
- R5 탐색 단계 단일 공유 `CtModel` 성능 최적화 — 🔵 deferred (v1은 정확성만)

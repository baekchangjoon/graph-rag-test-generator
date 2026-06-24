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

### REQ-002 — `--sut-src` brace = 콤마-리스트 = 단독 빌드 동치 + dedup
- 유형: Functional
- 우선순위: Must
- 설명: brace 표현, 콤마 구분 리스트, 개별 단독 빌드가 동일한 루트 집합을 만들고,
  중복 매칭은 dedup 된다(결정적 "첫 루트").
- 수용기준:
  - Given 루트 `feature`,`common`, When `--sut-src '…/feature, …/common'` /
    `--sut-src '…/{feature,common}'` / (`feature` 단독 ∪ `common` 단독), Then 세
    경우의 endpoints id 집합이 동일.
  - Given 같은 디렉터리를 두 번 매칭하는 패턴, When 빌드, Then 루트가 1회만 채택.
- 검증 레벨: E2E (정적 인덱싱)

### REQ-003 — `--sut-src` 리터럴 디렉터리 + glob 혼용(한 플래그)
- 유형: Functional
- 우선순위: Must
- 설명: glob 메타문자 없는 패턴은 그 디렉터리 자체를 단일 루트로 채택하고, glob
  패턴과 한 플래그에 섞어 쓸 수 있다(합집합).
- 수용기준:
  - Given `--sut-src 'a/b/c/feature, a/b/c/common/**'`(리터럴 + 재귀 glob), When
    빌드, Then `feature`와 `common` 하위 전체가 합집합으로 인덱싱.
- 검증 레벨: E2E (정적 인덱싱)

### REQ-004 — `--sut-src` 0매칭 시 안내와 함께 실패
- 유형: Functional
- 우선순위: Must
- 설명: 어떤 패턴도 디렉터리에 매칭되지 않으면(또는 전부 비-디렉터리) 확인한 베이스
  경로 안내와 함께 비정상 종료한다.
- 수용기준:
  - Given 존재하지 않는 경로 패턴, When 빌드, Then `"--sut-src '<pat>' matched no
    source directory"` 취지의 에러로 종료(0 그래프 산출 안 함).
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
- 설명: 각 셀렉터가 정확→glob 순으로 독립 해석되어, 정확 id/`"METHOD /path"`와 glob을
  한 플래그에 섞어 쓸 수 있다(합집합).
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
- 수용기준:
  - Given 어떤 단위에도 매칭되지 않는 glob 셀렉터, When resolve, Then 후보 목록과 함께
    `IllegalArgumentException`(기존 정확-미스 톤).
- 검증 레벨: integration

### REQ-009 — 표준 glob 문법(플랫폼 독립)
- 유형: Functional
- 우선순위: Must
- 설명: `*`=세그먼트 내(`/` 미포함), `**`=`/` 횡단(재귀), `?`=한 문자, `{a,b}`=택일.
  `--endpoint`는 문자열 glob-to-regex 매처를 써 OS 구분자에 무관하게 동작한다(Windows
  `\` 비의존).
- 수용기준:
  - Given `a/*` vs `a/**`, Then 전자는 직속 자식만, 후자는 재귀 매칭(구분됨).
  - Given `/`-경로를 가진 `"METHOD /path"` glob, When 매칭, Then 플랫폼과 무관하게
    동작(`Path.of` 예외 없음).
- 검증 레벨: integration

### REQ-010 — 두 플래그 동시 사용 = 교집합
- 유형: Functional
- 우선순위: Must
- 수용기준:
  - Given `--sut-src '…/{feature,common}'` + `--endpoint 'GET *'`, When 빌드, Then
    `feature`·`common` 중 GET 단위(= `get-api-common`)만 탐색 대상.
- 검증 레벨: E2E

### REQ-011 — `--sut-resources` 우선, 미지정 시 첫 루트 sibling 폴백
- 유형: Functional
- 우선순위: Must
- 수용기준:
  - Given 멀티 루트, When (a) 명시 `--sut-resources` 지정 → 그 디렉터리 사용,
    (b) 미지정 → 첫 매칭 루트의 `resolveSibling("resources")` 사용, Then 두 경우 모두
    정상 빌드.
- 검증 레벨: E2E

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
- 설명: 캐시 지문이 모든 `parseRoots`의 `.java`를 합산하므로, 비-primary 루트만
  변경돼도 stale 캐시 hit가 발생하지 않는다.
- 수용기준:
  - Given 멀티 루트(`feature`+`common`)로 1차 빌드 후 캐시 존재, When 비-primary
    루트(`common`)의 `.java`만 변경하고 2차 빌드, Then 캐시 **miss**(재인덱싱).
- 검증 레벨: integration

### REQ-014 — 비-primary 루트 핸들러의 제약/리터럴 추출 정확성
- 유형: Functional
- 우선순위: Must
- 설명: 탐색 단계의 per-endpoint 소스 분석이 전 `parseRoots`를 파싱하므로, 비-primary
  루트에 사는 핸들러의 제약/리터럴이 누락되지 않는다(silent oracle 약화 방지).
- 수용기준:
  - Given 핸들러가 비-primary 루트에 있고 그 안에 비교 가드/리터럴이 존재, When 멀티
    루트 빌드로 그 엔드포인트를 탐색, Then 해당 제약/리터럴 유래 탐색 사실이 빈 결과가
    아님(단일 그 루트만으로 빌드했을 때와 동등한 제약이 추출됨).
- 검증 레벨: integration

### REQ-015 — 하위호환: 단일 루트 + 정확 셀렉터
- 유형: Non-functional (호환성)
- 우선순위: Must
- 설명: glob/멀티 루트 미사용 시 기존 코드 경로를 그대로 타며, 정적 인덱스가 기존과
  동일하고 기존 e2e가 무수정 통과한다.
- 수용기준:
  - Given 단일 `--sut-src <dir>` + 정확 `--endpoint`, When 기존 `run-e2e.sh` 실행,
    Then tests>0, failures=0, errors=0 (정적 인덱스 기존과 동일).
- 검증 레벨: E2E

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

## 추적 매트릭스

| REQ-ID | 요구사항 | 수용 테스트 (예정 이름) | Level | Status |
|--------|----------|--------------------------|-------|--------|
| REQ-001 | 멀티 루트 부분 그래프(형제 제외) | `MultiRootStaticE2E#selectedRootsOnly` | E2E | 🔴 planned |
| REQ-002 | brace=콤마=단독 동치 + dedup | `MultiRootStaticE2E#braceEqualsCommaList` | E2E | 🔴 planned |
| REQ-003 | sut-src 리터럴+glob 혼용 | `MultiRootStaticE2E#mixLiteralAndGlob` | E2E | 🔴 planned |
| REQ-004 | sut-src 0매칭 실패 | `SutSrcResolveTest#zeroMatchFails` | integration | 🔴 planned |
| REQ-005 | endpoint glob, 정적 풀 유지 | `EndpointGlobE2E#globScopesExploreNotStatic` | E2E | 🔴 planned |
| REQ-006 | endpoint 정확+glob 혼용 | `EndpointSelectorTest#mixExactAndGlob` | integration | 🔴 planned |
| REQ-007 | endpoint 정확 매칭 하위호환 | `EndpointSelectorTest#exactBackwardCompat` | integration | 🔴 planned |
| REQ-008 | endpoint glob 0매칭 실패 | `EndpointSelectorTest#globZeroMatchFails` | integration | 🔴 planned |
| REQ-009 | 표준 glob 문법(플랫폼 독립) | `GlobMatcherTest#starVsDoubleStar`,`#pathStringPortable` | integration | 🔴 planned |
| REQ-010 | 두 플래그 교집합 | `MultiRootStaticE2E#sutSrcIntersectEndpoint` | E2E | 🔴 planned |
| REQ-011 | sut-resources 우선/폴백 | `MultiRootResourcesE2E#explicitAndFallback` | E2E | 🔴 planned |
| REQ-012 | 멀티 루트 + incremental-base 거부 | `BuilderCliArgsTest#multiRootRejectsIncremental` | integration | 🔴 planned |
| REQ-013 | IndexCache 멀티 루트 freshness | `IndexCacheMultiRootTest#nonPrimaryChangeMiss` | integration | 🔴 planned |
| REQ-014 | 비-primary 핸들러 제약 추출 | `MultiRootConstraintTest#nonPrimaryHandlerConstraints` | integration | 🔴 planned |
| REQ-015 | 하위호환 회귀(run-e2e.sh) | `e2e/run-e2e.sh` (구조적 어설션) | E2E | 🔴 planned |
| REQ-016 | CLI 사용법/문서 혼용 명시 | `BuilderCliUsageTest#documentsListGlobMix` + docs/03 갱신 | doc | 🔴 planned |

Coverage: 0/16 green (0%) — target 100% (대상: Must 16개. 미연기 Should 없음.)

### 제외(분모 외 — 🔵)
- N1 진짜 다중 애플리케이션(마이크로서비스) 통합 그래프 — 🔵 out-of-scope
- N3 `--sut-jar` 분할 — 🔵 out-of-scope (jar은 풀 앱 유지)
- N4 attach 모드 전용 멀티 루트 E2E — 🔵 deferred (분석 모드 E2E로 갈음)
- N2 멀티 루트 × incremental-base **상호작용**(이월 의미) — 🔵 deferred (단, **거부**는
  REQ-012로 분모 포함)
- R5 탐색 단계 단일 공유 `CtModel` 성능 최적화 — 🔵 deferred (v1은 정확성만)

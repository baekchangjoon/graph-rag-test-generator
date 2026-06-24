# 설계: `--sut-src` 멀티 루트 + `--endpoint` glob 지원

- 날짜: 2026-06-24
- 상태: 설계 합의 (사용자 승인 대기)
- 워크트리/브랜치: `worktree-feat-multi-sut-endpoint`
- 대상 모듈: `graph-rag-builder`

## 1. 배경과 문제

graph-rag-builder는 한 번에 **SUT 하나**만 분석한다. `BuildConfig`는 단일
`sutSrc`(Spoon 소스 루트)·`sutJar`·`out` 그래프를 받고, 정적 인덱싱은
`SharedSpoonModel.build(Path)`가 그 단일 루트 하위 전체를 파싱한다.

두 가지 실사용 불편이 있다.

1. **거대 모놀리식을 통째로만 분석**: 500만 라인급 단일 앱을 "피처 패키지 + 공통
   단위"로 좁혀 분석할 방법이 없다. 단일 `--sut-src`는 한 디렉터리 전체를
   파싱·탐색하므로, 관심 없는 형제 패키지까지 비용을 떠안는다. 패키지 트리는 보통
   4번째 세그먼트(`a.b.c.*`)에서 형제 패키지가 급격히 늘어난다 — `a.b.c.d`,
   `a.b.c.e`, `a.b.c.f` … 공통 조상 `a.b.c`를 루트로 잡으면 이 폭증을 그대로
   인덱싱·파싱하게 된다.

2. **엔드포인트 다수 선택의 불편**: `--endpoint`는 콤마로 여러 셀렉터를 받지만
   (`EndpointSelector.resolve`) **정확 id** 또는 **정확 `"METHOD /path"`** 매칭만
   한다. 한 영역의 엔드포인트가 수백~수천이면 손으로 전부 나열해야 한다.

## 2. 목표 / 비목표

### 목표 (이번 범위)

- **G1. `--sut-src` 멀티 루트**: 여러 개의 **명시적** 소스 루트를 받아, 그
  **합집합만 정확히 파싱**한다. 공통 조상으로 끌어올리지 않는다. 표준 glob
  브레이스로 형제 정밀 선택(`a/b/c/{e,common}` → `e`·`common`만, `d`·`f` 제외).
- **G2. `--endpoint` glob**: 셀렉터에 glob 메타문자(`*` `?` `{}` 등)가 있으면 glob
  패턴으로 간주해, 각 단위의 **id**와 **`"METHOD /path"`** 문자열 양쪽에 매칭한다.
  glob이 없는 셀렉터는 기존 정확 매칭 그대로(하위호환).
- **G3. 표준 glob 문법**: Java NIO `glob:` 의미(`*`=경로 세그먼트 내, `**`=`/`
  횡단, `?`, `{a,b}`)를 채택한다.
- **G4. 하위호환**: glob/멀티 루트를 쓰지 않으면(단일 디렉터리 1개, 정확 엔드포인트
  셀렉터) 기존 코드 경로를 그대로 탄다. 검증 기준은 (a) **정적 인덱스**(endpoints/
  ws/kafka 목록·`StaticIndex`)가 기존과 **동일**, (b) 기존 e2e(`run-e2e.sh` 등)의
  **구조적 어설션이 무수정 통과**. 런타임 탐색 사실(path/sql/coverage)은 JaCoCo·
  WireMock·Kafka가 끼어 비결정적이므로 "바이트 동일"은 주장하지 않는다(구조적 동등으로
  한정).

### 비목표 (이번 범위에서 제외 — deferred)

- **N1. 진짜 다중 애플리케이션(마이크로서비스) 통합 그래프**: 서로 다른 여러 앱을
  한 실행에서 분석하고 서비스 간 호출을 상관(traceparent/B3)해 단일 그래프로 엮는
  것은 비목표다. 이번의 "멀티 SUT"는 **하나의 앱을 여러 소스 루트로 좁히는 것**이다.
- **N2. 멀티 루트 × `--incremental-base` 상호작용**: 멀티 루트 스코프와 증분 빌드의
  파티션 이월 의미 결합은 복잡하므로 v1에서 제외한다. 둘을 함께 주면 명시적으로
  거부(에러)한다(§8 참조).
- **N3. `--sut-jar` 분할**: jar은 여전히 풀 앱 1개를 받는다(런타임 부팅·커버리지
  지문에 풀 앱이 필요). 멀티 루트는 **정적 소스 스코프**에만 적용된다.
- **N4. attach 모드 전용 E2E**: attach 모드도 `--sut-src`를 공유하므로 멀티 루트가
  그대로 적용된다(별도 차단 없음 — `--sut-src` 해석은 분석/attach 공통 경로). 단
  attach 모드의 멀티 루트 전용 E2E는 v1에서 추가하지 않는다(deferred). 분석 모드
  E2E로 멀티 루트 정적 스코프를 검증하는 것으로 갈음.

## 3. 용어

- **소스 루트(source root)**: Spoon `addInputResource`에 주는 디렉터리. Spoon은 각
  `.java`의 `package` 선언으로 패키지를 결정하므로, 루트의 디렉터리 이름 자체는
  패키지 해석에 영향을 주지 않는다(파싱 대상 파일 집합만 결정).
- **primary 경로**: 경로 파생 용도(아래)에만 쓰는 단일 대표 경로. 파싱 루트 집합과
  구분된다.
- **부분 그래프(partial graph)**: 선택된 루트만 인덱싱·탐색해 생성된, 앱 일부만 담은
  그래프.

## 4. CLI 표면

### 4.1 `--sut-src` (멀티 루트)

- 값은 **콤마로 구분된 glob 패턴 리스트**. 각 패턴은 디렉터리(소스 루트)에 매칭.
  - 예 1(브레이스): `--sut-src 'a/b/c/{e,common}'`
  - 예 2(콤마 리스트): `--sut-src 'a/b/c/e, a/b/c/common'`
  - 예 3(단일, 기존): `--sut-src a/b/c` → 단일 루트, 기존과 동일.
- **토큰화(brace-aware)**: 콤마는 **brace 깊이 0에서만** 리스트 구분자다. `{...}` 안의
  콤마는 brace 표현의 일부로 보존한다. 즉 `a/b/c/{e,common}`는 1개 패턴(2개 디렉터리로
  확장), `a/b/c/e, a/b/c/common`은 2개 패턴. (현 `BuilderCli.parseCsv` 무조건 콤마
  분할을 brace-aware 분할로 교체.) 경로에 리터럴 콤마가 포함된 경우는 **미지원**으로
  문서화(이 경우 단일 패턴/단일 디렉터리를 쓰거나 brace 사용).
- **glob 확장 알고리즘**: 패턴마다 (1) 첫 glob 메타문자 이전의 최장 비-glob 접두를
  base 경로로 잡고, (2) base에서 `Files.walk` 하며, (3) 각 경로에
  `FileSystems.getDefault().getPathMatcher("glob:"+절대패턴)`을 적용, (4) **디렉터리**인
  항목만 채택. 패턴은 절대경로 또는 실행 디렉터리 기준 상대경로로 해석(기존 `Path.of`
  의미 유지). glob 메타문자가 전혀 없으면 그 경로 자체를 단일 루트로 채택(기존 동작).
- **dedup·정렬**: 전체 패턴 확장 결과의 `parseRoots`는 canonical 경로 기준 **중복 제거
  + 안정 정렬**한다(여러 패턴이 같은 디렉터리를 매칭해도 1회, "첫 루트" 결정이
  결정적).
- **혼용 가능**: 각 패턴이 독립 확장되므로, **리터럴 디렉터리와 glob 패턴을 한
  플래그에 섞어** 쓸 수 있다(예: `--sut-src 'a/b/c/orders, a/b/c/common/**'` → 리터럴
  `orders` + `common` 재귀, 합집합·dedup).
- 매칭 결과가 0개(또는 전부 비-디렉터리)면 후보 안내와 함께 실패(§8).

### 4.2 `--sut-resources` (primary 결정)

- 기존에 이미 존재하는 별도 플래그. 멀티 루트일 때 **명시 `--sut-resources`가
  최우선**. 미지정 시 **첫 매칭 루트**의 `resolveSibling("resources")`로 폴백한다.
- primary 경로(경로 파생·로그 용도)는 **정렬된 첫 매칭 루트**로 항상 정한다.
  `--sut-resources`는 resources 스캔에만 쓰고 primary 결정에는 쓰지 않는다(부모를
  primary로 잡으면 `src/main` 등 java 루트가 아닌 곳을 가리켜 단일-루트 환원 시
  Spoon이 resources를 java로 파싱할 위험 — 구현/리뷰 합의).

### 4.3 `--endpoint` (glob)

- 기존 콤마 리스트 유지(brace-aware 토큰화는 §4.1과 동일 규칙 적용 — `{a,b}` 보존).
  각 셀렉터를 다음 순서로 해석:
  1. **정확 id** 매칭(기존) → 매칭 시 채택.
  2. **정확 `"METHOD /path"`** 매칭(기존) → 매칭 시 채택.
  3. 셀렉터에 **glob 메타문자**(`*`, `?`, `{`, `[` 중 하나 이상)가 있으면 **glob
     매칭**: 각 단위의 `id`와, HTTP 엔드포인트의 `"METHOD /path"`(대소문자 무시
     method) 양쪽에 매칭. 하나라도 매칭되면 그 단위 채택.
- **매처는 문자열 glob-to-regex**(플랫폼 독립). NIO `PathMatcher`를 쓰지 않는다 — 이유:
  (a) `--endpoint` 대상은 파일시스템 경로가 아니라 id/`"METHOD /path"` **문자열**이고,
  (b) NIO `PathMatcher`는 OS 구분자 의존(Windows `\`)이라 `/`-경로에 부적합하며,
  (c) `Path.of("POST /api/...")`가 InvalidPath를 던질 수 있다. glob→regex 변환은
  `*`→`[^/]*`(세그먼트 내), `**`→`.*`(횡단), `?`→`[^/]`, `{a,b}`→`(a|b)`로 정의하고
  나머지 정규식 특수문자는 이스케이프한다.
- **`{` 충돌 주의**: Spring path 변수 `{id}`가 들어간 정확 `"METHOD /path"`를 쓰려면
  1·2단계(정확 매칭)에서 잡히므로 glob으로 가지 않는다. glob 모드에서 `{...}`는 택일
  그룹으로 해석되므로, path 변수를 리터럴로 매칭하려면 정확 셀렉터를 쓰거나 `*`로
  대체한다(문서화).
- **혼용 가능**: 각 셀렉터가 정확→glob 순으로 독립 해석되므로, **정확 id/`METHOD
  /path` 나열과 glob을 한 플래그에 섞어** 쓸 수 있다(매칭 합집합).
  - 예(혼용): `--endpoint 'post-api-orders, GET /api/users/**, get-api-foo'`
  - 예(glob): `--endpoint 'POST /api/orders/**'`, `--endpoint 'post-api-orders-*'`
- glob 셀렉터가 0개 단위에 매칭되면 후보와 함께 실패(기존 정확-미스 동작과 동일 톤).

## 5. 동작 의미 (semantics)

### 5.1 멀티 루트 = 자연 부분 그래프 (별도 필터 불필요)

멀티 루트는 **선택한 루트만 파싱**한다. 따라서 정적 인덱스(`endpoints()` 등) 자체가
선택 루트의 단위만 담게 되고, "인덱싱된 모든 엔드포인트를 탐색"하는 기존 흐름이
자동으로 "선택 루트의 엔드포인트만 탐색"으로 귀결된다. **어느 단위를 탐색하느냐**에는
별도 post-filter가 필요 없다(정적 인덱스가 이미 부분이므로).

> ⚠️ 단, 이는 **탐색 대상 집합**에만 해당한다. **탐색 단계의 소스 기반 분석 배선은
> 별개 문제다**(§5.4). `BuilderCli.explore()`는 핸들러별로 `ConstraintExtractor`·
> `LiteralCandidateExtractor`·`ValidationConstraintExtractor`·`HandlerSourceExtractor`·
> `InputOracle.SutCode`를 **현재 `config.sutSrc()` 단일 경로로** 호출한다. 멀티
> 루트에서 primary만 넘기면 비-primary 루트에 사는 핸들러/DTO의 제약·리터럴이 빈
> 결과가 되어 오라클이 silently 약화된다. 따라서 이 호출들은 **반드시 `parseRoots`
> 전체**(또는 공유 `CtModel`)를 받아야 한다 — §5.4·§6.3에서 구속한다.

이는 `--endpoint` 스코핑과 **의미가 다르다**:

| 축 | `--sut-src` 멀티 루트 | `--endpoint` 셀렉터 |
|---|---|---|
| 정적 엔드포인트 목록 | 선택 루트만(부분) | 풀 유지(필터 안 함) |
| 탐색 사실(path/sql/…) | 선택 루트만 | 선택 단위만 |
| 효과 | 분석 영역 전체를 좁힘 | 탐색만 좁힘(정적 목록은 보존) |

이 차이는 의도된 것이다 — 사용자는 거대 앱의 **분석 영역 자체**를 피처+공통으로
좁히려 한다(정적 목록까지 부분).

### 5.2 두 옵션 동시 사용 = 교집합

`--sut-src`가 소스(=인덱싱) 영역을 정하고, `--endpoint` glob이 그 안에서 추가
선별한다. 즉 탐색 단위 = (선택 루트에서 인덱싱된 단위) ∩ (`--endpoint` 매칭). 둘 다
없으면 기존 전체 동작.

### 5.3 glob 문법 (G3 구체화)

- NIO `glob:`(`FileSystems.getDefault().getPathMatcher("glob:…")`) 의미를 따른다.
  - `*` = 한 경로 세그먼트 내 임의 문자열(`/` 미포함).
  - `**` = `/`를 횡단하는 임의 문자열(재귀).
  - `?` = 한 문자, `{a,b}` = 택일.
- 사용자 표기 `/abad/*`는 **직속 자식만** 매칭한다(재귀는 `/abad/**`). 표준 glob을
  그대로 채택하므로 이 의미를 문서/도움말에 명시한다.
- `--sut-src`의 glob은 **파일시스템 경로**에 매칭(NIO `PathMatcher`). `--endpoint`의
  glob은 **id 문자열**과 **`"METHOD /path"` 문자열**에 매칭(문자열 glob-to-regex,
  §4.3).

### 5.4 `config.sutSrc()` 사용처 → 이행 후 접근자 매핑 (구속표)

이행의 핵심 산출물. 현재 `config.sutSrc()`(단일 Path)를 쓰는 모든 사용처를 이행 후
어느 접근자로 바꿀지 **이 표로 구속**한다(구현 계획은 이 표를 벗어날 수 없다).

| 사용처 (BuilderCli 등) | 현재 | 이행 후 |
|---|---|---|
| 정적 인덱싱 `indexStatically` (단일 공유 모델) | `sutSrc` | `sourceRoots`(전 루트) → `SharedSpoonModel.build(sourceRoots)` |
| `IndexCache.scan` (캐시 지문) | `sutSrc` | `sourceRoots`(전 루트 `.java` 합산 해시) — §6.4 |
| `ConstraintExtractor.*` (per-endpoint: comparisons/conjunctions/joinGuards/enumColumns/stateGuards/extract/reachableMethods/stringEqualities) | `sutSrc` | `sourceRoots`(전 루트) — 비-primary 핸들러 제약 누락 방지 |
| `LiteralCandidateExtractor.extract` (per-endpoint) | `sutSrc` | `sourceRoots`(전 루트) |
| `ValidationConstraintExtractor.extract` (per-endpoint) | `sutSrc` | `sourceRoots`(전 루트) |
| `HandlerSourceExtractor` (생성자) | `sutSrc` | `sourceRoots`(전 루트) |
| `InputOracle.SutCode.srcDir` (StaticLiteral/Concolic 오라클) | `sutSrc` | `sourceRoots`(전 루트). StaticLiteralOracle은 전 루트 순회, ConcolicOracle은 ASM 바이트코드 기반이라 소스 루트 영향이 작으나 동일 입력 통일 |
| `MapperXmlIndexer` (XML 디렉터리 스캔, 비-Spoon) | resources | 전 루트의 resources(또는 `sutResources`) 순회 — §6.3 |
| 로그 메시지 (`log.info("indexing ... {}", …)`) | `sutSrc` | `sourceRoots.primary()` 또는 전 루트 목록 표기 |
| `sutSrc.resolveSibling("resources")` 기본값 | `sutSrc` | `sourceRoots.primary().resolveSibling(...)` (단 `--sut-resources` 우선, §4.2) |

원칙: **소스를 파싱/분석하는 모든 경로 = 전 `parseRoots`**, **경로 파생(resources
폴백·로그)만 = `primary`**.

## 6. 데이터 모델 / 컴포넌트 변경

### 6.1 신규 값 타입 `SourceRoots`

```
record SourceRoots(List<Path> parseRoots, Path primary) {
    // parseRoots: Spoon이 파싱할 루트 합집합(1개 이상)
    // primary: 경로 파생 용도 단일 대표(resolveSibling/IndexCache 등)
    static SourceRoots single(Path dir);          // 기존 단일-루트 환원
    static SourceRoots of(List<Path> roots, Path primary);
}
```

- 단일 루트만 줄 때 `SourceRoots.single(dir)` → `parseRoots=[dir]`,
  `primary=dir`. 기존 동작과 동일.

### 6.2 `SharedSpoonModel.build`

- `build(SourceRoots)` 추가: `parseRoots`마다 `launcher.addInputResource(...)`를
  호출하고 모델을 1회 빌드한다. 기존 `build(Path)`는 `build(SourceRoots.single(p))`로
  위임(호출부 점진 이행). `setNoClasspath(true)`라 선택 루트 밖(예: `d`/`f`)으로의
  참조는 graceful 미해석.

### 6.3 정적 인덱서/추출기 — 두 부류로 구분

`Path srcDir`로 소스를 파싱하는 사용처를 **두 부류**로 나눠 다르게 이행한다.

**(a) one-shot 인덱서 (build당 1회, 공유 모델로 충분)**: `EndpointIndexer`,
`WsEndpointIndexer`, `KafkaListenerIndexer`, `RouterFunctionIndexer`,
`GatewayRouteIndexer`, `EnumConstantExtractor`, `ResponseDtoIndexer`. 이들은
`indexStatically`에서 한 번 호출되므로, `SharedSpoonModel.build(SourceRoots)`가 만든
**단일 공유 `CtModel`**을 받게 하면 멀티 루트가 자동 반영된다. `Path` 오버로드는
`SourceRoots.single`로 위임만.

- **정정**: `ConverterRegistryIndexer`는 이미 `EndpointIndexer.index(CtModel)` 안에서
  **이미 빌드된 `CtModel`로** 호출된다(Path 오버로드는 프로덕션 미사용). 따라서 별도
  시그니처 변경 불요 — EndpointIndexer가 공유 모델을 쓰면 자동 이행. (앞선 "~14개"
  표기에서 제외.)

**(b) per-endpoint 추출기 (탐색 루프에서 핸들러별 호출, 자체 `Launcher` 빌드)**:
`ConstraintExtractor`(메서드별 독립 `Launcher` 8곳: `extract`, `reachableMethods`,
`extractComparisons`, `extractConjunctions`, `extractJoinGuards`, `extractEnumColumns`,
`extractStateGuards`, `extractStringEqualities`), `LiteralCandidateExtractor.extract`,
`ValidationConstraintExtractor.extract`, `HandlerSourceExtractor`. 이들은
`BuilderCli.explore()`에서 `config.sutSrc()`로 호출된다(§5.4 표). **이행 규칙**:
입력을 `Path → SourceRoots`로 바꾸고 내부에서 `parseRoots` 전체를 `addInputResource`
한다(전 루트 파싱). 그래야 비-primary 루트 핸들러의 제약/리터럴이 누락되지 않는다.

- **비-Spoon 사용처**: `MapperXmlIndexer`는 XML 디렉터리(resources)를 스캔한다 →
  `--sut-resources`(있으면) 또는 **전 `parseRoots`의 sibling resources**를 순회해
  비-primary 루트의 mapper XML도 포함한다.
- `InputOracle.SutCode.srcDir`도 `SourceRoots`로(§5.4 표).

**성능(명시적 deferred)**: (b)의 per-endpoint Spoon 재빌드는 **선재 동작**이다(현재도
핸들러별 재빌드 + `reachableCache`). 멀티 루트에서 루트당 파싱이 늘어 비용이 커질 수
있으나, **탐색 단계를 단일 공유 `CtModel` 재사용으로 합치는 광범위 리팩터는 이번 범위
밖(deferred)**으로 둔다 — 기능과 무관한 선재 아키텍처 변경이라 회귀 위험이 크다. v1은
**정확성(전 루트 전달)만 보장**하고, 성능 최적화는 후속으로 분리(§10 R5).

기존 테스트 스위트가 회귀 가드.

### 6.4 `BuildConfig` (record) + `IndexCache`

- `BuildConfig`는 **Java record**다. canonical 생성자에 `SourceRoots sourceRoots`를
  **`sutSrc` 바로 다음(2번째 컴포넌트) 위치**로 추가한다. `sutSrc`(단일 `Path`)는
  `sourceRoots.primary()`와 동일 의미로 유지(경로 파생·로그용). 기존 편의 생성자
  5개는 모두 그 위치에 `SourceRoots.single(sutSrc)`를 명시적으로 전달해 **하위호환**.
  (record는 비-컴포넌트 필드를 못 가지므로 lazy 파생 대신 컴포넌트로 둔다.)
- **`IndexCache.scan` (정확성 — 캐시 freshness)**: 현재 `scan(Path sutSrc, Path
  sutResources, AuthConfig)`는 단일 `sutSrc`만 `Files.walk` 해 지문을 만든다. 멀티
  루트에서 primary만 스캔하면 **비-primary 루트 변경이 감지되지 않아 stale 캐시
  hit**가 난다. `scan(SourceRoots, Path sutResources, AuthConfig)` 오버로드를 추가해
  **모든 `parseRoots`의 `.java`를 합산**해 manifest를 만든다. `staticIndexWithCache`는
  이 오버로드를 쓴다. (수용 기준: §9 E2E-6.)

### 6.5 `EndpointSelector.resolve`

- 정확 매칭(id, `"METHOD /path"`) 실패 후, 셀렉터에 glob 메타문자(`* ? { [`)가 있으면
  **문자열 glob-to-regex 매처**(§4.3, 플랫폼 독립)를 id/`"METHOD /path"`에 적용. NIO
  `PathMatcher`는 쓰지 않는다. 최종 0매칭이면 기존처럼 후보와 함께
  `IllegalArgumentException`. 비-glob 셀렉터의 fail-fast 정확-미스 동작은 보존.

### 6.6 `BuilderCli`

- `--sut-src`를 **brace-aware 토큰화**(§4.1, brace 깊이 0 콤마만 분리; `parseCsv`의
  무조건 분리 교체) → 각 패턴 glob 확장(§4.1 알고리즘) → 디렉터리만 채택 → dedup·정렬
  → `parseRoots`. primary는 §4.2 규칙으로 결정. `SourceRoots` 구성 후 `BuildConfig`에
  주입.
- `--incremental-base`와 멀티 루트(>1) 동시 지정 시 거부(§8, N2).
- `--endpoint` 토큰화도 brace-aware(§4.3).

## 7. 에러 처리

- `--sut-src` glob 0매칭 / 매칭이 전부 비-디렉터리: `"--sut-src '<pat>' matched no
  source directory"` + 확인한 베이스 경로 안내.
- `--endpoint` glob 0매칭: 기존 톤으로 후보 목록과 함께 실패.
- 멀티 루트(>1) + `--incremental-base`: `"--sut-src multi-root is not supported with
  --incremental-base (v1)"`로 명시 거부.
- 멀티 루트인데 `--sut-resources` 미지정: 첫 루트 sibling 폴백을 INFO 로그로 고지.

## 8. 하위호환

- 단일 `--sut-src <dir>` + 정확 `--endpoint`: 기존과 동일(파싱 루트 1개, glob 경로
  미진입). 기존 e2e(`run-e2e.sh` 등)는 무수정 통과해야 한다.
- `BuildConfig` 기존 생성자 시그니처는 유지(내부에서 `SourceRoots.single`로 승급).

## 9. E2E / 수용 테스트 (정의된 완료 기준)

최고 실현 가능 레벨 = **out-of-process 빌더 실행 후 산출 `graph.json` 어설션**
(기존 e2e 하니스 스타일, `:graph-rag-builder:run --args="build …"` → `graph.json`의
endpoints/탐색 사실 검사). 아래는 모두 통과(green)해야 완료다.

> E2E-1·E2E-2·E2E-6은 **정적 인덱싱 단계만** 검증하면 충분하다(멀티 루트는 정적
> 스코프 기능). SUT 풀 부팅 없이 정적 인덱스(endpoints id 목록 + 캐시 manifest)를
> 산출하는 경로(`indexStatically`/`staticIndexWithCache`)를 호출하는 가벼운 하니스로
> 구동해 인프라 의존 없이 빠르게 검증한다. E2E-3는 탐색 사실까지 보므로 풀 빌드.

**픽스처(공통, 신규 — 구속)**: 형제 패키지 3개를 가진 최소 픽스처를 추가한다(샘플
스캐폴딩과 분리된 미니 소스 트리 권장, 또는 order-service 하위에 추가):

```
io/graphrag/sample/multiroot/feature/FeatureController.java   // @PostMapping("/api/feature") 1개
io/graphrag/sample/multiroot/common/CommonController.java     // @GetMapping("/api/common") 1개
io/graphrag/sample/multiroot/other/OtherController.java       // @GetMapping("/api/other") 1개
```

각 컨트롤러는 endpoint 1개. 정적 인덱싱만으로 endpoints id가 결정되므로 런타임 불요.

- **E2E-1 (멀티 루트 부분 그래프 — 형제 제외)**: `--sut-src
  '…/multiroot/{feature,common}'`로 정적 인덱싱 → 산출 endpoints id 집합이 정확히
  `{post-api-feature, get-api-common}`이고 `get-api-other`는 **부재**.
- **E2E-2 (콤마 리스트 = brace 동치, id 집합)**: `--sut-src '…/feature, …/common'`의
  endpoints id 집합 == `--sut-src '…/{feature,common}'`의 집합 == (`…/feature` 단독)
  ∪ (`…/common` 단독). **id 집합 동치만** 비교(탐색 사실 아님). brace-aware 토큰화·
  dedup 검증 포함.
- **E2E-3 (`--endpoint` glob, 풀 빌드)**: 단일 풀 루트(order-service)에서 `--endpoint
  'POST /api/orders/**'`(또는 id glob)로 빌드 → 매칭 엔드포인트만 탐색 사실(path)을
  갖고 비매칭은 탐색 사실 없음. **정적 endpoints 목록은 풀 유지**(§5.1 표 — `--endpoint`
  의 정적-풀 의미 확인).
- **E2E-4 (`--sut-resources` 우선/폴백)**: 멀티 루트에서 (a) 명시 `--sut-resources`
  지정 시 그 디렉터리 사용, (b) 미지정 시 첫 루트 sibling 폴백 — 둘 다 정상 빌드.
- **E2E-5 (하위호환 회귀)**: 기존 `run-e2e.sh`(단일 `--sut-src` + 정확 셀렉터)가
  무수정으로 통과(tests>0, failures=0, errors=0). 정적 인덱스가 기존과 동일(G4-a).
- **E2E-6 (`IndexCache` 멀티 루트 freshness)**: 멀티 루트(`feature`+`common`)로 1차
  빌드 후, **비-primary 루트(`common`)의 `.java`만 변경** → 2차 빌드에서 **캐시
  miss**(재인덱싱)됨을 확인. primary만 스캔하던 결함의 회귀 가드(§6.4). 단위/통합
  레벨로도 가능.
- **E2E-7 (멀티 루트 + `--endpoint` glob 교집합)**: `--sut-src
  '…/{feature,common}'` + `--endpoint 'GET *'` → `feature`·`common` 중 GET만(=
  `get-api-common`) 탐색 대상이 됨(§5.2 교집합 확인).

정의된 완료 = E2E-1..7 전부 green + 단위/통합 테스트 green.

**테스트 자원 정리(전역 게이트)**: docker compose·SUT·백그라운드 프로세스를 띄우는 E2E
(E2E-3/5 및 풀 빌드 케이스)는 모든 종료 경로에서 teardown 보장(고유 `-p grb-…` project
name·label로 자기 것만 `down -v --remove-orphans` 또는 Ryuk/`@AfterAll`, 백그라운드는
PID 한정 종료), 무차별 정리 금지, 스위트 종료 후 자기 자원 잔존 0 확인. 잔존 시
green/완료 주장 불가(PR 전 게이트). 요구사항명세 REQ-020.

## 10. 위험과 완화

- **R1. 추출기 시그니처 변경의 회귀**: 기계적 변경이나 광범위(§6.3 두 부류). 완화 —
  단일-루트 환원(`SourceRoots.single` → `addInputResource(단일)` 동일 호출)으로 기존
  코드 경로의 동작을 보존, 기존 테스트 + E2E-5로 가드. 인덱서별로 점진 이행.
- **R2. `noClasspath` 미해석 증가**: 선택 루트 밖 참조가 미해석될 수 있음. 이미
  `noClasspath(true)`라 신규 실패 모드는 아니나, 부분 스코프에서 일부 정적 사실(예:
  타입 신호) 약화 가능. 한계로 문서화(§11).
- **R3. glob 의미 혼동(`*` vs `**`)**: 표준 채택 + 도움말/문서 명시로 완화.
- **R4. 픽스처 부재(형제 패키지)**: order-service가 단일 평면 패키지 위주 → §9에
  형제 패키지 3개(`feature`/`common`/`other`) 미니 픽스처를 **구체 구속**으로 명시해
  trivially-passing 위험 제거.
- **R5. per-endpoint Spoon 재빌드 비용(멀티 루트)**: 탐색 루프가 핸들러별로 전
  `parseRoots`를 재파싱하면 비용 증가. v1은 정확성만 보장하고 단일 공유 `CtModel`
  재사용 최적화는 deferred(§6.3). 멀티 루트는 보통 풀 모놀리식보다 작은 스코프라 절대
  비용은 단일-풀보다 낮을 수 있음(완화).
- **R6. cross-root 타입 미해석(`noClasspath`)**: 선택 루트 밖 타입은 미해석 → 일부
  정적 신호(타입 기반) 약화 가능. 신규 실패 모드는 아니나(이미 `noClasspath`) 부분
  스코프에서 빈도 증가. §11 한계로 문서화.

## 11. 문서 동기화 대상

- **빌더 CLI 도움말(`BuilderCli` usage 문자열) — 필수**: `--sut-src`·`--endpoint`
  각각에 대해 (1) 정확 나열, (2) glob 패턴, (3) **정확+glob 혼용**을 **구체 예시와
  함께** 사용법에 포함한다(예: `--endpoint 'post-api-orders, GET /api/users/**'`,
  `--sut-src 'a/b/c/orders, a/b/c/common/**'`). glob 문법(`*`=세그먼트 내, `**`=재귀,
  `{a,b}`=택일)도 도움말에 명시. **이는 완료 정의의 일부**(사용자 요구).
- `docs/03-graph-rag-builder.md`: "엔드포인트 선택(`--endpoint`)" 절에 glob·혼용
  추가, 신규 "소스 루트 선택(`--sut-src` 멀티 루트)" 절(혼용 예시 포함) 추가, 한계에
  R2/R6/N2 반영.
- 필요 시 `docs/26-attach-mode.md`(attach는 이번 범위 외지만 `--sut-src` 공통이므로
  멀티 루트 동작 한 줄 교차참조).

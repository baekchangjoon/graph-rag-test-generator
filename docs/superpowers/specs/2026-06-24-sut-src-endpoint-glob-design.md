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
- **G4. 하위호환 100%**: glob/멀티 루트를 쓰지 않으면(단일 디렉터리 1개, 정확
  엔드포인트 셀렉터) 기존 동작과 바이트 단위로 동일한 그래프를 산출한다.

### 비목표 (이번 범위에서 제외 — deferred)

- **N1. 진짜 다중 애플리케이션(마이크로서비스) 통합 그래프**: 서로 다른 여러 앱을
  한 실행에서 분석하고 서비스 간 호출을 상관(traceparent/B3)해 단일 그래프로 엮는
  것은 비목표다. 이번의 "멀티 SUT"는 **하나의 앱을 여러 소스 루트로 좁히는 것**이다.
- **N2. 멀티 루트 × `--incremental-base` 상호작용**: 멀티 루트 스코프와 증분 빌드의
  파티션 이월 의미 결합은 복잡하므로 v1에서 제외한다. 둘을 함께 주면 명시적으로
  거부(에러)한다(§8 참조).
- **N3. `--sut-jar` 분할**: jar은 여전히 풀 앱 1개를 받는다(런타임 부팅·커버리지
  지문에 풀 앱이 필요). 멀티 루트는 **정적 소스 스코프**에만 적용된다.

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
- 패턴은 절대경로 또는 실행 디렉터리 기준 상대경로로 해석한다(기존 `Path.of` 동작
  유지). glob 매칭은 매칭 결과가 **디렉터리**인 항목만 소스 루트로 채택한다.
- 매칭 결과가 0개면 후보 안내와 함께 실패(§8).

### 4.2 `--sut-resources` (primary 결정)

- 기존에 이미 존재하는 별도 플래그. 멀티 루트일 때 **명시 `--sut-resources`가
  최우선**. 미지정 시 **첫 매칭 루트**의 `resolveSibling("resources")`로 폴백한다.
- primary 경로(아래 경로 파생 용도)는 `--sut-resources`가 있으면 그 부모, 없으면 첫
  매칭 루트로 정한다.

### 4.3 `--endpoint` (glob)

- 기존 콤마 리스트 유지. 각 셀렉터를 다음 순서로 해석:
  1. **정확 id** 매칭(기존) → 매칭 시 채택.
  2. **정확 `"METHOD /path"`** 매칭(기존) → 매칭 시 채택.
  3. 셀렉터에 glob 메타문자가 있으면 **glob 매칭**: 각 단위의 `id`와, HTTP
     엔드포인트의 `"METHOD /path"`(대소문자 무시 method) 양쪽에 NIO glob으로 매칭.
     하나라도 매칭되면 그 단위 채택.
- glob 셀렉터가 0개 단위에 매칭되면 후보와 함께 실패(기존 정확-미스 동작과 동일 톤).
  - 예: `--endpoint 'POST /api/orders/**'`, `--endpoint 'post-api-orders-*'`.

## 5. 동작 의미 (semantics)

### 5.1 멀티 루트 = 자연 부분 그래프 (별도 필터 불필요)

멀티 루트는 **선택한 루트만 파싱**한다. 따라서 정적 인덱스(`endpoints()` 등) 자체가
선택 루트의 단위만 담게 되고, "인덱싱된 모든 엔드포인트를 탐색"하는 기존 흐름이
자동으로 "선택 루트의 엔드포인트만 탐색"으로 귀결된다. **sut-src 스코핑에는 별도
post-filter가 필요 없다.**

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
- `--sut-src`의 glob은 **파일시스템 경로**에 매칭. `--endpoint`의 glob은 **id
  문자열**과 **`"METHOD /path"` 문자열**에 매칭(파일시스템 아님).

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

### 6.3 정적 인덱서/추출기 (~14개)

`Path srcDir`로 자체 모델을 재빌드하는 인덱서·추출기(`EndpointIndexer`,
`ConstraintExtractor`, `ValidationConstraintExtractor`, `WsEndpointIndexer`,
`KafkaListenerIndexer`, `GatewayRouteIndexer`, `RouterFunctionIndexer`,
`ConverterRegistryIndexer`, `EnumConstantExtractor`, `LiteralCandidateExtractor`,
`ResponseDtoIndexer`, `MapperXmlIndexer`, `HandlerSourceExtractor`,
`InputOracle.SutCode`)의 입력을 `Path → SourceRoots`로 바꾼다(기계적 시그니처 변경,
내부는 `SharedSpoonModel.build(roots)` 사용). 기존 테스트 스위트가 회귀 가드.

> 단순화를 위해, `SourceRoots`를 받되 내부적으로 단일 `primary`만 쓰던 비-Spoon
> 경로 사용처(`MapperXmlIndexer`의 XML 디렉터리 스캔 등)는 `primary`(또는 모든
> parseRoots를 순회)를 사용한다. 각 사용처의 정확한 선택은 구현 계획에서 확정한다.

### 6.4 `BuildConfig`

- `sutSrc`(단일 `Path`)는 `SourceRoots.primary`로 의미 유지(경로 파생 용도). 신규
  `SourceRoots sourceRoots` 필드를 더한다. 기존 편의 생성자들은 단일-루트
  `SourceRoots.single(sutSrc)`로 채워 **하위호환**.

### 6.5 `EndpointSelector.resolve`

- 정확 매칭 실패 후, 셀렉터에 glob 메타문자가 있으면 NIO glob 매칭을 id/`"METHOD
  /path"`에 적용. 최종 0매칭이면 기존처럼 후보와 함께 `IllegalArgumentException`.

### 6.6 `BuilderCli`

- `--sut-src`를 콤마 분리 → 각 패턴 glob 확장 → 디렉터리만 채택 → `parseRoots`.
  primary는 §4.2 규칙으로 결정. `SourceRoots` 구성 후 `BuildConfig`에 주입.
- `--incremental-base`와 멀티 루트(>1) 동시 지정 시 거부(§8, N2).

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

- **E2E-1 (멀티 루트 부분 그래프 — 형제 제외)**: 형제 소스 루트가 ≥2개인 샘플에서
  `--sut-src '…/{feature,common}'`로 빌드 → `graph.json`의 endpoints가 `feature`·
  `common` 컨트롤러만 포함하고, 제외된 형제(`other`) 컨트롤러 엔드포인트는 **부재**.
  - 픽스처 요구: 형제 패키지 3개(`feature`/`common`/`other`)에 각 컨트롤러 1개를
    가진 샘플. order-service에 최소 추가(예: `…/orders/common`에 `PingController`,
    `…/orders/other`에 트리비얼 컨트롤러)하거나 신규 미니 샘플로 충족. 정확한 픽스처
    형태는 구현 계획에서 확정.
- **E2E-2 (멀티 루트 콤마 리스트 동치)**: `--sut-src 'A, B'`가 `--sut-src 'A'`와
  `--sut-src 'B'`를 각각 빌드한 그래프의 endpoints 합집합과 일치.
- **E2E-3 (`--endpoint` glob)**: 단일 풀 루트에서 `--endpoint 'POST /api/orders/**'`
  (또는 id glob)로 빌드 → 매칭 엔드포인트만 탐색 사실(path)을 갖고, 비매칭은 탐색
  사실 없음. 정적 endpoints 목록은 풀 유지(§5.1 표).
- **E2E-4 (`--sut-resources` 우선/폴백)**: 멀티 루트에서 (a) 명시 `--sut-resources`
  지정 시 그 디렉터리 사용, (b) 미지정 시 첫 루트 sibling 폴백 — 둘 다 정상 빌드.
- **E2E-5 (하위호환 회귀)**: 기존 `run-e2e.sh`(단일 `--sut-src` + 정확 셀렉터)가
  무수정으로 통과(tests>0, failures=0, errors=0).

정의된 완료 = E2E-1..5 전부 green + 단위/통합 테스트 green.

## 10. 위험과 완화

- **R1. ~14개 추출기 시그니처 변경의 회귀**: 기계적 변경이나 광범위. 완화 — 단일-루트
  환원(`SourceRoots.single`)으로 기존 경로를 바이트 동일 유지, 기존 테스트 + E2E-5로
  가드. 인덱서별로 점진 이행.
- **R2. `noClasspath` 미해석 증가**: 선택 루트 밖 참조가 미해석될 수 있음. 이미
  `noClasspath(true)`라 신규 실패 모드는 아니나, 부분 스코프에서 일부 정적 사실(예:
  타입 신호) 약화 가능. 한계로 문서화(§11).
- **R3. glob 의미 혼동(`*` vs `**`)**: 표준 채택 + 도움말/문서 명시로 완화.
- **R4. 픽스처 부재(형제 패키지)**: order-service가 단일 평면 패키지 위주 →
  E2E-1/2를 위해 최소 형제 패키지 추가 필요(구현 계획에서 처리).

## 11. 문서 동기화 대상

- `docs/03-graph-rag-builder.md`: "엔드포인트 선택(`--endpoint`)" 절에 glob 지원
  추가, 신규 "소스 루트 선택(`--sut-src` 멀티 루트)" 절 추가, 한계에 R2/N2 반영.
- 빌더 CLI 도움말(`BuilderCli` usage 문자열).
- 필요 시 `docs/26-attach-mode.md`(attach는 이번 범위 외지만 `--sut-src` 공통이므로
  멀티 루트 동작 한 줄 교차참조).

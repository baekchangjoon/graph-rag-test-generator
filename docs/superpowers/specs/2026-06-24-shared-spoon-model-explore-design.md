# 설계: 탐색 단계 Spoon 모델 단일 공유(R5) — explore 추출기 CtModel 재사용

- 작성일: 2026-06-24
- 브랜치: `feat-shared-spoon-model` (origin/main `8784c1bf` 기준)
- 성격: **순수 내부 성능 리팩터**(외부 관측 행위 불변). PR #94/#97(`--sut-src` 멀티 루트 + `--endpoint` glob)의 의도적 deferred 항목 **R5**.
- 관련 문서: `docs/superpowers/specs/2026-06-24-sut-src-endpoint-glob-design.md`

## 1. 배경·문제

`BuilderCli.explore()`는 정적 분석 단계에서 다음 추출기들을 호출하며, **각 호출이 내부에서
`SharedSpoonModel.build(SourceRoots)`로 Spoon AST를 매번 새로 빌드**한다.

**(a) explore() 직접 호출:**

| 추출기 | 호출 빈도(현재) | Spoon 빌드 |
|---|---|---|
| `ConstraintExtractor.extractComparisons/extractConjunctions/extractJoinGuards/extractEnumColumns/extractStateGuards/extractStateGuardConjunctions` | whole-app 각 1회 (6회) | 각 1빌드 |
| `ConstraintExtractor.extract(roots, class, method)` | 엔드포인트마다 1회 | 각 1빌드 |
| `ConstraintExtractor.reachableMethods(roots, class, method)` | 고유 핸들러마다 1회(`reachableCache`로 완화) | 각 1빌드 |
| `LiteralCandidateExtractor.extract(roots, class)` | 엔드포인트마다 1회 | 각 1빌드 |
| `ValidationConstraintExtractor.extract(roots, dtoType)` | 엔드포인트(shape!=null)마다 1회 | 각 1빌드 |

**(b) 오라클 경유(같은 explore() 블록):**

| 경로 | 호출 빈도(현재) | Spoon 빌드 |
|---|---|---|
| `StaticLiteralOracle.analyze()` → `ConstraintExtractor.extractComparisons` + `extractStringEqualities` | **상시** 1회(루프 전) | **+2빌드** (extractComparisons는 위 whole-app과 중복 추출) |
| `ConcolicOracle.analyze()` | useConcolic 시 1회 | **0** (ASM+Z3 bootJar 바이트코드, Spoon 없음) |
| `LlmOracle.analyze()` → `ValidationConstraintExtractor.extract(roots, dtoType)` | `--llm-oracle` 시 **엔드포인트(shape!=null)마다 1회** | **+E빌드** |
| `HandlerSourceExtractor` | `--llm-oracle` 시 1회(lazy 단일 빌드) | +1빌드 |

엔드포인트 수 E에 대해 빌드 횟수 ≈ `6 + 2(static oracle) + E·(2~3) + 고유핸들러수 (+ llm 시 E + 1)` = **O(E)**.
멀티 루트(`--sut-src` 다수)에선 빌드당 전 루트를 파싱하므로 대형 모놀리식에서 정적 분석이 선형으로 느려진다.

`indexStatically()`는 이미 `SharedSpoonModel.build(roots)`로 **모델 1회 빌드 후 모든 인덱서에 주입**(최적).
explore 단계만 미적용 상태다.

### 빌드 주체 실측(3-벤더 리뷰로 정정)
초안의 "StaticLiteralOracle은 ASM+Z3로 Spoon 미사용" 주장은 **틀렸다**. 실측:
- **`StaticLiteralOracle`은 Spoon 사용** — `analyze()`가 `ConstraintExtractor.extractComparisons(sut.roots())` +
  `extractStringEqualities(sut.roots())`를 호출(각 `SharedSpoonModel.build`). explore()가 **상시** 호출(루프 전 1회).
- **`ConcolicOracle`만 ASM+Z3** — bootJar 바이트코드 분석, Spoon 빌드 없음(`grep` 확인).
- **`LlmOracle`은 Spoon O(E)** — `analyze()`가 엔드포인트 루프에서 `valid.extract(sut.roots(), javaType)` 호출
  → `--llm-oracle` 활성 시 body shape 엔드포인트 수만큼 빌드(+ `HandlerSourceExtractor` lazy 1빌드).
- `InputOracle.SutCode`는 `SourceRoots`+`bootJar`를 담는 record일 뿐(직접 Spoon 미빌드).

→ 따라서 **StaticLiteralOracle·LlmOracle도 리팩터 범위에 포함**한다(§3.3). ConcolicOracle은 무관.

## 2. 목표·비목표

**목표.** explore 단계(직접 추출기 **+ StaticLiteralOracle + LlmOracle 경로 포함**)에서 Spoon 모델을
**build 호출당 1회만** 빌드해 재사용 → 빌드 횟수 O(E)→O(1)(llm on/off 무관). 산출 facts는 리팩터 전후
**완전 동등**. 멀티 루트의 전 parseRoots 포함을 보존(비-primary 핸들러 누락 0).

**비목표.** `indexStatically`↔explore 모델 공유는 하지 않는다(아래 §4 근거). 추출 로직·정렬·dedupe·휴리스틱
변경 없음. **외부 관측 행위(CLI 인자·출력·그래프 산출물·종료코드) 불변** — 단, 성능 관측용 INFO 로그 1줄
추가는 허용한다(로그 스트림은 외부 계약이 아님). StaticLiteralOracle의 `extractComparisons` 재-traversal 중복
제거(explore의 `allComparisons` 재사용)는 자족성 보존 위해 **이번 범위 밖**(빌드만 제거; traversal은 저렴).

## 3. 설계

### 3.1 추출기에 `CtModel` 오버로드 추가, 기존 오버로드는 위임(하위호환)

각 추출기에서 **실제 추출 로직을 `CtModel`을 받는 오버로드로 이동**하고, 기존 `SourceRoots`/`Path` 오버로드는
`SharedSpoonModel.build(roots)`로 모델을 만든 뒤 그 `CtModel` 오버로드에 위임한다.

```java
// ConstraintExtractor (9개 public 메서드 동일 패턴)
public List<Comparison> extractComparisons(CtModel model) { /* 본문(기존 로직) */ }
public List<Comparison> extractComparisons(SourceRoots roots) {   // 위임(하위호환)
    return extractComparisons(buildModel(roots));
}
public List<Comparison> extractComparisons(Path srcDir) {         // 기존 위임 유지
    return extractComparisons(SourceRoots.single(srcDir));
}
```

대상:
- `ConstraintExtractor`: `reachableMethods`, `extract`(condition span), `extractComparisons`,
  `extractStringEqualities`, `extractJoinGuards`, `extractConjunctions`, `extractEnumColumns`,
  `extractStateGuards`, `extractStateGuardConjunctions` — 각 `CtModel` 오버로드 추가.
- `LiteralCandidateExtractor.extract(CtModel, classFqn)` 추가.
- `ValidationConstraintExtractor.extract(CtModel, dtoQualifiedName)` 추가.
- `HandlerSourceExtractor`: `HandlerSourceExtractor(CtModel)` 생성자 추가(주입된 모델을 그대로 사용,
  기존 lazy 빌드 경로는 `SourceRoots`/`Path` 생성자에 유지).

### 3.2 `explore()`가 모델을 1회 빌드해 주입

`explore()` 정적 분석 블록 진입 시 한 번만:

```java
CtModel sharedModel = SharedSpoonModel.build(config.sourceRoots());
```

이후 모든 추출기 호출을 `config.sourceRoots()` 대신 `sharedModel`로 교체:
- whole-app 6종 + 엔드포인트별 `extract`/`literal`/`validation` + `reachableMethods` 전부 `sharedModel` 사용.
- `--llm-oracle` 시 `new HandlerSourceExtractor(sharedModel)`.

빌드 직후 검증·관측용 로그 한 줄 추가(누수 없는 단순 관측):
`log.info("explore: shared Spoon model built once (buildCount delta={})", ...)` — e2e 로그에 before/after 근거.

### 3.3 오라클 경로도 `sharedModel` 재사용 (SPI 무결, 구체 생성자 주입)

`InputOracle.SutCode`(공유 SPI record)에 Spoon 결합(`CtModel`)을 추가하지 **않는다**(ConcolicOracle 등
비-Spoon 구현 오염 방지). 대신 **Spoon을 쓰는 구체 오라클의 생성자로 모델을 주입**한다:

- `StaticLiteralOracle(CtModel model)` 생성자 추가 — `analyze()`가 `extractor.extractComparisons(model)` +
  `extractStringEqualities(model)` 사용. 기존 무인자 생성자는 유지(모델 null → `sut.roots()`로 빌드, 하위호환).
- `LlmOracle(..., CtModel model)` 파라미터 추가 — `analyze()`가 `valid.extract(model, javaType)` 사용.
  넘기는 `HandlerSourceExtractor`도 `sharedModel`로 구성.
- explore()에서 `new StaticLiteralOracle(sharedModel)`, `new LlmOracle(..., sharedModel)`로 구성.

`analyze(SutCode)` 시그니처(SPI)는 불변 — 모델은 구현 필드로 흐르고 `SutCode.roots()`는 그대로 ConcolicOracle이
사용한다. 결과 동등: `sut.roots()`와 `sharedModel`은 동일 `config.sourceRoots()`에서 나오므로 추출 facts 불변.

### 3.4 `reachableCache` 처리(프롬프트 질의)

`reachableCache`(핸들러키→reachable 결과 Map)는 **현재 두 역할**을 한다: ① Spoon 재빌드 회피, ② 동일 핸들러
재-traversal 회피. 공유 모델 도입으로 ①은 사라지지만, **② 결과 캐시 가치는 남는다**(같은 핸들러가 여러
엔드포인트에 매핑될 때 `getElements` 순회 1회로 단축). 따라서 **`reachableCache`는 결과 캐시로 유지**하되,
주석을 "Spoon 빌드 가드"에서 "traversal 결과 캐시"로 갱신한다. (제거하면 동작은 같으나 동일-핸들러 재순회
비용이 늘므로 유지가 우월.)

## 4. 대안과 기각

- **A. explore가 indexStatically의 모델을 재사용.** `staticIndexWithCache`는 **캐시 히트 시 Spoon 0회**(디스크
  `IndexResult` 복원)이고, 미스 시 `indexStatically`가 만든 `CtModel`은 facts 추출 후 폐기되어 캐시 경계를 넘지
  못한다. 무거운 `CtModel`을 캐시 너머로 들고 가면 결합도·메모리만 늘고 히트 시엔 모델이 아예 없다. → **기각**.
  explore가 자체 1회 빌드하는 편이 캐시 상태와 무관하게 O(1)이며 결합이 없다.
- **B. 추출기를 stateful(생성자에 모델 주입)으로 전면 전환.** API 파급·테스트 영향이 크다. 오버로드 추가가
  최소 변경(외과적). → **기각**.
- **C. 전역 모델 캐시(static).** 동시성·생명주기 위험. explore 지역 변수로 충분. → **기각**.

## 5. 테스트 / 완료 정의

이 변경은 **외부 관측 행위 불변(순수 내부 리팩터)**이라 **새 요구사항명세를 만들지 않는다**(global dev-workflow
비례성 조항). 기존 E2E가 외부 계약 회귀 가드 역할을 한다.

### 5.1 단위(신규) — O(1) 빌드 + 동등성 직접 입증
신규 `SharedModelReuseTest`(`index` 패키지):
1. **동등성:** 멀티-핸들러 fixture에 대해 각 추출기의 `SourceRoots` 오버로드 결과와
   `CtModel` 오버로드(같은 roots로 빌드한 모델) 결과가 `equals`. 대상 = **9개 ConstraintExtractor 메서드
   (`extractStringEqualities` 포함)** + `LiteralCandidateExtractor.extract` + `ValidationConstraintExtractor.extract`.
2. **O(1):** `resetBuildCount()` 후 모델 1회 빌드 → 9 ConstraintExtractor + literal + validation +
   reachableMethods를 **여러 핸들러/엔드포인트만큼** `CtModel` 오버로드로 호출 → `buildCount()==1`.
3. **대조(before):** 동일 호출을 `SourceRoots` 오버로드로 하면 `buildCount()==호출수`(O(N)). PR에 수치 기록.
4. **멀티 루트:** 2-루트 `SourceRoots`로 빌드한 모델의 `CtModel` 오버로드가 **비-primary 루트 핸들러**의
   제약을 포함(누락 0).
5. **오라클 재사용:** `StaticLiteralOracle(sharedModel).analyze(sut)` 및 `LlmOracle(..., sharedModel)`의
   validation 추출이 추가 빌드 0(모델 1회 빌드 후 `buildCount()==1` 유지) — llm on/off 양쪽 기대값 분리 기록.

### 5.2 기존 단위/통합 회귀(전부 green 유지)
`ConstraintExtractor*Test`, `LiteralCandidateExtractorTest`, `ValidationConstraintExtractorTest`,
`MultiRootConstraintTest`, `MultiRootStaticIndexTest`, `HandlerSourceExtractorTest`,
`SharedSpoonModelTest`, `BuilderStaticIndexTest`, `IndexCacheWiringTest`.

### 5.3 E2E 회귀(완료 게이트)
- `e2e/run-e2e.sh` green (tests>0, failures=0, errors=0)
- `e2e/run-endpoint-glob-e2e.sh` green

### 5.4 완료 정의
위 5.1~5.3 전부 green + PR에 before/after 빌드 횟수(단위 테스트 측정값) 기록 + 코드 리뷰(spec-compliance +
`pr-review-toolkit:code-reviewer`) triage 완료.

### 5.5 실측 결과 (2026-06-24)
- **빌드 횟수 before/after (단위 측정):** `SharedModelReuseTest` — 동일 추출 시퀀스를 `SourceRoots` 오버로드로
  호출 시 `buildCount==13`(O(N)), `CtModel` 오버로드로 호출 시 `buildCount==1`(O(1)). LlmOracle/StaticLiteralOracle
  주입 모델 재사용도 `buildCount==1` 유지.
- **e2e 라이브 측정:** `run-e2e.sh` 로그 `R5: explore static-analysis Spoon builds = 1` — 전 엔드포인트 탐색에서
  공유 모델 1회만 빌드(핸들러 수 무관).
- **공유 모델 변형 가드:** `SharedModelReuseTest.handlerSourceStableAfterSharedModelTraversal` — 전 추출기로
  traverse한 공유 모델의 핸들러 본문 toString이 fresh 모델과 동일(LLM 캐시 키 sha256 보존).
- **E2E:** `run-e2e.sh` ✅ tests=78 failures=0 errors=0. `run-endpoint-glob-e2e.sh` ✅(아래 게이트).
- **기존 실패(이 변경 무관, 규명 완료):**
  - `LlmOracleE2E::REQ-012` — clean base `8784c1bf`(이 브랜치 base)에서도 **동일 실패**(REQ-011은 양쪽 통과).
    즉 origin/main 선재 실패이며 이 리팩터와 무관. 본문 안정성 가드로 캐시 키 메커니즘 영향 없음을 별도 입증.
  - `BuilderIntegrationTest::build_exploresMultiplePathsAndCapturesBothOrms` — 48분 병렬 풀스위트에서
    "SUT process died during boot"(자원 경합). 격리 재실행 시 통과 → 환경성, 이 변경 무관.

## 6. 리스크

- **위임 누락:** 한 `SourceRoots` 오버로드라도 `CtModel` 오버로드로 위임 안 하면 로직 중복·드리프트. → 본문은
  `CtModel` 오버로드에만 두고 나머지는 순수 위임(로직 0줄)으로 강제.
- **HandlerSourceExtractor 이중 빌드:** 새 `CtModel` 생성자는 lazy 빌드를 건너뛰어야(주입 모델 사용). model
  필드를 생성자에서 채우고 `model()`는 null일 때만 빌드.
- **오라클 생성자 누락:** explore가 `new StaticLiteralOracle()`/무인자 LlmOracle 경로로 잘못 구성하면 오라클이
  여전히 재빌드한다. → explore의 오라클 구성부를 `sharedModel` 주입 생성자로 일괄 교체하고 §5.1.5 테스트로 가드.
- **메모리 피크:** 대형 모놀리식에서 explore 정적 분석 블록 동안 `CtModel` 1개 상주 → 인덱싱 단계와 별개 피크
  가능. 완화: 모델 참조를 explore 정적 분석 지역 변수로 한정(블록 종료 시 GC 대상). 인덱싱 모델과 동시 상주
  아님(인덱싱 모델은 `indexStatically` 종료로 이미 폐기). 순증가는 모델 1개분으로 제한.
- **누수:** 없음(인프로세스 단위 + 기존 e2e 정리 로직 재사용, 새 컨테이너/프로세스 미도입).

## 7. 3-벤더 design 리뷰 triage (2026-06-24)

Claude Sonnet `design-doc-reviewer` + Gemini 3.5 Flash(High) + Cursor(auto). 핵심 수렴 = StaticLiteralOracle Spoon
사용(초안 오류) + 오라클 경로 O(E):
- **수용:** §1 빌드 주체 정정(StaticLiteralOracle Spoon 사용, ConcolicOracle만 ASM+Z3) / 오라클 경로
  (StaticLiteralOracle·LlmOracle) 범위 포함(§3.3) / 빌드 횟수 표·수식에 오라클 행 추가 / §3.1 주석·§5.1 메서드 수
  8→9(`extractStringEqualities` 포함) / 로그 허용 범위 명시(§2) / 메모리 피크 리스크(§6).
- **기각:** Cursor I5 "explore의 `allComparisons`를 StaticLiteralOracle에 재사용해 중복 traversal 제거" — 오라클
  자족성(InputOracle 계약: SUT만 받아 스스로 추출) 보존 위해 보류. 빌드 중복은 제거하되 traversal 중복은 저렴해
  방치(§2 비목표 명시). Cursor I3의 일부(LlmOracle validation을 explore `fieldConstraints`와 동일 소스로 공유) —
  동일 사유로 모델 주입까지만(facts 재사용은 결합 증가) 채택.

## 8. 코드 리뷰 triage (2026-06-24, PR 전)
spec-compliance(general-purpose, 설계 대조) + code-quality(`pr-review-toolkit:code-reviewer`) 2종 실행.
- **spec-compliance: COMPLIANT** — 9 메서드 오버로드·explore 배선·오라클 주입·하위호환·범위 무확장 모두 일치, 편차 0.
- **code-quality: high-confidence 이슈 0**(공유 모델 변형 위험은 가드 테스트로 충분). 마이너 2건 triage:
  - buildCount delta 로그가 프로세스 전역 카운터 기반(~40, INFO 전용) → **기각**. explore는 단일 스레드·로그는
    비계약이며, 실제 O(1)은 단위 테스트(`resetBuildCount` 격리)로 강제. 오히려 루프 내 빌드 재도입 시 delta>1로
    드러나는 회귀 카나리아라 유지가 이득.
  - 오라클 null-fallback 경로 미테스트(~35) → **수용**. LlmOracle null 경로는 기존 `LlmOracleTest`(7-arg 생성자)가
    이미 커버. StaticLiteralOracle 무인자 경로는 `staticLiteralOracleNoArgFallbackEquivalentToInjected` 추가로 닫음.

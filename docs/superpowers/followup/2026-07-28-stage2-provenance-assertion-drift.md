# 단계2 fuzzing E2E의 provenance 단언 drift 해소 계획

> **에이전트 작업자용:** 필수 서브스킬 — superpowers:subagent-driven-development(권장) 또는
> superpowers:executing-plans로 task 단위 실행. 각 스텝은 체크박스(`- [ ]`)로 추적한다.

**목표:** `Stage2AStringLiteralFuzzingE2E#REQ-004`와 `Stage2EnumResponseFuzzingE2E#REQ-004`의
`allMatch(SYNTHESIZED)` 단언이 이후 도입된 `egressassert` 경로의 `CONTRACT` provenance와 어긋나
`main`에서 상시 실패하는 상태를 해소한다. **이 문서는 계획만 담는다 — 이 문서를 쓴 브랜치에서는
코드를 고치지 않았다.** 이 drift는 특정 브랜치의 회귀가 아니라 2026-06-25 커밋들이 남긴 선재
결함이다.

**아키텍처(현 상태 요약):**
- `EndpointExplorationRunner`의 변형 루프는 **같은 외부 callSite 경로**(`site.pathLiteral()`)에 대해
  두 종류의 `CapturedHttpCall`을 만든다 — `-responsevar-`(provenance `SYNTHESIZED`)와
  `-egressassert-`(provenance `CONTRACT`). 소스에도 그 혼재가 명시돼 있다:
  [EndpointExplorationRunner.java:2565](file:///Users/changjoonbaek/github_graph-rag-test-generator/graph-rag-test-generator/graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java#L2565)
  `// variantHttpCalls: SYNTHESIZED(responsevar) + CONTRACT(egressassert) 혼재 — provenance로 구분`
- 두 E2E 테스트는 `asset.httpCalls()`를 **`urlPath`만으로** 필터링한 뒤 전량 `SYNTHESIZED`를 요구한다.

**기술 스택:** Java, JUnit 5, AssertJ, Testcontainers(Postgres), Gradle

## 전역 제약
- 모든 파일 링크는 절대경로 + `file://` 스킴을 쓴다. **경로는 메인 체크아웃 루트
  (`/Users/changjoonbaek/github_graph-rag-test-generator/graph-rag-test-generator`) 기준으로 적었다** —
  worktree에서 작업 중이면 같은 저장소 상대경로를 **그 worktree 루트에 다시 붙여** 해석한다(이 문서
  자신은 작성 시점에 `.claude/worktrees/phase-a-followup/` 아래에만 존재했다).
- "TODO", "나중에 구현" 같은 자리표시자를 쓰지 않는다. 정확한 클래스·메서드·파라미터를 명시한다.
- 단언을 통과시키려고 약화(예: `anyMatch`로 완화, 테스트 비활성화)하지 않는다 — 아래 권고는
  **범위를 요구사항 문면에 맞추는 것**이지 강도를 낮추는 것이 아니다.

---

## 1. 증상 (실측)

`./gradlew :graph-rag-builder:test --tests Stage2EnumResponseFuzzingE2E --tests Stage2AStringLiteralFuzzingE2E`
실행 결과(2026-07-28, 이 저장소 worktree, 33분 16초):

```
Stage2AStringLiteralFuzzingE2E > REQ-004: String 변형 stub 캡처는 SYNTHESIZED FAILED
Stage2EnumResponseFuzzingE2E > REQ-004: 변형 stub 캡처 SYNTHESIZED FAILED
6 tests completed, 2 failed
BUILD FAILED
```

두 클래스의 나머지 4건(REQ-001 arm 도달, REQ-002 결정성)은 green이다. 실패는 `REQ-004` 1건씩이다.

실패 메시지가 나열한 `urlPath=/inventory/stock` 캡처는 **8건**이고, provenance 분포는 다음과 같다.

| id | pathId | responseProvenance |
| --- | --- | --- |
| `http-post-api-orders-s201-2-1` | `post-api-orders-s201-2` | `SYNTHESIZED` |
| `http-post-api-orders-s409e409-1-1` | `post-api-orders-s409e409-1` | `SYNTHESIZED` |
| `http-post-api-orders-responsevar-mode-BACKORDER` | `post-api-orders-responsevar` | `SYNTHESIZED` |
| `http-post-api-orders-responsevar-mode-EXPRESS-ONLY` | `post-api-orders-responsevar` | `SYNTHESIZED` |
| `http-post-api-orders-responsevar-region-EMBARGOED` | `post-api-orders-responsevar` | `SYNTHESIZED` |
| `http-post-api-orders-egressassert-mode-BACKORDER` | `post-api-orders-egressassert` | **`CONTRACT`** |
| `http-post-api-orders-egressassert-mode-EXPRESS-ONLY` | `post-api-orders-egressassert` | **`CONTRACT`** |
| `http-post-api-orders-egressassert-region-EMBARGOED` | `post-api-orders-egressassert` | **`CONTRACT`** |

분류하면 **`SYNTHESIZED` 5건 = 탐색 캡처 2건(`s201-2`, `s409e409-1`) + 변형 stub 경유 캡처 3건
(`-responsevar-`)** 이고, `CONTRACT` 3건은 전부 `-egressassert-`다. 즉 **REQ-004가 겨냥한 대상은
이미 전부 정상**이고, 필터에 함께 걸려든 `-egressassert-` 3건만 다르다.

**환경 조건:** 두 클래스는 `@Tag("integration")` + `@EnabledIfSystemProperty(named = "sut.jar")`다.
`sut.jar`는 [graph-rag-builder/build.gradle.kts:79](file:///Users/changjoonbaek/github_graph-rag-test-generator/graph-rag-test-generator/graph-rag-builder/build.gradle.kts#L79)가
항상 주입하므로, `-PexcludeTags=integration,docker` 없이 `:graph-rag-builder:test`를 돌리는 모든
실행에서 이 2건이 실패한다. 반대로 `-PexcludeTags=integration,docker`를 주는 경로에서는 skip되어
드러나지 않는다 — 그래서 지금까지 상시 실패가 방치됐다.

## 2. 근본 원인 (커밋 근거)

1. **테스트가 먼저 쓰였다.** `42cee13`(2026-06-24) `test(e2e): 단계2 enum 변형 E2E 스켈레톤(red)
   REQ-001,002,004`, `22479de`(2026-06-24) `test(e2e): 단계2-A String 리터럴 E2E + 매트릭스 갱신
   REQ-001,002,004,005`. 이 시점에는 `/inventory/stock` 캡처가 탐색 캡처와 변형 stub 캡처뿐이라
   `urlPath` 필터 = "변형 stub 경유 캡처"가 성립했다.
2. **그 뒤 `CONTRACT` provenance가 도입됐다.**
   - `772b577` `feat(egress): EgressStubComposer happy body String 리터럴·CONTRACT
     (REQ-F012-001/002/011)` — 계약 리터럴이 1건이라도 적용되면 `CONTRACT`
     ([EgressStubComposer.java:50](file:///Users/changjoonbaek/github_graph-rag-test-generator/graph-rag-test-generator/graph-rag-builder/src/main/java/io/graphrag/builder/run/EgressStubComposer.java#L50)).
   - `c50089f`(2026-06-25) `feat(egress): 변형 SUT status 관측 + egress-assertion 단언 path 환류
     (REQ-F012-006/007)` — `buildEgressAssertionPaths`가 **보존된 변형마다** `CONTRACT` 캡처를
     `variantHttpCalls`에 추가한다
     ([EndpointExplorationRunner.java:2629](file:///Users/changjoonbaek/github_graph-rag-test-generator/graph-rag-test-generator/graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java#L2629)).
     실측된 `CONTRACT` 3건은 전부 이 경로에서 나온다.
3. **필터 술어가 요구사항 문면보다 넓다.** REQ-004의 정의는 두 요구사항명세 모두 "**변형 stub 경유**
   캡처도 `responseProvenance=SYNTHESIZED`로 판정된다(전역 미등록이어도)"이고, 수용기준도 "Given
   변형 stub(헤더 매칭, 전역 Set 미등록)으로 통과한 외부 호출"이다
   ([enum REQ-004](file:///Users/changjoonbaek/github_graph-rag-test-generator/graph-rag-test-generator/docs/superpowers/requirements/2026-06-24-stage2-enum-response-fuzzing-requirements.md),
   [string-literal REQ-004](file:///Users/changjoonbaek/github_graph-rag-test-generator/graph-rag-test-generator/docs/superpowers/requirements/2026-06-24-stage2-string-literal-response-fuzzing-requirements.md)).
   `egressassert` 캡처는 **stub을 경유해 SUT가 실제로 호출한 기록이 아니라**, 생성기가 쓸 단언용으로
   합성해 환류시킨 계약 산출물이다(`consumedFields=[]`, `baggagePropagated=false`가 그 증거).
   즉 REQ-004의 대상이 아니다. **테스트의 `urlPath` 필터가 요구사항 범위를 초과했다.**

### 2.1 `CONTRACT`의 두 번째 발생원 — 검토했고 오늘의 데이터에는 해당 없음

리뷰에서 지적된 잔여 위험을 명시해 둔다. `CONTRACT`는 `-egressassert-` 경로 말고
**`EgressStubComposer.compose(...)`** 경로에서도 나올 수 있다 —
[EndpointExplorationRunner.java:2829](file:///Users/changjoonbaek/github_graph-rag-test-generator/graph-rag-test-generator/graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java#L2829)의
`captureHttpCalls`가 span으로 발견된 egress 호출에 대해 이를 호출하고, `stringLiteralsByDto`의
리터럴이 1건이라도 적용되면 `CONTRACT`가 된다. 그 캡처의 `pathId`는 `candidate.pathId()`
(예: `post-api-orders-s201-2`)라 **`-egressassert` 접미로 걸러지지 않는다.**

다만 §1의 실측 8건 중 탐색 캡처 2건은 그 경로가 아니라
[provenanceOf](file:///Users/changjoonbaek/github_graph-rag-test-generator/graph-rag-test-generator/graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java#L2857)에서
나오며, 그 메서드가 반환할 수 있는 값은 `SYNTHESIZED`/`CAPTURED` 둘뿐이라 **구조적으로 `CONTRACT`가
될 수 없다.** 따라서 오늘의 fixture에서 Task 1의 필터는 정확하다.

**의도된 동작(약화 아님):** 장래에 `compose()` 경로가 같은 `urlPath` 캡처를 `CONTRACT`로 태깅하게
되면, 그 캡처는 `-egressassert` 접미가 없어 `stubCaptures` 버킷에 들어가고 `allMatch(SYNTHESIZED)`가
**실패한다**. 이는 버그가 아니라 원하는 결과다 — 그 시점에는 REQ-004의 범위를 다시 판정해야 하며,
조용히 통과시키는 것보다 실패로 드러나는 편이 옳다. 이 문서는 그 실패를 "이미 알려진 재발"이 아니라
**새 판정 대상**으로 다루라고 지시한다.

## 3. 선택지

### 선택지 A — 단언 범위를 요구사항 문면에 맞춘다 (`-egressassert`를 제외하고 `CONTRACT`는 별도 양성 단언)

- **내용:** `urlPath` 필터에서 **`pathId`가 `-egressassert`로 끝나는 캡처를 제외**해, SYNTHESIZED
  단언의 대상을 "stub 경유 관측 캡처(탐색 + `-responsevar-`)"로 한정한다. 더불어 `egressassert`
  캡처가 `CONTRACT`임을 **양성으로 단언**해, 제외가 은근한 커버리지 축소가 되지 않게 한다.
  (탐색 캡처를 함께 남기는 이유: 그 2건도 stub 경유 관측이고 이미 `SYNTHESIZED`라, 굳이
  `-responsevar-`만 남기면 기존 커버리지를 줄이게 된다.)
- **장점:** REQ-004 문면과 정확히 일치한다. 실측상 대상 5건은 이미 전부 `SYNTHESIZED`이므로 요구된
  행위는 실제로 성립하고 있다 — 즉 결함은 제품이 아니라 단언 범위에 있다. `egressassert`(REQ-F012)의
  계약 산출물 태깅도 함께 고정되어 총 커버리지는 오히려 늘어난다.
- **단점:** 두 요구사항명세의 REQ-004 항목에 "egressassert 캡처는 대상 밖"이라는 범위 각주를
  덧붙여야 한다(문서 동기화 비용).

### 선택지 B — `egressassert`의 `CONTRACT` 산출을 되돌린다(다시 `SYNTHESIZED`로)

- **내용:** `buildEgressAssertionPaths`가 `Provenance.CONTRACT` 대신 `SYNTHESIZED`를 쓰게 한다.
- **장점:** 테스트를 한 줄도 고치지 않아도 green이 된다.
- **단점:** REQ-F012 계열이 명시적으로 도입한 구분(계약 기반 산출물 vs 관측 합성)을 파괴한다.
  `EgressStubComposer`가 리터럴 적용 여부로 `CONTRACT`/`SYNTHESIZED`를 가르는 설계
  (REQ-F012-010 silent-fallback 가시화)와도 모순되어, "조용한 폴백"을 다시 숨기게 된다. 오래된
  테스트를 지키려고 나중에 승인된 요구사항을 되돌리는 방향이라 우선순위가 뒤집힌다.

## 4. 권고

**선택지 A를 채택한다.** 근거: ① REQ-004의 대상은 문면상 "변형 stub 경유 캡처"이고 그 5건은 실측상
전부 `SYNTHESIZED`라 제품 행위에는 결함이 없다. ② `CONTRACT`는 나중에(REQ-F012) 승인된 요구사항의
산출물이므로, 그것을 되돌리는 B는 승인된 범위를 축소한다. ③ A는 `egressassert` 캡처의 provenance를
양성 단언으로 추가해 커버리지를 늘리는 방향이다.

**반론 검토(B를 완전히 배제하지 않는 조건):** 만약 `egressassert` 캡처를 `httpCalls()`에 싣는 것
자체가 설계 실수였다면(= 생성기 단언용 산출물이 "관측 캡처" 컬렉션을 오염시키는 문제) A는 증상만
가린다. 그 경우 올바른 해법은 B가 아니라 **제3안 — 별도 컬렉션 분리**다. 다만 이는 `GraphAsset`
스키마 변경이라 Task 1의 범위를 넘고, 아래 Task 3에서 관측 근거를 남긴 뒤 별도 REQ로 다룬다.

## 5. 상세 작업

### Task 1: 두 E2E의 REQ-004 단언을 요구사항 범위로 좁히고 CONTRACT를 양성 단언한다
**Files:**
- Modify: [Stage2EnumResponseFuzzingE2E.java](file:///Users/changjoonbaek/github_graph-rag-test-generator/graph-rag-test-generator/graph-rag-builder/src/test/java/io/graphrag/builder/cli/Stage2EnumResponseFuzzingE2E.java)
- Modify: [Stage2AStringLiteralFuzzingE2E.java](file:///Users/changjoonbaek/github_graph-rag-test-generator/graph-rag-test-generator/graph-rag-builder/src/test/java/io/graphrag/builder/cli/Stage2AStringLiteralFuzzingE2E.java)

**Interfaces:**
- Consumes: `GraphAsset.httpCalls()` — `CapturedHttpCall(id, pathId, …, responseProvenance)`.
- Produces: 대상 분리 단언 2개(변형/탐색 캡처 = `SYNTHESIZED`, `egressassert` 캡처 = `CONTRACT`).

- [ ] **Step 1: 실패를 먼저 재현한다(red 고정).**
  Command: `./gradlew :graph-rag-builder:test --tests Stage2EnumResponseFuzzingE2E --console=plain`
  Expected: `REQ-004: 변형 stub 캡처 SYNTHESIZED FAILED` — 실패 메시지에 `-egressassert-` 캡처
  3건이 `responseProvenance=CONTRACT`로 나열되는 것을 확인한다. (참고: 이 클래스 1개만 약 16분,
  두 클래스 합쳐 약 33분이 걸린다. 반복 루프에 전체 스위트를 쓰지 않는다.)

- [ ] **Step 2: `Stage2EnumResponseFuzzingE2E#variantStubCapturesAreSynthesized`를 아래로 교체한다.**
  ```java
  /**
   * 변형 stub 경유 캡처는 SYNTHESIZED로 태깅된다(REQ-004). egress-assertion 산출물
   * ({@code pathId}가 {@code -egressassert}로 끝나는 캡처)은 stub 경유 관측이 아니라 생성기
   * 단언용 계약 산출물이므로 REQ-004 대상이 아니며, CONTRACT임을 별도로 단언한다
   * (REQ-F012-006/007). 판별자는 이 테스트·주석·요구사항 각주 모두 {@code pathId} 접미로 통일한다.
   */
  @Test
  @DisplayName("REQ-004: 변형 stub 캡처 SYNTHESIZED")
  void variantStubCapturesAreSynthesized() throws Exception {
      GraphAsset asset = build(noExternalStubs());

      List<CapturedHttpCall> inventoryCalls = asset.httpCalls().stream()
              .filter(c -> c.urlPath().equals(INVENTORY_PATH))
              .toList();
      assertThat(inventoryCalls)
              .as("외부 inventory 호출이 변형 포함 다수 캡처됨").isNotEmpty();

      List<CapturedHttpCall> stubCaptures = inventoryCalls.stream()
              .filter(c -> !c.pathId().endsWith("-egressassert"))
              .toList();
      assertThat(stubCaptures)
              .as("REQ-004 대상(탐색·변형 stub 경유 캡처)이 존재해야 한다").isNotEmpty();
      assertThat(stubCaptures)
              .as("변형 stub 경유 캡처도 SYNTHESIZED")
              .allMatch(c -> c.responseProvenance() == CapturedHttpCall.Provenance.SYNTHESIZED);

      List<CapturedHttpCall> egressAssertCaptures = inventoryCalls.stream()
              .filter(c -> c.pathId().endsWith("-egressassert"))
              .toList();
      assertThat(egressAssertCaptures)
              .as("egress-assertion 산출물이 존재해야 한다(REQ-F012-006/007)").isNotEmpty();
      assertThat(egressAssertCaptures)
              .as("egress-assertion 산출물은 계약 기반이므로 CONTRACT여야 한다")
              .allMatch(c -> c.responseProvenance() == CapturedHttpCall.Provenance.CONTRACT);
  }
  ```

- [ ] **Step 3: `Stage2AStringLiteralFuzzingE2E#variantStubCapturesAreSynthesized`를 아래로
  교체한다** (Step 2와 기계적으로 동일하고, 첫 `.as(...)` 문구만 기존 String 판을 유지한다).
  ```java
  /**
   * String 변형 stub 경유 캡처도 SYNTHESIZED로 태깅된다(REQ-004). 전역 Set에 미등록
   * (trace-id 격리)이어도 provenance는 SYNTHESIZED여야 한다. egress-assertion 산출물
   * ({@code pathId}가 {@code -egressassert}로 끝나는 캡처)은 REQ-004 대상이 아니며,
   * CONTRACT임을 별도로 단언한다(REQ-F012-006/007).
   */
  @Test
  @DisplayName("REQ-004: String 변형 stub 캡처는 SYNTHESIZED")
  void variantStubCapturesAreSynthesized() throws Exception {
      GraphAsset asset = build(noExternalStubs());

      List<CapturedHttpCall> inventoryCalls = asset.httpCalls().stream()
              .filter(c -> c.urlPath().equals(INVENTORY_PATH))
              .toList();
      assertThat(inventoryCalls)
              .as("외부 inventory 호출이 변형 포함 다수 캡처됨").isNotEmpty();

      List<CapturedHttpCall> stubCaptures = inventoryCalls.stream()
              .filter(c -> !c.pathId().endsWith("-egressassert"))
              .toList();
      assertThat(stubCaptures)
              .as("REQ-004 대상(탐색·변형 stub 경유 캡처)이 존재해야 한다").isNotEmpty();
      assertThat(stubCaptures)
              .as("변형 stub 경유 캡처(String 포함)도 SYNTHESIZED")
              .allMatch(c -> c.responseProvenance() == CapturedHttpCall.Provenance.SYNTHESIZED);

      List<CapturedHttpCall> egressAssertCaptures = inventoryCalls.stream()
              .filter(c -> c.pathId().endsWith("-egressassert"))
              .toList();
      assertThat(egressAssertCaptures)
              .as("egress-assertion 산출물이 존재해야 한다(REQ-F012-006/007)").isNotEmpty();
      assertThat(egressAssertCaptures)
              .as("egress-assertion 산출물은 계약 기반이므로 CONTRACT여야 한다")
              .allMatch(c -> c.responseProvenance() == CapturedHttpCall.Provenance.CONTRACT);
  }
  ```

- [ ] **Step 4: green 확인.**
  Command: `./gradlew :graph-rag-builder:test --tests Stage2EnumResponseFuzzingE2E --tests Stage2AStringLiteralFuzzingE2E --console=plain`
  Expected: `6 tests completed, 0 failed` / `BUILD SUCCESSFUL`.

- [ ] **Step 5: 누수 검증 게이트.**
  Command: `docker ps -a --format '{{.Names}}'` + `docker compose ls`
  Expected: 이 스위트가 띄운 Testcontainers 컨테이너·compose project 잔존 0.

- [ ] **Step 6: 커밋.**
  Command: `git commit -am "test(stage2): scope REQ-004 provenance assertion to stub captures"`

### Task 2: 두 요구사항명세의 REQ-004에 범위 각주를 단다
**Files:**
- Modify: [2026-06-24-stage2-enum-response-fuzzing-requirements.md](file:///Users/changjoonbaek/github_graph-rag-test-generator/graph-rag-test-generator/docs/superpowers/requirements/2026-06-24-stage2-enum-response-fuzzing-requirements.md)
- Modify: [2026-06-24-stage2-string-literal-response-fuzzing-requirements.md](file:///Users/changjoonbaek/github_graph-rag-test-generator/graph-rag-test-generator/docs/superpowers/requirements/2026-06-24-stage2-string-literal-response-fuzzing-requirements.md)

두 문서 모두 기존 각주(footnote) 관례가 없다(`[^…]` 0건). 따라서 아래 블록쿼트 형식으로 통일한다.

- [ ] **Step 1: 각 문서의 `### REQ-004` 항목 마지막 줄(`- 검증 레벨: …`) 바로 아래에 다음
  블록쿼트를 삽입한다.**
  ```markdown
  > **범위 각주(2026-07-28):** 대상은 stub 경유 관측 캡처다 — 이후 REQ-F012-006/007이 도입한
  > egress-assertion 산출물(`pathId` 접미 `-egressassert`)은 계약 기반 산출물이라
  > `provenance=CONTRACT`이며 이 요구의 대상이 아니다. 실측 근거와 경위:
  > `docs/superpowers/followup/2026-07-28-stage2-provenance-assertion-drift.md`.
  ```

- [ ] **Step 2: 추적 매트릭스의 REQ-004 행을 정정한다.** 현재 두 매트릭스 모두 REQ-004를
  `🟢`/`🟢 pass`로 표기하고 있으나 실제로는 main에서 상시 실패였다(§1) — 거짓 green이다. Task 1의
  수정이 green을 확인한 **뒤에** 그 행의 요구사항 이름 셀을 `변형 provenance (범위 각주 참조)`로
  바꾸고, 상태 셀은 `🟢`를 유지하되 실측 근거로 이 followup 문서 경로를 같은 셀에 덧붙인다. 문서
  상단에 커버리지 요약 수치가 있으면 실제 통과 건수와 일치하는지 대조한다. 새 REQ-ID는 만들지
  않는다 — 요구의 범위를 문면으로 확정하는 것이지 요구를 추가·삭제하는 게 아니다.

- [ ] **Step 3: 커밋.**
  Command: `git commit -am "docs(req): scope stage2 REQ-004 to stub captures"`

### Task 3: `egressassert` 산출물을 `httpCalls()`에 싣는 설계의 적정성을 판단할 근거를 남긴다
**Files:**
- Modify: [2026-07-28-stage2-provenance-assertion-drift.md](file:///Users/changjoonbaek/github_graph-rag-test-generator/graph-rag-test-generator/docs/superpowers/followup/2026-07-28-stage2-provenance-assertion-drift.md) (이 문서)

- [ ] **Step 1: `asset.httpCalls()`를 `urlPath`로만 필터링하는 다른 소비자를 전수 조사한다.**
  Command(zsh에서 글롭이 먼저 확장되지 않도록 **반드시 인용**한다):
  `grep -rn "httpCalls()" --include='*.java' graph-rag-builder/src test-generator/src shared-model/src`
  **우선 점검 대상 = `urlPath`-only 필터 + `findFirst()`/`allMatch(...)` 조합**이다. 이미 알려진
  후보: [Stage1ExternalStubSynthesisE2E.java:159](file:///Users/changjoonbaek/github_graph-rag-test-generator/graph-rag-test-generator/graph-rag-builder/src/test/java/io/graphrag/builder/cli/Stage1ExternalStubSynthesisE2E.java#L159)의
  `inventoryCall(...)`(`urlPath` 필터 후 `findFirst()` — 같은 `urlPath`에 `egressassert` 산출물이
  섞이면 순서 의존으로 잘못된 캡처를 고를 수 있다). 결과 목록은 **§7**에 기록한다(§6은 완료 정의
  전용이므로 건드리지 않는다).

- [ ] **Step 2: 발견된 오인 지점이 0이면** "현행 단일 컬렉션 유지"를 결론으로 **§7**에 적고 종료한다.
  **1건 이상이면** `GraphAsset`에 계약 산출물 전용 컬렉션을 분리하는 신규 REQ를 만든다 — `GraphAsset`
  스키마 변경은 특정 스테이지에 종속되지 않는 cross-cutting 관심사이므로, 기존 stage2 명세에 끼워
  넣지 말고 **신규 파일 `docs/superpowers/requirements/YYYY-MM-DD-graph-asset-contract-artifact-split-requirements.md`**
  를 만들어 등록한다(사용자 워크플로의 요구사항명세 규칙). 이 문서에서 코드 변경은 하지 않는다.

- [ ] **Step 3: 커밋.**
  Command: `git commit -am "docs(followup): record egressassert capture-collection survey"`

## 6. 검증 방법 (완료 정의)

- `./gradlew :graph-rag-builder:test --tests Stage2EnumResponseFuzzingE2E --tests Stage2AStringLiteralFuzzingE2E --console=plain`
  → `6 tests completed, 0 failed`.
- `./gradlew test -PexcludeTags=integration,docker --console=plain` → 무회귀(BUILD SUCCESSFUL).
- 누수 검증: 스위트 종료 후 `docker ps -a`와 `docker compose ls`에 이 실행이 만든 컨테이너·
  compose project 잔존 0.
- 문서 동기화: 두 요구사항명세의 REQ-004 범위 각주가 Task 1의 테스트 코드와 서로를 가리킨다.
- **금지 사항 재확인:** `allMatch` → `anyMatch` 완화, `@Disabled`, 필터에서 `CONTRACT`를 조용히
  빼고 양성 단언을 생략하는 것 — 셋 다 완료로 인정하지 않는다.

## 7. 조사 기록 / 설계 판단 (Task 3 산출물 기록 위치)

> Task 3 실행 전에는 비어 있다. Task 3 Step 1의 전수 조사 결과 목록과 Step 2의 결론
> ("현행 단일 컬렉션 유지" 또는 "신규 REQ 등록 — 파일 경로")을 여기에 적는다. §6(완료 정의)과
> 섞지 않는다.

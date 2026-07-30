# 코드리뷰에서 기각·연기한 선재 결함 3건 (착수 대기)

> 2026-07-29 code-quality 리뷰(`pr-review-toolkit:code-reviewer`)가 지적했으나 **이번 PR
> 범위 밖**으로 판단해 고치지 않은 항목이다. 셋 다 이번 변경이 만든 결함이 아니라 **선재
> 결함**이고, 고치려면 별도 설계 판단이나 스키마 변경이 필요하다. 기각이 아니라 **연기**이며,
> 근거를 남겨 다음 세션이 다시 발견하는 비용을 없앤다.

## 1. `stubs.json`이 mapping 1개만 싣는다 — 외부 호출 2개 이상이면 나머지는 실제 egress

- **위치:** `BuilderCli`의 `stubsJsonContent` / `ensureExternalStubSlots`
- **사실:** `ensureExternalStubSlots`는 callSite마다 mapping을 만드는데 파일 포맷이 단일 객체라
  첫 mapping만 기록된다. 나머지 외부 호출은 스텁 없이 실제로 나가 5xx가 된다. warn 로그는 남아
  조용하지는 않다.
- **왜 연기하나:** 파일 포맷(단일 객체 → 배열) 변경이고, T1 검증기·`TrialRunner`·에이전트 스킬
  문서의 계약을 함께 바꿔야 한다. 마커 계약(REQ-009)의 diff 단위도 재정의된다.
- **착수 조건:** 외부 호출이 2개 이상인 엔드포인트를 실제로 대상으로 삼을 때. 그전까지는
  단일 호출 엔드포인트에서 정상 동작한다.

## 2. guard-input 슬롯이 DTO 타입 피연산자에도 스칼라 마커를 놓는다

- **위치:** `TripleSynthesizer`의 guard-input 스윕 / `isContainerType`
- **사실:** `isContainerType`은 List/Map 계열만 거른다. `if (req.getAddress() == null) throw ...`
  같은 null 가드는 jsonPath=`address`, javaType=`Address`인 INPUT 피연산자를 만들고, 슬롯이
  `body.address = "__AGENT_FILL__{...}"`(문자열)로 배치돼 SUT Jackson이 400을 낸다.
- **현재 마스킹되는 이유:** unguarded 리프(`address.city` 등)가 먼저 처리돼 `bodyHasPath`로
  걸러진다. `@RequestBody`가 아닌 파라미터나 getter 없는 DTO처럼 unguarded 리프가 없는 형상에서
  그대로 발현한다.
- **왜 연기했나:** "스칼라로 판정 가능한 javaType" 목록을 확정하는 것이 설계 판단이고, 잘못
  좁히면 결정값이 갭 마커로 후퇴한다고 봤다.

> **[해소됨 2026-07-29]** javaType 목록을 만드는 대신 **리포트 데이터로 판정**했다 —
> `path + "."`로 시작하는 리프(unguarded 또는 다른 가드 피연산자)가 하나라도 있으면 그 경로는
> 객체이므로 스칼라 슬롯을 만들지 않고 사유를 notes에 남긴다(`hasChildLeaf`). 인덱서가 DTO를
> 리프 dot-path로 전개하므로 이 판정은 타입 목록 없이 성립하고, enum을 DTO로 오분류할 위험도
> 없다(같은 커밋에서 enum을 리프로 고정했다). 순서 의존(3번)도 함께 사라진다.
>
> 남은 경계: 어떤 DTO의 리프가 **전부** 가드에 참조돼 unguarded가 비는 경우에는 판정이
> unguarded만으로는 성립하지 않는다. 그래서 가드 피연산자의 jsonPath와 **DERIVED의
> `derivedFrom` 경로까지** 함께 본다.
>
> **[정정 2026-07-30]** 이 문단의 이전 판은 "리프 슬롯이 뒤이어 배치되면 `bodyHasPath`가
> 걸러낸다"고 적었는데 **틀렸다.** 실제 슬롯 처리 순서는 DERIVED → guard-input이라 자식(리프)이
> 부모보다 **먼저** 배치되고, 뒤이은 부모 스칼라가 앞서 만든 자식 객체를 덮어쓴다. 코드리뷰가
> 이 괴리를 지적해 `hasChildLeaf`가 `derivedFrom`도 검사하도록 고쳤다
> (회귀 테스트: `req005_derivedOnlyChildLeafAlsoMarksParentAsObject`).

## 3. `descendWithCollections`가 기존 스칼라 자식을 조용히 `{}`로 교체할 수 있다

- **위치:** `TripleSynthesizer`의 `childContainer`
- **사실:** 같은 접두 경로에 스칼라가 먼저 놓인 뒤 중첩 경로가 처리되면 그 스칼라가 객체로
  덮어써져 결정값이 소실된다. 현재 슬롯 처리 순서(unguarded → guard-input) 덕에 발현하지 않는다.
- **왜 연기했나:** 2번과 같은 코드 경로이고, 순서 의존을 없애려면 슬롯 병합 규칙 자체를
  재설계해야 한다고 봤다.

> **[해소됨 2026-07-29]** 2번의 `hasChildLeaf` 판정이 순서와 무관하게 객체 경로를 걸러내므로,
> "스칼라가 먼저 놓인 뒤 객체로 덮어써지는" 조합 자체가 생기지 않는다. 전용 테스트
> `req005_objectValuedGuardOperandDoesNotGetScalarMarker`가 **리프를 나중에 처리하는 순서**로
> 이 경로를 고정한다(수정 전에는 이 테스트가 실패한다).

---

## 함께 기록 — 이번 PR에서 **기각**한 지적 1건

**`pathParamsOf`의 javaType이 항상 `java.lang.String`이라 경로 변수 센티널이 `"0"`이 아니라
`"missing"`이 된다(Minor).** 기각 근거: ① 센티널은 후보 body에 경로 변수 키가 **없을 때만**
쓰이는데 그건 애초에 잘못된 후보이고 어느 값이든 4xx다. ② `javaType`을 null로 두면
`pathSentinel`/`Generator`의 `switch(param.javaType())`가 NPE를 낸다. ③ 공유 센티널의 기본값을
바꾸면 기존 골든이 흔들린다. 판단 근거는 `BuilderCli.pathParamsOf` 주석에 남겼다.

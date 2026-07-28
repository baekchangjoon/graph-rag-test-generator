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
- **왜 연기하나:** "스칼라로 판정 가능한 javaType" 목록(원시형·String·시간형·BigDecimal·enum)을
  확정하는 것이 설계 판단이고, 잘못 좁히면 결정값이 갭 마커로 후퇴한다. 회귀 위험이 있어
  전용 픽스처와 함께 다뤄야 한다.

## 3. `descendWithCollections`가 기존 스칼라 자식을 조용히 `{}`로 교체할 수 있다

- **위치:** `TripleSynthesizer`의 `childContainer`
- **사실:** 같은 접두 경로에 스칼라가 먼저 놓인 뒤 중첩 경로가 처리되면 그 스칼라가 객체로
  덮어써져 결정값이 소실된다. 현재 슬롯 처리 순서(unguarded → guard-input) 덕에 발현하지 않는다.
- **왜 연기하나:** 2번과 같은 코드 경로이고, 순서 의존을 없애려면 슬롯 병합 규칙 자체를
  재설계해야 한다. 2번과 함께 다루는 것이 맞다.

---

## 함께 기록 — 이번 PR에서 **기각**한 지적 1건

**`pathParamsOf`의 javaType이 항상 `java.lang.String`이라 경로 변수 센티널이 `"0"`이 아니라
`"missing"`이 된다(Minor).** 기각 근거: ① 센티널은 후보 body에 경로 변수 키가 **없을 때만**
쓰이는데 그건 애초에 잘못된 후보이고 어느 값이든 4xx다. ② `javaType`을 null로 두면
`pathSentinel`/`Generator`의 `switch(param.javaType())`가 NPE를 낸다. ③ 공유 센티널의 기본값을
바꾸면 기존 골든이 흔들린다. 판단 근거는 `BuilderCli.pathParamsOf` 주석에 남겼다.

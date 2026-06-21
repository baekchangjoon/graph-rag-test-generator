# P5 — 소규모 정합성 개선 Implementation Plan

> REQUIRED SUB-SKILL: superpowers:subagent-driven-development. 런타임 검증(P3 교훈), OS-이식성(gateway-CI 교훈).

**Goal:** 두 소규모 정합성 결함 — (REQ-017) 메서드-레벨 `@RequestMapping(method=…)` 미인덱싱, (REQ-018) 빈 path 변수(double-slash) 캡처-재현 인코딩 불일치 — 를 고친다.

> 출처: RFC §5 P5, 요구사항 REQ-017/018. (G5a=REQ-020·G5b=REQ-019는 🔵 연기.)

## REQ-017 — 메서드-레벨 @RequestMapping(method=…) 인덱싱
**현황:** `EndpointIndexer`의 메서드 루프는 `MAPPING_TO_METHOD`(5개 verb 어노테이션)만 본다. verb 어노테이션 없이 `@RequestMapping(method=RequestMethod.POST)`만 쓰는 핸들러는 `httpMethod==null`→`continue`→미인덱싱.
**Task 1:** verb-어노테이션 루프 후 `httpMethod==null`이면, 메서드의 `@RequestMapping`(상수 `REQUEST_MAPPING`)을 찾아 `method` 속성(`RequestMethod` enum, 예: `RequestMethod.POST`)을 읽어 HTTP 메서드로 매핑한다. `method`가 없으면(전 verb 매칭) skip 유지. `method`가 배열 다중값이면 첫 값(또는 각각) — 단일 우선. path는 그 `@RequestMapping`의 value/path로. `annotationStringValue`/`getValues()` 헬퍼 재사용. RequestMethod enum 읽기는 `CtFieldRead`/enum-access로 best-effort.
- 단위 TDD(EndpointIndexerTest 패턴, @TempDir 소스): `@RequestMapping(value="/x", method=RequestMethod.POST)`만 쓴 핸들러 → POST `/x` 엔드포인트 발견. 다중 method=`{GET,POST}`도 처리(GET 우선 or 둘 다 — 결정 후 명시). 기존 verb-어노테이션 인덱싱 회귀 0.

## REQ-018 — 빈 path 변수(double-slash) 캡처-재현 인코딩 일치
**현황:** `EndpointExplorationRunner.buildPathAndQuery`(L1038)와 `Generator.resolveLiteralPath`(L603) 모두 path var를 치환한다. path var 값이 **빈 문자열("")**이면 `path.replace("{x}","")` → `/…//content`(double-slash). 탐색(java.net.http)과 생성 테스트(RestAssured)가 double-slash를 다르게 전송 → status 갈림(diary `s404_2`: 캡처 404 vs 재현 400).
**Task 2(권장 fix — 양쪽 센티널 통일):** buildPathAndQuery와 resolveLiteralPath 둘 다에서, path var 값이 **blank/empty면 누락과 동일하게 `pathSentinel`로 치환**한다(빈 문자열 → 센티널). double-slash가 사라져 java.net.http·RestAssured가 동일 경로를 전송 → 캡처-재현 status 일관. (대안: double-slash 유지 + RestAssured `urlEncodingEnabled(false)` — 인코딩 통제가 까다로워 비채택.)
- 단위 TDD: `buildPathAndQuery`/`resolveLiteralPath`에 path var=""인 input → double-slash 없는 동일 경로 산출(둘이 동일 문자열). 비-빈 값·누락 케이스 회귀 0.
- **확인 필요(구현 시):** ① `pathSentinel`이 두 메서드에서 호출 가능한지(시그니처) ② 빈 값이 실제로 어디서 들어오는지(변이/입력) — 둘 다 blank-guard면 충분.

## Task 3 — 회귀 + 매트릭스
- [ ] `:graph-rag-builder:test :test-generator:test` green + `e2e/run-e2e.sh`(order-service) green(인코딩 변경이 기존 경로 회귀 0). 매트릭스 REQ-017/018 🟢, Coverage 갱신.

## Self-Review
- Spec: REQ-017(T1)·REQ-018(T2)·검증(T3). 누락 없음.
- 설계: REQ-018은 빈-var 센티널 통일(인코딩 통제 회피). REQ-017은 verb-loop 폴백.
- 런타임 검증(e2e) + 회귀 0 확인.

## Execution
Subagent-driven, task별 spec+quality 리뷰. CI watch → rebase 머지.

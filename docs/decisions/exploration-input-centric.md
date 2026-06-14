# 의사결정: 탐색 엔진 입력 일반화 — EndpointTarget baseInput+mutableFields

날짜: 2026-06-14 / 단계: Phase 7 (C#2, E2)

## 배경

기존 탐색 엔진(`HeuristicExplorer`, `CoverageGuidedFuzzer`)은 POST body를 `JsonNode`로
받아 변이했다. GET/PUT/DELETE 지원 시 path param·query param을 별도로 처리하거나
엔진을 복제해야 한다는 문제가 생겼다.

## 결정

"탐색 입력"을 평탄한 `JsonNode {paramName: value}` 하나로 통일하고, `EndpointTarget`에
`baseInput`(전체 파라미터 초기값) + `mutableFields`(변이 대상 필드 목록)를 둔다.
`httpInvoker`가 실행 직전에 `Endpoint.params`의 `ParamKind`(BODY/PATH/QUERY)를 보고
path 치환·query string·body로 분배한다.

## 효과

- `InputMutator`·`HeuristicExplorer`·`CoverageGuidedFuzzer`·dedup·budget 로직
  **변경 없이** GET/PUT/DELETE/PATCH에 재사용. 엔진 코드는 ParamKind를 모른다.
- POST는 `{bodyField: value}` 그대로 → byte-identical (하위호환).
- GET의 path param(`{id}`)과 query param(`?userId=`)이 평탄 맵으로 동일하게 변이됨.
  FK 값 고정·null 삽입·경계값 등 기존 변이 전략이 그대로 적용.

## 트레이드오프

- path 치환 시 `{paramName}` 토큰을 평탄 맵에서 찾아야 하므로 파라미터 이름 충돌
  (body 필드명과 path 변수명이 같을 때)에 주의. 현재 케이스 없음; 충돌 시 PATH가 우선.
- `mutableFields`를 명시적으로 관리해야 하나 `ReadInputSynthesizer`가 결정해
  전달하므로 탐색 엔진이 추론할 필요 없음.

## 대안

path param·query·body를 별도 필드로 `EndpointTarget`에 추가하는 방법도 검토했으나,
엔진 코드 3곳(heuristic/fuzzer/dedup)을 모두 수정해야 하고 하위호환이 더 복잡해진다.
평탄 단일 맵이 엔진 재사용 측면에서 명백히 단순.

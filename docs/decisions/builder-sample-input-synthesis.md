# 의사결정: Phase 0 sample input 합성 규칙

날짜: 2026-06-10 / 단계: 0.6

## 배경

Phase 0은 JDart/fuzzer 없이 endpoint당 happy-path 1개를 결정적으로 실행해야 한다
(분기 탐색은 Phase 1). 그러려면 "성공하는" 요청 입력과 FK 사전 데이터가 필요하다.

## 결정 (결정적 규칙, 시간/Random 금지)

1. body 필드 `<x>Id`(camelCase)를 snake_case로 바꿔 스키마의 FK 컬럼과 매칭되면:
   - 값은 `probe-<필드명>`
   - FK 부모 테이블에 seed row INSERT (참조 컬럼=probe 값, 그 외 NOT NULL 컬럼은
     타입별 기본값: 문자열 `probe`, 숫자 1, bool true)
2. 그 외 스칼라: String → `sample-<필드명>`, 정수 → 1, 실수 → 1.0, bool → true
3. binding origin 판정: 캡처된 SQL 바인딩 값이 body 값과 일치 → API_PARAM,
   아니면 → LITERAL

## 한계 (정직하게)

- `@NotNull`/enum/range 제약은 미반영 — 400이 나오면 path의 expectedStatus가
  400으로 기록될 뿐 실패는 아니다. Phase 1의 분기 탐색이 이를 다중 path로 확장.
- COMPUTED origin(시퀀스/시간 유래)은 Phase 0에서 미판정(LITERAL로 폴백).
  Phase 1에서 응답/시퀀스 대조로 추가.
- FK 휴리스틱은 `<x>Id` 네이밍 관례 의존. 비관례 매핑은 Phase 1+에서
  dataflow 분석으로 보강.

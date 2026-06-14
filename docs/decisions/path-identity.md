# 의사결정: path 식별 = 응답 status + 분기 집합

> **⚠️ SUPERSEDED (2026-06-14)** — path 식별 키가 `status + 요청별 probe 지문`(arm-accurate,
> `coverage/CoverageFingerprint`)으로 바뀜. 분기 집합은 지문이 없을 때 폴백으로만 사용. true/false
> arm을 구분해 발견 입력이 distinct path로 보존됨. 현행: [docs/24](../24-exploration-backends-and-input-oracle.md) "arm-aware path 보존". 이 문서는 당시 결정 기록으로 보존.

날짜: 2026-06-10 / 단계: 1.1

## 문제

docs/03은 ExploredPath를 "분기 시퀀스"로 정의하지만, JaCoCo 분석 범위를
SUT 자체 클래스(BOOT-INF/classes)로 한정하면 **라이브러리 내부 분기가 보이지
않는다**. 실측 사례: `Optional.orElseThrow`로 갈리는 201/404가 컨트롤러 분기
집합으로는 동일해 하나의 path로 합쳐졌다.

## 결정

path 동일성 키 = **HTTP 응답 status + (정렬된) SUT 분기 집합**.

- 분기 집합이 같아도 status가 다르면 관측 가능한 별개 동작 → 별개 path
- 같은 status에서 분기 집합이 다르면 별개 path (예: 검증 `||` 체인의
  operand별 400 변형들)
- path id는 `<endpointId>-s<status>-<발견순서>` — 발견 순서가 결정적이므로
  id도 결정적

## 대안과 기각 사유

- 라이브러리 포함 전체 분기 분석: 분기 수 폭발 + novelty 신호가 노이즈화
- 응답 body까지 키에 포함: 시퀀스 값 등 비결정 필드 때문에 path 중복 폭발

## 영향

- 도구 2는 status별 테스트를 합성할 수 있다 (404 테스트가 201과 분리됨)
- 분기 방향(true/false) 식별이 필요해지면 ASM 수준 분석으로 강화
  (explorer-engines.md의 단조 키 한계와 동일 맥락)

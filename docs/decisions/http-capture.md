# 의사결정: 외부 HTTP 사실의 획득 방식 (Phase 2)

날짜: 2026-06-10 / 단계: 2.1~2.5

## 결정 요약

| 항목 | 원안 (docs/09) | 결정 |
|---|---|---|
| 2.1 호출 추적 | 정적 dataflow로 URL/body | **실행 캡처 우선** (임베디드 WireMock journal). 정적 분석은 literal 후보·DTO 필드 추출에 한정 |
| 2.2 recorder | WireMock recorder | journal 기반 요청 단위 delta 캡처 + `--external-stubs`로 minimal valid 응답 제공 (운영자 입력 — docs/03 전제) |
| 2.3 OTEL | javaagent 부착 | 분석·테스트 양쪽 env 부착 + **전파를 실측해 사실로 기록** (`baggagePropagated`) |
| 2.5 사용 필드 | 소비 코드가 읽은 필드 | **DTO 바인딩 필드 ∩ 응답 필드** 근사 + 스텁 투영 |

## 근거

- 정적 dataflow로 URL/body를 복원하는 것은 문자열 조립·설정 주입 때문에 취약.
  실행 캡처는 "실제 나간 요청"을 그대로 준다 (분석 환경의 외부 의존이 전부
  임베디드 WireMock으로 redirect되므로 누락 없음).
- propagation을 가정하지 않고 실측하는 이유: docs/06의 parallel_safety_report가
  요구하는 "SUT propagation missing" 판정은 실측 없이는 불가능.

## 픽스처 규칙 교정 (Phase 2에서 발견된 결함)

"4xx path는 픽스처 생략"(Phase 1 규칙)은 **409에서 오답** — 409는 행 존재가
전제다. 교정된 규칙: SELECT 캡처에 대해 **조회 성공의 증거**가 있을 때 seed:

1. 해당 SELECT 이후 다른 SQL이 이어졌다, 또는
2. path에 외부 HTTP 호출이 있다, 또는
3. (마지막 문장이 SELECT인 경우) 응답이 2xx다

→ 404(증거 없음 + 4xx)는 seed 안 함, 409(HTTP 호출 증거)는 seed.

한계: 조회 성공 후 무신호로 4xx가 나는 in-memory 검증 path는 오판 가능 —
still_missing/실패 테스트로 드러나며, Phase 3+에서 행 수 캡처(MyBatis `Total:`)로
보강 후보.

## 한계

- consumedFields는 상한 근사 (DTO 전체 필드 ⊇ 실제 읽은 필드)
- 다단 외부 호출 체인의 순서-의존 stateful 응답은 미지원 (Phase 4의 socket
  세션과 함께 재검토)

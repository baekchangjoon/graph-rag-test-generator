# 의사결정: STOMP 캡처·합성 방식 (Phase 3)

날짜: 2026-06-10 / 단계: 3.1~3.3

## 결정 요약

| 항목 | 결정 |
|---|---|
| STOMP 클라이언트 | **자체 최소 구현** (JDK WebSocket + 자체 프레임 코덱, 의존성 0). CONNECT/SUBSCRIBE/SEND/MESSAGE만 |
| 클라이언트 공유 | testlib에 두고 **builder가 testlib에 의존** — 캡처 경로와 생성 테스트 경로가 같은 구현을 사용해 충실도 확보 |
| WS 설정 추출 | `addEndpoint`/`setApplicationDestinationPrefixes` 리터럴 정적 추출 (best-effort; 동적 설정 미지원) |
| 교환 캡처 | 결정적 2-변형 (happy + `<x>Id` missing-ref). **WS 분기 탐색은 보류** |
| 병렬 격리 | 응답이 치환 필드의 캡처 값을 echo → 해당 변수로 `awaitMessageContaining` 마커 매칭. echo 없으면 `@Execution(SAME_THREAD)` + `serial_required(WS_NO_CORRELATION)` |
| 픽스처 재사용 | WsExchange → pseudo-ExploredPath 변환으로 FixtureComposer 그대로 사용 |

## 근거

- spring-messaging 클라이언트 의존은 도구가 SUT의 스택 버전에 끌려가는 결합
  (socket-mock 자체 제작과 같은 판단). STOMP 1.2 서브셋은 ~150줄.
- WS "path" 식별은 HTTP status 같은 표준 신호가 없어 Phase 1 탐색기의 전제와
  맞지 않음. 응답 메시지 shape 기반 path 식별은 실수요(분기 있는 WS 핸들러)
  확인 후 설계.

## Phase 3에서 잡은 결함 (조인 별칭 귀속 오류)

WS 핸들러의 파생 쿼리가 조인을 만들면서
(`from orders o1_0 left join users u1_0 ... where u1_0.id=?`)
바인딩 컬럼의 별칭이 벗겨져 **주 테이블(orders)로 잘못 귀속** →
BIGSERIAL PK에 문자열을 넣는 픽스처가 합성돼 E2E 16개 중 2개 실패.

교정: `ParsedSql`이 FROM/JOIN 절에서 별칭→테이블 맵을 해석해
`SqlBinding.table`에 기록, FixtureComposer는 바인딩 테이블을 우선 사용.
(HTTP 단독 쿼리에서는 드러나지 않던 결함 — WS의 조인 쿼리가 발견 계기)

## 한계

- @SendToUser/user destination, 인증 핸드셰이크 미지원 (auth_mode=REAL 샘플과 함께)
- 다단계 메시지 시나리오(구독 후 N개 수신) 미지원 — Phase 4 stateful 세션과 함께 재검토

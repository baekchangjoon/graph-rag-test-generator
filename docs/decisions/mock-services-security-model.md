# 의사결정: mock/dashboard 서비스의 보안 모델

날짜: 2026-06-10 / 단계: 0.4, 0.5

## 배경

자동 보안 리뷰가 dashboard `/events`와 socket-mock `/__admin/**`의
무인증을 HIGH로 지적했다.

## 결정

이 서비스들은 **임시(ephemeral) docker-compose 테스트 네트워크 내부 전용**
디버깅/테스트 인프라다 (docs/06, docs/08). WireMock의 `__admin` API와 같은
신뢰 모델을 따른다. 다음과 같이 절충한다.

### 반영한 것

- dashboard: `testId` 형식 검증(`[A-Za-z0-9._-]{1,128}` — 로그 인젝션 방지 +
  키 카디널리티 제한), in-memory run 상한(10,000 + 종료 상태 evict),
  옵션 Bearer 토큰(`DASHBOARD_TOKEN`, 상수시간 비교)
- socket-mock: listenPort 범위 검증(1024–65535), 리스너 상한(64),
  bind 실패의 HTTP 오류 매핑(400/429/409)

### 의도적으로 반영하지 않은 것

- mock/admin API의 **필수(fail-closed) 인증**: 생성된 테스트와 도구가
  compose 내부망에서 자유롭게 호출하는 설계(docs/07)와 충돌하고,
  동일 역할의 표준 도구(WireMock admin)도 무인증이다.
  compose 밖으로 포트를 노출하지 않는 운용을 전제로 한다.
- 노출이 필요한 환경에서는 dashboard는 `DASHBOARD_TOKEN`으로 보호 가능.
  socket-mock admin 인증은 그런 요구가 실제로 생길 때 추가한다.

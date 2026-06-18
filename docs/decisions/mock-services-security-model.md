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

## 샘플 SUT(order-service)의 의도적 무인증 (2026-06-10 추가)

`samples/order-service`는 운영 서비스가 아니라 **도구 검증용 픽스처**다.
자동 보안 리뷰가 지적한 WS IDOR / broadcast 토픽 / origin 미제한은 모두
의도된 시나리오다:

- 무인증: docs/04의 `auth_mode=DISABLED` 경로를 검증하는 전제
- `/topic` broadcast + body의 userId: broadcast 환경에서 마커(testId-unique 값)
  기반 메시지 상관관계로 병렬 격리하는 것이 Phase 3의 검증 대상 그 자체
- 임시(ephemeral) 분석/compose 환경에서만 구동되며 배포되지 않는다

인증 있는 SUT 시나리오(`auth_mode=REAL`, `@SendToUser`)는 testlib AuthClient
어댑터가 실구현되는 시점에 별도 샘플로 추가한다.

## attach 모드 호스트 바인딩 서비스의 per-run 토큰 (2026-06-18 추가)

위 "compose 내부망 전용, 포트 미노출" 전제의 **예외**가 attach 모드에 있다.
attach 모드는 SUT를 컨테이너로, 빌더의 캡처 인프라(OTLP 리시버 · capture WireMock)를
**호스트에서** 돌린다. 컨테이너가 `host.docker.internal` 로 도달하려면 이 두 서버가
호스트의 모든 인터페이스(`0.0.0.0`)에 bind해야 하므로, 분석 동안만이라도 같은 호스트의
다른 (비신뢰) 프로세스에 노출된다.

이 노출을 막기 위해 **실행마다 1회용 256-bit 토큰**으로 보호한다(fail-closed):

- **OTLP 리시버**: 토큰을 `OTEL_EXPORTER_OTLP_HEADERS` 로 컨테이너에 전달하고, 리시버는
  상수시간 비교(`MessageDigest.isEqual`)로 일치하는 요청만 받는다(불일치 거부).
- **capture WireMock**: SUT는 outbound 헤더를 제어하지 못하므로(SUT 코드가 외부 호출의
  헤더를 우리 마음대로 붙이지 않는다) 헤더 토큰 대신 토큰을 **URL 경로 prefix**(`/<token>`)로
  쓴다. 빌더가 SUT에 주입하는 base URL(`{{wiremock}}` 치환값)에 prefix를 심고, WireMock의
  RequestFilter가 prefix 없는 요청은 401로 막은 뒤 prefix를 벗겨 스텁을 매칭한다.

토큰은 **캡처된 데이터(`CapturedHttpCall.urlPath`)와 빌더 로그에 새지 않는다** — drain 시
prefix를 제거하고, 로그에는 토큰 없는 loopback URL/포트만 남긴다. 그래야 토큰 없는 환경에서
실행되는 생성 테스트의 mock 경로가 캡처 경로와 일치한다. 토큰은 프로세스 메모리에만 존재하고
실행이 끝나면 사라진다(영속화하지 않음).

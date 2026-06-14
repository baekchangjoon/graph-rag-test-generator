# 7-auth — JWT 인증 주입

날짜: 2026-06-14

## 진행 내용

petclinic `ApiBlackBoxTestSupport` 패턴을 이식.

**빌더 측 (탐색 시):**
- `AuthConfig`: login path/user/pass/token 필드명 파라미터화
- `AuthTokenProvider`: 탐색 시작 전 1회 login 요청 → JWT 캐싱
- `httpInvoker`: `authRequired=true`인 endpoint 호출 시 `Authorization: Bearer <token>` 헤더 자동 주입
- `BuilderCli`: `--auth-login-path / --auth-user / --auth-pass / --auth-token-field` 신규 플래그

**testlib 측 (생성 테스트 실행 시):**
- `RealAuthAdapter` + `JwtAuthClient`: SPI 어댑터. 생성 테스트 실행 환경에서 login → Bearer 토큰 취득
- `RestAssuredHelper.authenticated()`: `authRequired=true` path에 Bearer 헤더 삽입

**generator:**
- `authRequired=true` path → `scope.rest().authenticated()` 코드 합성

**샘플 SUT (F1):**
- `POST /api/auth/login` 엔드포인트 + jjwt 기반 JWT 발급
- Spring Security: login path 공개, 나머지 Bearer 필수

## 검수

- `AuthTokenProvider` 단위 GREEN
- `RealAuthAdapter`/`JwtAuthClient` 단위 GREEN
- e2e: auth POST 10건 authenticated 경로로 통과 (아래 7-e2e.md 참조)

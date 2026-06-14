# 의사결정: authRequired 휴리스틱 판정 + login-once 캐싱 패턴

날짜: 2026-06-14 / 단계: Phase 7 (C1, D1–D5)

## 배경

기존 `EndpointIndexer`는 `authRequired=false`를 하드코딩. 실제 SUT는 대부분의 경로가
JWT Bearer를 요구하며, 인증 없이 탐색하면 401로만 끝난다.

## authRequired 판정 방식 결정

### 검토한 접근

| 접근 | 평가 |
|---|---|
| **A (채택): 비-login 경로 = 보호 휴리스틱** | 구현 단순. `AuthConfig` 제공 시 loginPath와 명시 public 경로 외 전부 true |
| B: Spring Security `SecurityFilterChain` 정적 파싱 | SB2/SB3 코드 스타일 다양, XML/람다 혼재, DSL 버전 차이. 분석 복잡도 대비 실용성 낮음 |
| C: actuator `/actuator/mappings` + 401 프로브 | 실행 중 판정으로 정확하나 탐색 전에 모든 경로를 401 프로브해야 해 비용 높음 |

### 근거

실제 SUT에서 "인증 불필요한 비-login 경로"는 극히 드물다. 과탐지(false positive, 실제
공개 경로를 보호로 오인)는 탐색에서 Bearer를 불필요하게 붙이는 것에 그쳐 기능 오류가 없다.
미탐지(false negative, 실제 보호 경로를 공개로 오인)는 탐색이 401을 받아 path가 누락되므로
더 해롭다. 따라서 보수적(과탐지) 휴리스틱이 적합.

Spring Security 정적 파싱은 `docs/decisions/builder-analysis-environment.md`의 "Phase 0
범위 밖 — actuator 기반 조회 우선 검토" 원칙과 일치. 필요 시 actuator `/actuator/mappings`
+ 401 프로브 방식으로 확장.

## login-once 캐싱 패턴 (petclinic 이식)

petclinic의 `ApiBlackBoxTestSupport`에서 이식.

- **빌더**: `AuthTokenProvider`가 탐색 시작 전 1회 login → JWT 캐싱. 이후 각 endpoint
  `invoke()` 시 `authRequired=true`면 `Authorization: Bearer <token>` 헤더 주입.
- **testlib**: `RealAuthAdapter`가 테스트 실행 환경에서 동일 패턴 — login 1회, 클래스 수명
  동안 캐싱 (`@BeforeAll` 수준).
- **generator**: `authRequired=true` path → `scope.rest().authenticated()` 코드 합성.

## 한계

- 휴리스틱이므로 `loginPath`에 포함되지 않은 공개 경로(예: `/api/health`)는
  오탐(보호로 분류). 탐색 실패는 아니나 불필요한 Bearer 첨부. 공개 경로가 많으면
  `--auth-public-paths` 명시 리스트 옵션을 추후 추가 가능.
- 토큰 만료: 현재 탐색 내내 단일 토큰 사용. 장시간 탐색에서 만료 시 재발급 로직 미구현.

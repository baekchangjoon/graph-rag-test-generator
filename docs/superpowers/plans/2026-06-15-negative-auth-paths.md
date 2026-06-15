# 부정 인증 경로 (invalid-token 변종으로 auth 필터 거부 arm 열기)

작성일: 2026-06-15 · 브랜치: `worktree-feat-negative-auth` (main 기준) · **3-모델 리뷰 반영(§8)**
근거: 탐색이 **valid token(happy-auth)만** 보내서 JWT 필터/유틸의 거부 arm이 미탐색. (외부 petclinic 클론 진단에서
`JwtAuthenticationFilter`/`JwtUtil.validateToken` 거부 arm 미커버 확인 — petclinic은 e2e real-clone, in-tree 아님.)
in-repo 벤치마크 = order-service. #1(Kafka 양-arm)·#4(state-guard 양-arm)의 HTTP 인증 버전.

## 1. 문제 (in-repo 벤치마크 = order-service, 검증됨)

`JwtAuthFilter.doFilterInternal`(OncePerRequestFilter, 전 요청 실행):
- `if (header != null && header.startsWith("Bearer "))` — **header==null arm은 permitAll 로그인**(`/api/auth/login`,
  Authorization 헤더 없이 발행 → 외부 if false-arm)이 **이미 커버**(필터가 전 요청에 도므로).
- `if (jwtUtil.validate(token))` **false arm** + `JwtUtil.validate` try/catch의 **catch arm**(return false) — **미커버**:
  어떤 요청도 무효 Bearer 토큰을 안 보낸다(httpInvoker는 auth-required면 항상 valid 토큰 주입, line 564-567).
SecurityConfig `.anyRequest().authenticated()` → 무효 토큰은 4xx(401/403). `get-api-orders`는 auth-required(확인).

## 2. 접근 — auth-required 엔드포인트에 invalid-token 변종 1회 (orchestrator 밖)

httpInvoker의 요청 전송 코어를 추출:
```
private InvocationOutcome doSend(HttpClient http, Endpoint endpoint, JsonNode input, String authHeaderValue)
```
authHeaderValue!=null이면 그 값을 `authConfig.headerName()` 헤더로 설정, null이면 미설정. **보존 불변식**: baggage 헤더,
GET/DELETE noBody vs body 분기, 150ms flush, `coverage.dump(true)`→`cumulativeCoverage` OR-병합, InvocationOutcome
(status/response/coveredBranches/logStart/logEnd/httpCapture drain/coverageKey). `httpInvoker()`는 provider 토큰으로
`doSend` 위임(동작 불변); 부정 패스는 무효 토큰으로 `doSend` 호출.

**부정 패스 위치**: `run()` 안, state-guard 변종 블록 **후**·`report()`(cumulativeCoverage 기준) **전**. auth-required +
`authProvider!=null` + `!"off".equalsIgnoreCase(System.getenv("GRB_NEGATIVE_AUTH"))`일 때:
`doSend(http, endpoint, happy.body(), authConfig.headerValue("invalid-token-" + endpoint.id()))` 1회.
무효 토큰(파싱 불가 문자열)은 `JwtUtil.validate`에서 예외→catch→false → 필터 거부 arm. doSend의 per-request dump가
거부 arm 커버를 cumulativeCoverage에 크레딧(→runWideExec, missed→covered).

**path 캡처 + 생성 제외(신규)**: 결과를 12-arg `ExploredPath`로 캡처 —
`(endpoint.id()+"-negauth", endpoint.id(), happy.body()[sampleInput], outcome.status()[401/403], parseJsonOrNull(
응답)[실제 거부 body], List.of()[sql], List.of()[http], List.copyOf(outcome.coveredBranches()), "negative-auth"
[discoveredBy], List.of()[constraints], List.of()[warnings], List.of()[seeds])`. **attachSeeds 후에 finalPaths에
append**(빈 seed라 attach 무관). `Generator.generate()`의 per-path 루프(line 68)에 **신규** skip 추가:
`if ("negative-auth".equals(path.discoveredBy())) continue;` (현재 discoveredBy skip은 없음 — Kafka는 boolean
`variant` 필드라 별개 메커니즘. 이건 신규 가드). discoveredBy는 영속·reload되므로 skip이 로드 후에도 발화.

예산: auth 엔드포인트당 +1 요청(budgetRequests와 별개, 작음).

## 3. E2E/수용 기준 (먼저 작성, 바깥 루프 — Docker 필요)
> `BuilderE2eTest`(실제 order-service jar). auth-required 엔드포인트(`get-api-orders`)에 한정.
1. **하드 게이트(결정적)**: `get-api-orders`가 `discoveredBy=="negative-auth"` & `expectedStatus ∈ {401,403}`인
   ExploredPath를 **갖는다**. 되돌리면(happy만) 그 path 없음 → FAIL.
2. **생성 무회귀**: `Generator` 단위테스트 — negative-auth path 포함 엔드포인트 → 그 path는 파일 미생성(skip),
   나머지 path 생성 수 불변. run-e2e 생성 수 불변.
3. **무회귀**: 기존 BuilderE2eTest 단언(201/404/400/409, Kafka happy+변종, WS, state-guard, inter-field) 전부 불변.
4. **ablation/전 SUT 회귀**: `GRB_NEGATIVE_AUTH=off`면 negative-auth path 0. order-service(in-tree auth SUT) +
   petclinic·tainted-spring MVC(외부 e2e 클론 — auth/permitAll별 무영향, JWT-auth SUT는 거부 arm 커버 추가, 감소 불가).

## 4. Double-loop TDD 순서
1. **바깥 먼저(RED)**: §3-1 단언을 `BuilderE2eTest`에 추가 — RED.
2. **inner #1 doSend 추출(리팩터)**: httpInvoker→doSend 위임. 기존 빌더 단위·통합 불변(동작 보존, §2 불변식).
3. **inner #2 생성기 skip(단위, RED→GREEN)**: `Generator.generate()` 루프에 negative-auth skip + 단위테스트(그 path 미생성, 타 path 수 불변).
4. **inner #3 부정 패스 배선**: run()에 invalid-token doSend + 12-arg path append + `GRB_NEGATIVE_AUTH` 게이트(run() 내 getenv, Kafka 선례).
5. **단위 회귀(no Docker)**: `:graph-rag-builder:test`(통합 제외 패키지) + `:test-generator:test` + `:shared-model:test`.
6. **바깥 GREEN(Docker)**: `BuilderE2eTest` §3-1 통과 + ablation(off) 확인.
7. **PR 게이트**: 회귀 green(Docker-skip 명시) + docs/24 갱신 → spec-compliance 리뷰 → code-quality 리뷰 → triage.

## 5. 범위 / 비범위
- **범위**: auth-required 엔드포인트에 invalid-token 변종 1회 → JWT 필터/유틸 거부 arm 커버. negative-auth path 캡처(생성 제외).
- **비범위**: expired-token(실제 만료 JWT 생성 필요)·role/권한 세분·negative-auth **테스트 생성**(커버리지만). no-token 변종(이미 로그인이 커버). 비-JWT 인증 SUT는 4xx여도 JwtUtil arm 미적용(path는 캡처, 무해) — 현 스코프 JWT SUT.

## 6. 리스크 (리뷰 반영)
- **생성기 누출(Kafka #37 교훈)**: negative-auth path가 생성되면 회귀 → discoveredBy skip(신규). `Generator` 단위로 가드 + 무생성·타 path 수 불변 단언.
- **doSend 리팩터 회귀**: §2 보존 불변식 목록대로 추출. inner#2 기존 통합(BuilderE2eTest happy 경로)으로 확인.
- **status 가변(401 vs 403)**: Spring entry-point 유무로 갈림 → 단언 `∈{401,403}`.
- **invalid token 조기 예외 vs false**: 파싱 불가 Bearer는 `JwtUtil.validate` 예외→catch→false(어느 쪽이든 거부 arm). 서명 검증으로 통과 불가.
- **sampleResponse 정합**: 거부 path의 sampleResponse는 **실제 401/403 body**(happy body 아님) — 생성 제외라 FixtureComposer 미사용이나 정합 위해 실제 응답 저장.

## 7. 관련 파일
- 수정: `run/EndpointExplorationRunner.java`(doSend 추출 + 부정 패스 + 게이트), `generator/Generator.java`(discoveredBy skip).
- 테스트: `GeneratorNegativeAuthTest`(생성 skip 단위), `BuilderE2eTest`(수용).
- 문서: `docs/24` 갱신.

## 8. 3-모델 리뷰 triage (Opus/Sonnet/Haiku)
세 모델 `needs_revision` — 접근은 sound(Opus 확인), 근거/명확성 수정. 반영:
- **근거 정정(Opus I1/I2)**: §0 petclinic 심볼(JwtAuthenticationFilter/validateToken)은 외부 클론 — in-repo 벤치마크는 order-service(`JwtAuthFilter`/`JwtUtil.validate`, validateToken 없음)로 명시. 스윕에서 petclinic은 외부 e2e 클론으로 표기.
- **생성기 skip은 신규(Sonnet I1, Haiku I3)**: Kafka는 boolean `variant`라 별개 — discoveredBy skip은 generate() 루프에 신규 추가로 정정(Kafka-동일 표현 제거).
- **sampleResponse(Sonnet I2)**: happy body 아닌 실제 401/403 응답. sampleInput=happy body. 12-arg 전부 명시.
- **gate 위치(Sonnet I3)**: run() 내 getenv(Kafka 선례) — state-guard처럼 BuilderCli 경유 대신. 명시.
- **패스 위치/머지 타이밍(Sonnet I4)**: state-guard 블록 후·report() 전, doSend dump→cumulativeCoverage 크레딧 후 report.
- **doSend 시그니처(Sonnet I5, Haiku I2)**: 명시 + 보존 불변식 목록.
- **벤치마크 엔드포인트(Haiku I6)**: get-api-orders는 auth-required(검증) — 단언 대상 확정.
- **거부**: coverage missed→covered 직접 단언(Opus I7)은 path 존재로 갈음(report에 필터는 endpoint 아님 — branch 단언 인프라 과대). isEqual/expired(비범위).

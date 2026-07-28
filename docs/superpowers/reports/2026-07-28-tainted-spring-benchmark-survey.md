# tainted-spring MSA — Phase A 삼중 합성 벤치마크 후보 정적 조사

- 작성일: 2026-07-28
- 상태: **정적 조사만 완료 (SUT 부팅·Docker·A/B 측정 미실행)**. 조사 머신의 load average가
  250+(10코어)라 SUT 기동을 동반하는 측정은 실패가 예정돼 있어 의도적으로 배제했다.
- 배경: petclinic이 Phase A A/B 벤치마크로 부적합함이 확정돼(잔여 가드가 `||` 결합 INPUT 단독
  범위검사 위주 + 파라미터 전파에서 origin 소실 → 승격 가능한 트리플 후보를 구조적으로 만들 수
  없음, [수동 실증 절차서 E2E-B2 실행 기록](2026-07-26-triple-synthesis-manual-evidence.md) 참조)
  대체 벤치마크를 찾기 위한 조사다.
- 관련 문서: [design spec](../specs/2026-07-26-agent-skill-triple-synthesis-design.md),
  [요구사항명세 REQ-029](../requirements/2026-07-26-agent-skill-triple-synthesis-requirements.md),
  [docs/03 삼중 합성 CLI 절](../../03-graph-rag-builder.md)
- 조사 대상 레포: `github.com/baekchangjoon/tainted-spring-{diary,community,mindgraph,analytics,
  notification,counseling,auth-user,bff-gateway}` (8종 전부 `--depth 1` 클론, 작업 사본은
  `.work/tainted-spring/` — gitignore 대상)

---

## 0. 결론 요약 (먼저 읽을 것)

**tainted-spring 8개 서비스 24개 REST 엔드포인트 전부에 `provenance` CLI를 실제로 돌린 결과,
INPUT×DB_READ 교차 가드 0건, INPUT×EXTERNAL_RESPONSE 교차 가드 0건이다.** 전 서비스를 통틀어
인식된 가드는 **단 1건**(`auth-user` `GET /api/v1/me`)이고, 그 1건조차 피연산자 3개가 모두
`UNKNOWN`이다 — **petclinic에서 관측된 실패 모드(`||` 결합 + 파라미터 전파에서 origin 소실)와
동일한 형태**다.

따라서 정직한 판정은 다음과 같다.

- **"교차 가드를 푸는 능력"을 측정하는 벤치마크로는 tainted-spring도 부적합하다.** 그런 가드가
  SUT에 존재하지 않는다. petclinic 교체만으로는 문제가 해결되지 않는다.
- **다만 "삼중 채널 라우팅(body/seed/stub)으로 깊은 happy path를 여는 능력"을 측정하는
  벤치마크로는 유효하다.** tainted-spring에는 2xx 미도달 엔드포인트가 실재하고(§3), 그 미도달
  원인이 정확히 Phase A가 겨냥한 세 채널(입력 형상·DB 시드·외부 스텁)에 대응한다. 단 그 전에
  **가드 인식기 3개 결함을 고쳐야** 측정이 가능하다(§5).
- 즉 Phase A의 A/B 측정 지표를 "교차 가드 해소 수"가 아니라 **"엔드포인트 2xx 도달 여부"**로
  재정의하면 tainted-spring이 petclinic보다 명확히 낫다. 지표를 그대로 두면 tainted-spring도
  "효과 미측정"으로 끝난다.

---

## 1. 조사 방법

### 1.1 실행한 것

1. 8개 레포를 `gh repo clone ... -- --depth 1`로 `.work/tainted-spring/<name>`에 클론(8/8 성공).
2. 각 서비스의 `@RestController`/`RouterFunction`/`RouteLocator` 표면을 소스 전수 판독.
3. `provenance` CLI를 **24개 엔드포인트 전부에 실제로 실행**. gradle `run` 태스크 대신
   `:graph-rag-builder:printRuntimeClasspath`로 뽑은 클래스패스에 직접 `java -cp`로 붙여
   JVM 기동 1회/엔드포인트로 처리했다(부하 최소화). 실행 형태:

   ```
   java -cp "<runtime-cp>" io.graphrag.builder.cli.BuilderCli provenance \
     --sut-src <abs>/.work/tainted-spring/<svc>/src/main/java \
     --endpoint '<METHOD> <PATH>' --out <out>/<svc>--<name>.json
   ```

4. 산출 24개 `provenance-report.json`을 집계(가드 수·피연산자 origin·unguarded·unresolved).
5. 각 레포에 동봉된 기존 블랙박스 산출물(`graphrag-blackbox/README.md`,
   `KNOWN-LIMITATIONS.md`, `graph/exploration-report.json`)을 교차 판독해 **현행 도구가 실제로
   어떤 상태코드까지 도달했는지**를 확인(= "2xx 미도달인가"의 근거).

### 1.2 실행하지 않은 것 (그리고 그 이유)

- **SUT 부팅·docker compose·A/B 커버리지 측정**: 머신 부하(load 250+)로 실패가 예정됨. 이번
  범위 밖으로 명시적으로 배제했다. §6에 부하 해소 후 실행할 절차 초안을 남긴다.
- **`synthesize-triple` / `trial` 실행**: `provenance` 결과가 전 엔드포인트 0 교차 가드이므로
  하위 단계를 돌려도 산출물이 비어 정보량이 없다. 돌리지 않았다는 사실을 그대로 기록한다.

---

## 2. 서비스별 요약표

`EP` = 어노테이션/함수형 라우팅으로 **현행 인덱서가 실제로 해소한** REST 엔드포인트 수.
`가드` = `provenance-report.json`의 `guards[]` 길이 합. `교차` = 한 가드 안에 INPUT과
DB_READ(또는 EXTERNAL_RESPONSE)가 동시에 등장하는 가드 수. `UNKNOWN 비율` = 전체 가드 피연산자
중 `origin == UNKNOWN` 비율.

| 서비스 | 저장소 | EP | 가드 | 교차(INPUT×DB) | 교차(INPUT×EXT) | DERIVED | UNKNOWN 비율 | unresolved | 구조적 제약 |
|---|---|---:|---:|---:|---:|---:|---:|---:|---|
| `diary` | PostgreSQL + Kafka | 5 | 0 | 0 | 0 | 0 | — | 0 | 평면 DTO(5필드). 존재 가드는 `orElseThrow(DiaryNotFoundException)` → **미인식** |
| `community` | PostgreSQL + Kafka | 5 | 0 | 0 | 0 | 0 | — | 0 | 평면 DTO. 존재 가드 `PostNotFoundException` → 미인식 |
| `mindgraph` | PostgreSQL + Redis + Feign | 2 | 0 | 0 | 0 | 0 | — | 0 | 응답이 중첩 컬렉션(`nodes[]`/`links[]`), 시드 컬럼이 **JSON 문자열** |
| `analytics` | PostgreSQL | 2 | 0 | 0 | 0 | 0 | — | 0 | 가드 자체가 없음. 2xx 이미 도달(빈 목록) |
| `notification` | **Redis 전용(DB-less)** | 1 | 0 | 0 | 0 | 0 | — | 0 | `seed.sql` 채널로 Redis List를 시드할 수 없음 |
| `counseling` | **Redis 전용 + WebFlux 함수형 라우팅** | 2 | 0 | 0 | 0 | 0 | — | 0 | 함수형 라우트는 해소되나 **body 형상 미추출**(`unguarded[]` 비어 있음) |
| `auth-user` | MySQL + Redis + 외부 OAuth | 5 | **1** | 0 | 0 | 0 | **100%** (3/3) | 0 | 인식된 유일한 가드가 전부 UNKNOWN(petclinic과 동형) |
| `bff-gateway` | Spring Cloud Gateway + WebFlux | 2 | 0 | 0 | 0 | 0 | — | 0 | 인증이 `WebFilter`에 있어 핸들러 슬라이스 밖. **프록시 라우트 4개 전부 인덱싱 실패** |
| **합계** | — | **24** | **1** | **0** | **0** | **0** | 100% | **0** | — |

### 2.1 엔드포인트별 `unguarded[]` 산출 (마커 계약 표현 가능성)

body가 있는 엔드포인트만 발췌. `unguarded[]`가 채워진다는 것은 `body.json` 갭 마커를 그 자리에
놓을 수 있다는 뜻이므로, Phase A 마커 계약의 표현 가능성 지표다.

| 엔드포인트 | unguarded 필드 | 형상 판정 |
|---|---|---|
| `POST /internal/diaries` (diary) | `userId`,`title`,`content`,`primaryEmotion`,`energyScore` | 평면 스칼라 5 — **표현 가능** |
| `PUT /internal/diaries/{id}` (diary) | `title`,`content`,`primaryEmotion`,`energyScore` | 평면 스칼라 4 — **표현 가능** |
| `POST /internal/posts` (community) | `userId`,`title`,`category`,`content`,`moodEmoji`,`nickname` | 평면 스칼라 6 — **표현 가능** |
| `POST /internal/posts/{id}/comments` (community) | `author`,`content`,`role` | 평면 스칼라 3 — **표현 가능** |
| `POST /api/v1/auth/login` (auth-user) | `provider`,`providerToken` | 평면 스칼라 2 — **표현 가능**(단 값 제약이 불가시, §5.3) |
| `POST /internal/auth/verify` (auth-user) | `token` | 평면 스칼라 1 — **표현 가능** |
| `POST /internal/counseling/sessions` (counseling) | **(비어 있음)** | 함수형 라우트 body 미추출 — **표현 불가** |
| `POST /internal/counseling/sessions/{id}/messages` (counseling) | **(비어 있음)** | 동일 — **표현 불가** |

동적 키 `Map` body(petclinic quotas류 구조적 한계)는 **tainted-spring 전체에 존재하지 않는다**.
이 점은 petclinic 대비 명확한 개선이다 — 모든 어노테이션 기반 body가 평면 스칼라 record/POJO다.

---

## 3. "현행 합성으로 2xx 미도달"의 근거

각 레포에 동봉된 기존 블랙박스 산출물이 도구가 실제로 도달한 상태코드를 기록하고 있다. 정적
판단이 아니라 **과거 실측 기록**이므로 그대로 인용한다.

| 엔드포인트 | 현행 도달 상태코드 | 2xx 미도달 원인 | Phase A 대응 채널 |
|---|---|---|---|
| `GET /internal/graphs/diary/{diaryId}` (mindgraph) | 404만 (+격리된 flaky 500) | `graph_record` 행 부재. 탐색기가 넣은 시드는 `nodes_json="probe"`로 **JSON 불량** → 500 | `seed.sql` (**DB_READ**) |
| `GET /internal/graphs/user/{userId}` (mindgraph) | 404만 | Redis 캐시 미시드 | (Redis — 채널 없음) |
| `GET /api/v1/diaries/{id}` (bff) | **401만** | 유효 Bearer 미부착 + auth-user verify·diary·mindgraph 스텁 부재 | `body.json`(헤더) + `stubs.json` (**EXTERNAL_RESPONSE**) |
| `GET /api/v1/me/mood-trends` (bff) | **401만** | 동일(+ analytics 스텁) | 동일 |
| `POST /api/v1/auth/login` (auth-user) | **401/400만** | `provider ∈ {google,kakao,naver,toss}` **그리고** `providerToken == "valid-<provider>-<seed>"` 라는 2필드 결합 제약 미충족 | `body.json` (**INPUT**) |
| `GET /internal/notifications/{userId}` (notification) | 200(빈 목록)만 | 비어 있지 않은 목록 경로는 Redis List 시드 필요 | (Redis — 채널 없음) |
| `POST /internal/counseling/sessions/{id}/messages` (counseling) | 미탐색(과거) / 현행 해소되나 body 미추출 | 선행 세션이 Redis에 있어야 함 | (Redis — 채널 없음) |
| `GET /internal/analytics/mood/{userId}` (analytics) | **200 도달** | — (빈 목록 200) | 개선 여지 낮음 |
| `POST /internal/diaries`, `POST /internal/posts` 등 | **2xx 도달** | — | 개선 여지 낮음 |

> bff-gateway의 200 경로는 **SUT 결함이 아님이 이미 실증돼 있다** — 해당 레포 README의
> "수동 200 실증" 절이, verify 스텁(`active:true`) + 다운스트림 스텁을 붙이면 두 엔드포인트가
> 200 + 올바른 집계 본문을 돌려준다고 기록한다. 즉 **Phase A가 열어야 할 대상이 실재하고, 열리면
> 열린다는 것이 이미 확인된 상태**다. A/B 벤치마크로서 이상적인 조건이다.

---

## 4. Top 3 추천

판정 기준 4개(교차 가드 실재 / 현행 도구 인식 / 마커 계약 표현 가능 / 2xx 미도달)를 그대로
적용하면 **기준 ②(현행 도구 인식)를 통과하는 후보는 0개다.** 그러므로 아래 Top 3은
"**최소 도구 수정으로 Phase A 효과가 측정 가능해지는 순서**"로 제시한다. 각 항목에 필요한 수정을
명시했다(수정 없이 그대로 측정하면 세 후보 모두 "효과 미측정"이 된다).

### 1위 — `bff-gateway` `GET /api/v1/diaries/{id}` (EXTERNAL_RESPONSE 채널)

- **근거(강):** Phase A의 3채널 중 `stubs.json` 채널을 **가장 순수하게** 측정한다. 200 도달에
  필요한 것이 전부 외부 응답이다 — auth-user `POST /internal/auth/verify` → `{active:true,userId}`,
  diary `GET /internal/diaries/{id}`, mindgraph `GET /internal/graphs/diary/{id}`. DB 시드가
  **전혀 필요 없다**(bff는 자체 DB가 없다) → 시드 채널의 잡음 없이 스텁 채널만 A/B 가능.
- **근거(강):** 현행 도달 상태코드가 **401 하나뿐**이고, 200이 수동으로 실증돼 있다.
  A/B의 before(401)/after(200) 대비가 이보다 선명할 수 없다.
- **근거(중):** 응답 형상이 `{"diary":…,"graph":…}`로 단순하고 동적 키 Map이 없다.
- **필요한 도구 수정:** 재귀 슬라이스가 **`WebFilter`/`GlobalFilter`를 핸들러 슬라이스에
  포함**해야 한다. 현재 가드는 전부 `BearerAuthSupport.authorize`에 있는데
  (`token == null` → 401, `!result.active()` → 401), 이건 `AuthWebFilter`가 부르는 코드라
  컨트롤러 메서드에서 출발하는 DFS가 도달하지 못한다 → `guards: []`.
  `!result.active()`는 origin을 붙이면 **EXTERNAL_RESPONSE**(auth-user verify 응답의 `active`
  필드)로 정확히 태깅될 수 있는 형태다.
- **리스크:** 인증 필터를 슬라이스에 넣는 것은 범위가 넓은 변경이다(전 SUT에 영향). 대안으로
  "엔드포인트별 인증 요구를 `authRequired`로 표면화하고 스텁 채널에만 반영"하는 좁은 수정도
  가능하다 — 어느 쪽이든 별도 설계 판단이 필요하다.

### 2위 — `mindgraph` `GET /internal/graphs/diary/{diaryId}` (DB_READ 채널)

- **근거(강):** `seed.sql` 채널을 순수하게 측정한다. 백엔드가 **PostgreSQL**이므로
  `seed.sql` 화이트리스트 규약이 그대로 적용된다(Redis 서비스들과 달리 채널 불일치가 없다).
  대상 테이블은 `graph_record` 단일, FK 전이 없음.
- **근거(강):** 200 미도달이 **정확히 시드 품질 문제**임이 기록으로 남아 있다 — 과거 탐색기가
  `nodes_json="probe"`를 넣어 `objectMapper.readValue`가 터지고 500이 났다. 즉 "값의 의미를
  아는 시드"를 만들 수 있느냐가 정확한 측정 대상이 된다. Phase A의 갭 마커 + 에이전트 채움
  계약이 겨냥하는 문제와 정확히 일치한다.
- **근거(중):** 엔드포인트 표면이 극단적으로 단순(path var 1개, body 없음) → 다른 변수가 개입할
  여지가 적다.
- **필요한 도구 수정:** `ProvenanceIndexer.isExistsGuardLambda`가 **커스텀 도메인 예외를 인식**해야
  한다(§5.1). 현재 `repository.findById(diaryId).orElseThrow(() -> new GraphNotFoundException(...))`가
  가드로 잡히지 않아 `guards: []`다.
- **리스크(중):** `nodes_json`/`links_json`이 **JSON 문자열 컬럼**이라, 유효 시드를 만들려면
  마커 채움이 "컬럼 값 = 유효 JSON 배열"이라는 의미 제약을 만족해야 한다. 결정적 코드가 정할 수
  없는 값이므로 **에이전트 채움 슬롯**이 되는데, 이것이 Phase A 설계 의도에 정확히 부합하는지
  아니면 난이도를 과도하게 올리는지는 실측 전엔 단정할 수 없다.

### 3위 — `auth-user` `POST /api/v1/auth/login` (INPUT 채널)

- **근거(강):** 인프라 시드·스텁이 **하나도 필요 없는** 가장 값싼 A/B다. 사용자 계정이 없으면
  자동 생성(`orElseGet(() -> repository.save(...))`)되므로 DB 선시드 불필요, 기본 프로파일에서
  소셜 검증은 `MockSocialVerifier`(외부 호출 없음)로 위임된다. **body.json 채널만 바꿔서**
  401 → 200을 만들 수 있다.
- **근거(중):** 값 제약이 "리터럴 하나 맞추기"가 아니라 **2필드 결합 제약**이다 —
  `provider ∈ {google,kakao,naver,toss}`이고 `providerToken`이
  `"valid-" + provider + "-" + <비어있지 않은 seed>`로 시작해야 한다. 즉 `providerToken`의 정답이
  `provider` 값에 의존한다. petclinic의 단독 범위검사보다 명백히 상위 난이도이며, DERIVED/
  concolic 채널의 실효성을 재는 데 적합하다.
- **근거(중):** `unguarded[]`가 `provider`/`providerToken` 2개로 정확히 채워져 마커 계약을 그대로
  쓸 수 있다.
- **필요한 도구 수정:** `SocialVerifier` 인터페이스의 **다중 구현(5개: Composite/Mock/Google/
  Kakao/Naver) 디스패치**를 넘어야 한다(§5.3). 현재는 그 호출을 넘지 못해 가드가 0건이고,
  **`unresolved[]`에도 아무것도 남지 않는다** — 에이전트에게 "여기 못 본 게 있다"는 신호조차
  없는 조용한 누락이다. 최소한 `MULTI_IMPL` unresolved 표면화는 반드시 필요하다.
- **리스크(중):** 정답 문자열의 `seed` 부분이 자유 텍스트라 concolic 해가 아니라 **에이전트 채움**
  으로만 결정된다. 즉 이 후보는 "결정적 코드의 해 능력"이 아니라 "에이전트 채움 + 마커 계약"의
  효과를 재는 쪽에 가깝다.

### 차순위(보류) — `bff-gateway` `GET /api/v1/me/mood-trends`

1위와 동일 계열(스텁 3개 → 2개, analytics만)이라 1위가 열리면 거의 자동으로 열린다. **독립
측정점으로는 정보량이 낮아** Top 3에 넣지 않았다. 1위의 회귀 확인용 두 번째 케이스로 쓰면 좋다.

---

## 5. 부적합 사유 기록 (petclinic 사례와 같은 자산으로 남김)

### 5.1 (도구 결함) `orElseThrow`의 커스텀 도메인 예외를 EXISTS 가드로 인식하지 못한다 — 영향 6개 서비스

`ProvenanceIndexer.isExistsGuardLambda`는 람다 본문에 `CtThrow` 문이 있거나
`new ResponseStatusException(...)`이 있을 때만 EXISTS 가드로 인정한다.

```java
private static boolean isExistsGuardLambda(CtLambda<?> lambda) {
    boolean hasThrow = !lambda.getElements(new TypeFilter<>(CtThrow.class)).isEmpty();
    boolean constructsResponseStatusException = lambda.getElements(new TypeFilter<>(CtConstructorCall.class))
            .stream()
            .anyMatch(cc -> cc.getType() != null
                    && "ResponseStatusException".equals(cc.getType().getSimpleName()));
    return hasThrow || constructsResponseStatusException;
}
```

tainted-spring은 **`@RestControllerAdvice` + 커스텀 도메인 예외**라는 현대 Spring 관용을 쓴다:

- `diary`: `orElseThrow(() -> new DiaryNotFoundException(...))`
- `community`: `orElseThrow(() -> new PostNotFoundException(...))`
- `mindgraph`: `orElseThrow(() -> new GraphNotFoundException(...))`
- `auth-user`: `orElseThrow(() -> new UserNotFoundException(...))`, `new InvalidTokenException(...)`

식(expression) 람다이므로 `CtThrow`가 없고 `ResponseStatusException`도 아니다 → **전부 미인식**.
그 결과 6개 서비스 14개 엔드포인트가 `guards: []`로 나온다. **이것이 "교차 가드 0건"의 최대
단일 원인이다.** 수정 방향: 람다가 `RuntimeException` 서브타입을 생성하고 그 타입이
`@ResponseStatus` 또는 `@ExceptionHandler` 대상으로 매핑돼 있으면 EXISTS 가드로 인정.

### 5.2 (도구 결함, petclinic과 동형) 파라미터 전파에서 origin 소실

`auth-user` `GET /api/v1/me`에서 유일하게 잡힌 가드:

```json
{ "at": "MeController.java:24", "op": "||",
  "operands": [ {"origin":"UNKNOWN","javaType":"String"},
                {"origin":"UNKNOWN","javaType":"<nulltype>"},
                {"origin":"UNKNOWN","javaType":"boolean"} ] }
```

대응 소스는 `MeController.extractBearer`의
`if (authorization == null || !authorization.startsWith("Bearer "))`다. `authorization`은 핸들러
`me(@RequestHeader String authorization)`의 파라미터에서 **그대로 넘어온 값**이지만, INPUT 태깅은
"리프의 루트가 **핸들러 자신의** `CtParameter`인가"만 보므로, 한 단계 아래 private 메서드의
파라미터가 되는 순간 동일성이 깨져 UNKNOWN이 된다. **petclinic에서 확정된 실패 모드가 다른
코드베이스에서 그대로 재현됐다** — SUT 특성이 아니라 인덱서의 일반적 한계임이 이로써 확인된다.

### 5.3 (도구 결함) 다중 구현 인터페이스 디스패치에서 조용한 누락

`auth-user` `POST /api/v1/auth/login`의 실질 가드는 `SocialVerifier` 인터페이스 뒤에 있다
(`CompositeSocialVerifier` → `MockSocialVerifier.verify`의
`!SUPPORTED.contains(provider)` / `!providerToken.startsWith(prefix)`). DFS가 이 호출을 넘지
못해 가드 0건이 되는 것까지는 설계된 한계지만, **`unresolved[]`도 비어 있다**. 클래스 문서는
`MULTI_IMPL`을 unresolved에 표면화한다고 적고 있으나, 그 경로는 **가드 피연산자가 된 호출**에만
적용되고 "가드를 품고 있는 호출"에는 적용되지 않는다. 결과적으로 에이전트가 받는 리포트는
"가드 없음 + 미해결 없음" = **깨끗한 엔드포인트로 오인**된다. 조용한 누락은 UNKNOWN보다 나쁘다.

### 5.4 (도구 결함) Spring Cloud Gateway 프록시 라우트 — `uri()` 리터럴 요구로 4/4 전부 스킵

`GatewayRouteIndexer`는 이미 구현돼 있다(과거 `KNOWN-LIMITATIONS`의 "미지원" 기록은 **낡았다**).
그러나 tainted-spring bff에서는 4개 라우트가 전부 스킵되고 다음 경고만 남는다:

```
WARN i.g.b.index.GatewayRouteIndexer - GatewayRouteIndexer: path 또는 uri를 찾을 수 없어 라우트를 건너뜁니다. (×4)
```

원인은 `extractUri`가 `.uri(...)` 인자를 **문자열 리터럴로만** 받는 데 있다:

```java
if ("uri".equals(inv.getExecutable().getSimpleName())
        && !inv.getArguments().isEmpty()
        && inv.getArguments().get(0) instanceof CtLiteral<?> lit
        && lit.getValue() instanceof String value) { return value; }
```

tainted-spring은 `@Value("${services.diary.url}") String diaryUrl`을 주입받아 `.uri(diaryUrl)`로
넘긴다 — 리터럴이 아니라 메서드 파라미터다. `path()`는 정상 추출되므로 **`uri` 하나 때문에**
`/api/v1/auth/**`, `/api/v1/community/**`, `/api/v1/counseling/**`, `/api/v1/diaries` 4개 표면이
전부 사라진다. 수정 난도는 낮다(파라미터 → `@Value` 플레이스홀더 해석, 또는 uri 미해석 시
"라우트는 유지하되 target 미상"으로 완화).

### 5.5 (도구 결함) WebFlux 함수형 라우팅 — 라우트는 해소되나 body 형상이 비어 있다

`counseling`은 `RouterFunctions.route().POST(...)` 함수형 라우팅인데, 현행 빌더는 이를
**정상 인식한다**(`found 2 functional route(s) (RouterFunction)`) — 과거 `KNOWN-LIMITATIONS`의
"하드 블로커" 기록도 **낡았다**. 그러나 두 엔드포인트 모두 `unguarded[]`가 **비어 있다**:
body가 `@RequestBody` 파라미터가 아니라 `request.bodyToMono(CreateSessionRequest.class)`로
읽히기 때문에, `@RequestBody` 타입 전개를 전제로 한 unguarded 탐지가 아무것도 못 찾는다.
`CreateSessionRequest`/`SendMessageRequest`는 실재하는 record인데도 마커 슬롯이 하나도 생기지
않으므로 **마커 계약으로 표현 불가** → 삼중 합성 대상에서 제외.

### 5.6 (SUT 특성) Redis 전용 서비스는 `seed.sql` 채널과 구조적으로 불일치

`notification`(Redis List/Set 전용, DB-less), `counseling`(Redis 세션 전용),
`auth-user`의 토큰 저장(Redis), `mindgraph`의 user-graph 캐시(Redis)는 상태가 전부 Redis에 있다.
Phase A의 DB 채널은 `seed.sql` **하나뿐**이므로 이 상태를 결정적으로 시드할 수단이 없다.
`GET /internal/notifications/{userId}`의 "비어 있지 않은 목록" 경로,
`POST /internal/counseling/sessions/{id}/messages`의 200 경로가 여기 걸린다. **도구 결함이 아니라
채널 설계의 범위 밖**이므로, Redis 시드 채널 추가는 별도 설계 결정 사항으로 남긴다.

### 5.7 (SUT 특성) 이미 2xx에 도달한 엔드포인트 — 개선 여지 없음

`diary`(5 EP 중 4), `community`(5 EP 전부), `analytics`(2 EP 전부)는 현행 합성으로 이미
2xx에 도달한다. `diary POST`의 201이 격리된 것은 **도달 실패가 아니라 Kafka 이벤트의
비결정 필드(`eventId`/`occurredAt`) JSONAssert 하드코딩** 때문이다 — Phase A가 겨냥하는 문제가
아니다. A/B 벤치마크로 쓰면 before/after가 같아 정보량이 0이다.

---

## 6. 다음 단계 — A/B 측정 절차 초안 (부하 해소 후 실행 전제)

> **전제:** 머신 load average가 안정권(10코어 기준 < 20)으로 회복돼 있을 것. 본 절차는 SUT
> 부팅과 docker compose를 동반하므로, 부하 상태에서 실행하면 SUT 기동 타임아웃으로 실패한다
> (E2E-B2 실행 #1의 실패 원인과 동일).

### 6.0 (선행, 필수) 도구 수정 게이트

§5.1(EXISTS 가드) → §5.3(MULTI_IMPL 표면화) → §5.4(Gateway `uri`) 순으로 고친다. 각 수정 후
**해당 엔드포인트의 `provenance`를 다시 돌려 `guards[]`가 비어 있지 않은지**만 확인하면 되므로
SUT 부팅이 필요 없다 — 부하 상태에서도 진행 가능한 유일한 단계다. 이 게이트를 통과하지 못하면
아래 6.1~6.4는 실행해도 "효과 미측정"으로 끝난다(petclinic E2E-B2와 동일한 결말).

판정 기준(수정 후 기대값):

| 엔드포인트 | 기대 `guards[]` | 기대 origin |
|---|---|---|
| `GET /internal/graphs/diary/{diaryId}` | EXISTS 1건 | 피연산자 = INPUT(`diaryId`, path) |
| `POST /api/v1/auth/login` | ≥1건 또는 `unresolved[MULTI_IMPL]` ≥1건 | INPUT(`provider`,`providerToken`) |
| `GET /api/v1/diaries/{id}` | ≥1건(WebFilter 포함 시) | EXTERNAL_RESPONSE(`active`) |

### 6.1 환경 준비

각 서비스 레포에 이미 `Dockerfile`과 블랙박스 README의 compose 절차가 동봉돼 있다. 플랫폼
오케스트레이션이 필요하면 `tainted-spring-platform`을 추가로 클론한다(이번 조사에서는 부팅을
하지 않으므로 클론하지 않았다).

- 1위 후보(bff): bff 본체 + WireMock 스텁 4종(`auth-verify`, `diary-detail`, `mindgraph-diary`,
  `analytics-mood`). 해당 레포 `graphrag-blackbox/stubs/`에 **기존 스텁 파일이 그대로 있다**.
- 2위 후보(mindgraph): mindgraph + PostgreSQL만. Kafka·diary 다운스트림은 REST 조회 경로에서
  호출되지 않으므로 불필요.
- 3위 후보(auth-user): auth-user + MySQL + Redis. 외부 OAuth는 mock 모드라 불필요.

### 6.2 A(baseline) 측정

Phase A 삼중 합성을 **끄고** 기존 `build` 경로만으로 생성·실행해 엔드포인트별 최고 도달
상태코드와 커버리지를 기록한다. 기대 baseline은 §3 표와 일치해야 한다(불일치하면 환경 차이를
먼저 규명하고 진행 — 불일치를 무시한 채 B를 측정하면 개선분이 오염된다).

### 6.3 B(Phase A) 측정

```
provenance      --sut-src <abs>/src/main/java --endpoint '<METHOD> <PATH>' --out <out>/provenance-report.json
synthesize-triple --report <out>/provenance-report.json --triple-store <store> --sut-src <abs>/src/main/java
trial           --triple-candidates <store> ...   # 후보별 반복
```

에이전트 스킬 순서(`provenance-analysis` → `triple-synthesis` → `trial-loop`)를 지킨다.
갭 마커 외 변경은 `TripleValidator`가 기계적으로 reject하므로, 채움은 마커 위치에만 한다.

### 6.4 판정·기록

| 지표 | A | B | 판정 |
|---|---|---|---|
| 엔드포인트 최고 도달 상태코드 | (§3 기록) | | **주 지표** — 401/404 → 2xx면 효과 있음 |
| 승격된 트리플 후보 수 | 0 | | 0이면 "효과 미측정"으로 기록(순증 0과 구분) |
| line/branch 커버리지 | | | 보조 지표 |
| 갭 마커 채움 슬롯 수 / 그중 에이전트 채움 | | | 결정적 코드 vs 에이전트 기여 분해용 |

결과는 [수동 실증 절차서](2026-07-26-triple-synthesis-manual-evidence.md)의 E2E-B2 실행 기록에
**누적**으로 덧붙이고, green이면 요구사항명세 REQ-029 상태를 전환한다. 실패면 사유를 남기고
🔴로 유지한다 — 조용히 넘어가지 않는다.

---

## 7. 반론·한계 (이 조사 자체에 대해)

- **"가드가 없다"는 판정은 `provenance` CLI 출력에 근거한다.** 그런데 §5에서 확인했듯 그 CLI에
  결함이 3개 있다. 따라서 "교차 가드 0건"은 **SUT에 없다**와 **도구가 못 본다**가 섞인 값이다.
  다만 소스 전수 grep(비교 연산자·`equals`·`if` 전수 판독)으로 교차 비교식이 실제로 존재하지
  않음을 별도 확인했으므로, **INPUT×DB_READ 0건은 SUT의 사실**이고, `EXISTS` 가드 다수 누락은
  **도구의 결함**이다. 이 둘을 뭉뚱그리지 않았다.
- **`bff-gateway`의 `!result.active()`를 "INPUT×EXTERNAL_RESPONSE 교차 가드"로 셀지는 정의에
  달려 있다.** 이항 비교가 아니라 "INPUT(토큰)이 외부 호출의 인자가 되고 그 응답 필드가 가드
  피연산자가 되는" **데이터 흐름 연쇄**다. 본 조사는 보수적으로 교차 가드 0건으로 셌다. Phase A의
  목적(두 채널 공동 배치)에 비추면 이 연쇄가 오히려 더 어려운 문제라는 반론이 가능하며, 그렇게
  본다면 1위 후보의 가치는 더 올라간다.
- **8개 서비스 전부 소규모다**(main 소스 13~30파일). 대형 SUT에서만 드러나는 문제(깊은 호출
  체인, depth-cap 도달)는 이 벤치마크로 측정되지 않는다. `--provenance-depth` 관련 한계는
  여기서 검증할 수 없다.
- **`counseling`/`bff`의 과거 `KNOWN-LIMITATIONS` 기록 2건이 낡았다는 사실**(RouterFunction·
  RouteLocator 지원이 이미 추가됨)은 이번 실행 로그로 확인했다. 그 레포들은 이 저장소의 관리
  대상이 아니므로 여기 기록만 남긴다.

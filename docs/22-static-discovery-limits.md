# 정적 분석의 한계와 입력 오라클 도달 범위

이 프로젝트의 정적 분석은 **Spoon AST 스캔**(`graph-rag-builder`의 `index/` 패키지)으로
REST/WS 핸들러·요청 바디 구조·응답 DTO·검증 제약을 열거한다. 애플리케이션을 실행하지
않고, 바이트코드 의미 실행도 하지 않는다. 실제 동작·SQL·도달 분기는 별도의 **탐색 단계**
(`run/EndpointExplorationRunner` + `explore/ExplorationOrchestrator`)가 SUT를 띄워
HTTP로 확정·관측한다.

| 단계 | 구성요소 | 하는 일 |
|---|---|---|
| AST 인덱싱 | `index/`: `EndpointIndexer`, `BodyShapeExtractor`, `ResponseDtoIndexer`, `ValidationConstraintExtractor`, `ConstraintExtractor` | 핸들러·바디 shape·DTO 필드·검증/비교 제약 추출 (실행 없음) |
| 입력 오라클 | `cli/BuilderCli`가 `StaticLiteralOracle` + `ConcolicOracle`을 `merge` | 분기를 여는 입력 후보 도출 (아래 1절) |
| 탐색 | `run/EndpointExplorationRunner` → `explore/ExplorationOrchestrator` | SUT 기동, HTTP 호출, JaCoCo 커버리지·SQL(`capture/SqlLogParser`) 관측 |

산출물은 `exploration-report.json`(엔드포인트별 미도달 분기 포함)과 `GraphAsset`(JSON
그래프 스토어)이다.

이 문서는 (1) 입력 오라클이 정적 리터럴을 넘어 도달하게 된 범위와, (2) 정적 분석이
구조적으로 볼 수 없어 **탐색 시 런타임 관측**에 의존하는 다섯 가지 패턴을 정리한다.

---

## 1. 비-리터럴 입력값 도출 — 부분적으로 극복됨

과거에는 "소스에 리터럴로 없는 값은 도출 불가, 범위 밖"이라고 못 박았다. 지금은
`oracle/ConcolicOracle`(ASM 심볼릭 스캔 + Z3)이 **리터럴이 아닌 입력값**을 도출한다.
`cli/BuilderCli`에서 `StaticLiteralOracle().analyze(...).merge(ConcolicOracle().analyze(...))`로
두 오라클의 후보가 합쳐진다.

- **`StaticLiteralOracle`** (Spoon AST): 소스에 리터럴로 박힌 비교식과 문자열 동치
  (`==`/`equals`)를 싸게 추출한다.
- **`ConcolicOracle`** (ASM 바이트코드 + Z3): 입력 필드(파라미터/접근자)에서 파생된
  정수 선형식 `coeff*field+const`를 추적하고, 각 비교 분기의 경계 등식 `==0`을 Z3로 풀어
  경계값 B의 `{B-1, B, B+1}`을 후보로 낸다. 소스에 리터럴이 없는 값도 도출한다:
  - **정수 선형**: `amount*3==21` → `7`
  - **long 산술**: `bonus*2==1e10` → `5e9` (int 범위 밖, `LCMP`로 처리)
  - **문자열 길이**: `code.length()==5` → `"xxxxx"` (해당 길이 문자열)

**정확한 도달 범위**: intra-method, 정수 선형, 단일 필드. 다음은 보수적으로 skip한다
(false candidate는 어차피 `mutableFields` 투영에서 무시되므로 안전):

- 비선형식 (곱/나눗셈으로 인한 비선형, 비트연산 등)
- 다변수 동시해 (전체 입력 배정이 필요한 분기)
- 문자열 동치/접두사를 Z3 string theory로 푸는 것 (리터럴 문자열 동치는 `StaticLiteralOracle`이 담당)
- long 비교의 경계 edge, enum ordinal, 분기 간(interprocedural) 전파

자세한 증분·실증 표는 [docs/24](24-exploration-backends-and-input-oracle.md)의
"ConcolicOracle 지원 범위" 절을 참고. (도구가 못 따라가는 메서드는 그때까지 모은 비교만
사용하고 깔끔히 bail한다.)

> **아래 2~6절의 다섯 한계는 입력 *값* 도출과 무관하다.** ConcolicOracle은 분기를 여는
> 입력값을 푸는 것이고, 아래는 정적 분석이 **무엇이 실행되는지 자체를 볼 수 없는** 경우다.
> 어느 것도 ConcolicOracle로 해결되지 않으며, 모두 탐색 시 런타임 관측으로 메운다.

---

## 2. JPA derived-query 리포지토리 메서드

```java
public interface OwnerRepository extends JpaRepository<Owner, Integer> {
    List<Owner> findByLastNameStartingWith(String prefix);
    Optional<Owner> findByPhoneNumber(String phone);
}
```

- **AST 스캔이 보는 것:** 컨트롤러 애너테이션도, 메서드 바디도 없는 인터페이스. 핸들러
  0개, SQL 0개로 보고된다.
- **이유:** 실제 SQL은 메서드 이름에서 Spring Data가 런타임에 합성한다. 소스 트리에
  리터럴 SQL이 존재하지 않으므로 심볼 해석으로도 알 수 없다.
- **메우는 법:** 탐색이 라이브 SUT를 실제로 실행하면, `capture/SqlLogParser`가 SUT
  stdout 로그(Hibernate `org.hibernate.SQL` DEBUG + 바인딩 TRACE)에서 실행된 SQL과
  바인딩을 추출한다 (env 주입만으로 활성화, SUT 무수정). 해당 엔드포인트를 탐색이
  실제로 호출하기만 하면 `CapturedSql`이 채워진다.
- **무엇이 빨개지나:** 엔드포인트가 탐색으로 도달되면 아무 문제 없다. 도달하지 못하면
  (예: 특정 바디 shape가 있어야 dispatch) 그 분기가 `exploration-report.json`에
  미도달로 남는다.

## 3. MyBatis 동적 SQL (`<if>` / `<foreach>` / `<choose>`)

```xml
<select id="findActive" resultType="User">
  SELECT * FROM users WHERE deleted = 0
  <if test="role != null">AND role = #{role}</if>
  <foreach collection="ids" item="id" open="AND id IN (" separator="," close=")">
    #{id}
  </foreach>
</select>
```

- **AST 스캔이 보는 것:** `@Mapper` 인터페이스 메서드 — `@RequestMapping`이 없으니
  핸들러가 아니다.
- **이유:** SQL 형태가 입력에서 런타임에 계산된다. "이 메서드의 SQL" 하나를 AST가
  뽑을 수 없다.
- **메우는 법:** 탐색 시 캡처에 의존한다. `SqlLogParser`는 MyBatis 로그
  (`==> Preparing:` / `==> Parameters:`)에서 *실제 조립·실행된* SQL과 바인딩을
  기록한다.
- **무엇이 빨개지나:** 정적 스캔 자체로는 없다. 캡처된 SQL을 seed `INSERT`로 역산할
  깔끔한 방법이 없을 때 fixture 합성 쪽에서 통증이 나타난다.

## 4. `@Async` / `@Scheduled` 메서드

```java
@Service
public class NotificationService {
    @Async
    public CompletableFuture<Void> notifyAll(List<User> users) { ... }

    @Scheduled(cron = "0 */5 * * * *")
    public void cleanup() { ... }
}
```

- **AST 스캔이 보는 것:** 서비스 메서드 — `@RequestMapping`이 없으니 핸들러가 아니다.
  스캔 관점에서 이 메서드들은 보이지 않는다.
- **이유:** HTTP 진입점이 아니다. TaskExecutor·스케줄러 스레드에서 돈다. JaCoCo
  커버리지에는 나타나지만 귀속시킬 엔드포인트가 없다.
- **메우는 법:** REST 표면 밖이라 탐색으로 도달할 수 없다. 별도(단위 테스트, 수동
  트리거)로 다뤄야 한다.
- **무엇이 빨개지나:** REST로 도달 불가한 app 분기로 `exploration-report.json` 커버리지
  집계에 빠진 채로 남는다(수동 리뷰 대상).

## 5. Spring DI — 구현체 N개인 인터페이스 주입

```java
@RestController
public class PaymentController {
    private final PaymentGateway gateway;          // interface
    PaymentController(PaymentGateway gateway) { this.gateway = gateway; }
    @PostMapping("/charge")
    public void charge(@RequestBody ChargeRequest r) { gateway.charge(r); }
}

interface PaymentGateway { void charge(ChargeRequest r); }
@Service class StripeGateway implements PaymentGateway { ... }
@Service class PayPalGateway implements PaymentGateway { ... }
```

- **AST 스캔이 보는 것:** 컨트롤러 메서드는 정확히 본다.
- **보지 못하는 것:** 런타임에 어떤 `PaymentGateway` 구현체가 주입되는지, 따라서
  `charge()` 안의 어느 분기가 도달 가능한지. 정적으로는 컨트롤러 자신의 문장까지만이다.
- **이유:** Spring DI 바인딩은 런타임 — qualifier, `@Primary`, conditional config,
  profile 활성화가 모두 영향을 준다.
- **메우는 법:** 스캔은 의도적으로 주입 의존성을 따라 들어가지 않는다. 탐색이 *실제
  실행된* 경로를 JaCoCo로 캡처한다. 경로 식별은 요청별 probe 지문(`CoverageFingerprint`,
  SUT 자체 클래스 한정)이라 같은 라인의 다른 arm도 distinct path로 보존된다.
- **무엇이 빨개지나:** 탐색이 한 구현체만 깨우면 나머지 구현체의 분기는 미도달로 남는다.

## 6. 리플렉션 기반 dispatch

```java
@PostMapping("/handle/{type}")
public Object handle(@PathVariable String type, @RequestBody Map<String,Object> body)
        throws Exception {
    Class<?> cls = Class.forName("com.example.handlers." + type);
    Handler h = (Handler) cls.getDeclaredConstructor().newInstance();
    return h.process(body);
}
```

- **AST 스캔이 보는 것:** `handle()` 메서드 자체.
- **보지 못하는 것:** 어떤 `Handler` 구현체로 dispatch될지. `Class.forName(...)`이
  상수라 해도 그것을 따라가려면 의도적으로 생략한 심볼 해석이 필요하다.
- **이유:** 리플렉션은 정의상 런타임에 해소된다.
- **메우는 법:** 정적·탐색 자동화로는 닿지 않는다. **Manual-Archive Seed**가 탈출구다 —
  알려진 리플렉티브 dispatch 대상에 대해 `ExploredPath`를 손으로 작성해
  `--manual-paths` 디렉터리에 두면 `BuilderCli.mergeManualPaths`가 병합한다(id 충돌 시
  수동본 우선). 손으로 쓴 path도 path-id 보존 덕에 탐색이 캡처한 SQL과 동일하게 이어진다.

## 7. `@PathVariable` 없는 라우팅 전용 path placeholder

```java
@GetMapping("/a/b/c/{d}/{e}")
@ApiImplicitParams({@ApiImplicitParam(name = "d"), @ApiImplicitParam(name = "e")})
public AbcDTO abcDbyE(String d, String e) { ... }   // 파라미터에 @PathVariable 없음
```

- **AST 스캔이 보는 것:** `@GetMapping`은 본다. 하지만 핸들러 파라미터 `d`/`e`에
  `@PathVariable`이 없으므로 path 변수로 **캡처하지 않는다**(`EndpointIndexer.extractParams`는
  `@PathVariable`에만 의존). 같은 컨트롤러 어디에도 `@PathVariable`이 없으면 역추출
  타입 신호(`collectPathVarTypes`)도 비어 PATH 파라미터가 0개가 된다.
- **`@ApiImplicitParam`은 무관:** Swagger 문서화 전용 메타데이터로, 인덱서도 Spring
  런타임 바인딩도 무시한다. `@PathVariable`이 둘 다 있으면 `@ApiImplicitParams`가 끼어
  있어도 둘 다 정상 캡처된다(애너테이션 간섭 없음 — `index/` 테스트로 검증).
- **이유:** Spring 표준에서 애너테이션 없는 단순 타입 파라미터는 path variable이 아니라
  `@RequestParam`(쿼리) 기본 처리다. "템플릿 변수를 암묵적으로 path variable로 묶는"
  옵션은 Spring에 없다 — `@PathVariable` 애너테이션 자체가 필수(이름만 생략 가능). 위
  코드의 `{d}/{e}`는 **순수 라우팅 매칭용 placeholder**(아무 값이나 매칭)일 뿐 핸들러가
  값을 읽지 않는다.
- **무슨 일이 일어나나:** placeholder가 PATH 파라미터로 캡처되지 않으므로 URL 합성 시
  미바인딩으로 남는다. 캡처(`buildPathAndQuery`)·재현(`resolveLiteralPath`) **양쪽 모두**
  잔여 placeholder를 센티널("0")로 정리해 `/a/b/c/0/0`을 만든다(라우트는 어떤 값이든
  매칭되므로 의미상 정확, capture==reproduce 정합 유지). generator에 이 fallback이
  빠지면 다중 path 변수의 2번째 이후가 리터럴 `{e}`로 누출되어 RestAssured가
  `IllegalArgumentException: Invalid number of path parameters. expected 1, was 0`을
  던진다.
- **의도적으로 지원하지 않는 것:** 이름 매칭으로 미주석 파라미터를 PATH로 암묵 추론하는
  것은 Spring 실제 바인딩(`@RequestParam`)과 어긋나고 회귀 위험이 있어 도입하지 않는다.
  path 변수가 진짜 핸들러 입력이어야 하면 소스에 `@PathVariable`을 명시해야 한다(그러면
  인덱서가 캡처해 실제 값을 합성한다).

---

## 미도달 분기를 만났을 때

`exploration-report.json`에서 분기가 미도달로 남았다면 소스 위치부터 본다.

1. **JPA 리포지토리·`@Mapper` 인터페이스 안** → 2·3절. SQL은 캡처되지만 트리거
   엔드포인트가 탐색되지 않았을 수 있다. 도달 경로(필요 입력 shape)를 확인한다.
2. **`@Async` / `@Scheduled` 메서드** → 4절. REST 표면 밖이라 별도 단위 테스트가 필요.
3. **인터페이스 파라미터/의존성을 받는 메서드 안** → 5절. 특정 구현체를 깨우는 입력을
   찾거나, 수동 `ExploredPath`를 작성한다.
4. **리플렉션으로 dispatch** → 6절. 구현체별로 수동 path 작성.
5. **그 외** → 인덱서/오라클 버그일 수 있다. 실패하는 컨트롤러 shape를 인덱서 테스트
   (`index/` 테스트들)에 추가해 재현한다.

## 아직 범위 밖

- OpenAPI 스펙 ingestion — 발견 엔드포인트를 문서화된 계약과 교차검증. 실 SUT에서
  값어치가 확인되면 도입.
- Spring Security 분석으로 `Endpoint.authRequired` / `requiredRoles` 채우기. 현재
  인증은 탐색 설정의 per-step 헤더(`AuthConfig`)로 처리.
- 리플렉션 `Class.forName` 상수 폴딩 — edge case당 복잡도가 과해 보류.

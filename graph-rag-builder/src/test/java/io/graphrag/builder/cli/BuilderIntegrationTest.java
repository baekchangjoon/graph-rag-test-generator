package io.graphrag.builder.cli;

import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.run.AuthConfig;
import io.graphrag.model.BindingOrigin;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.GraphAsset;
import io.graphrag.model.RequiredSeed;
import io.graphrag.model.SqlBinding;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 order-service jar에 대한 빌더 전 사이클 (Phase 1: 탐색 + MyBatis). Docker 필요. */
@Tag("integration")
@EnabledIfSystemProperty(named = "sut.jar", matches = ".+")
class BuilderIntegrationTest {

    @TempDir
    Path out;

    @Test
    void build_exploresMultiplePathsAndCapturesBothOrms() throws Exception {
        Path sutSrc = Path.of(System.getProperty("sut.src"));
        Path sutJar = Path.of(System.getProperty("sut.jar"));
        Path sutResources = sutSrc.resolveSibling("resources");

        AuthConfig authConfig = new AuthConfig(
                "/api/auth/login", "admin", "password",
                "token", "Authorization", "Bearer", java.util.List.of());

        GraphAsset asset = BuilderCli.build(new BuildConfig(
                sutSrc, sutResources, sutJar, out,
                "order-service", "test",
                new DbConfig(DbConfig.Type.POSTGRES, "postgres:15", "app", "app", "app"),
                60, null,
                Path.of(System.getProperty("external.stubs")),
                java.util.Map.of("EXTERNAL_INVENTORY_URL", "{{wiremock}}"),
                null, null, authConfig, false, true, null,
                // 기본값은 otel로 전환됨(OtelKafkaBuildIntegrationTest가 otel full-build 커버).
                // 이 테스트는 log-parser 폴백 경로의 full-build 회귀 가드로 명시 고정한다.
                null, io.graphrag.model.RequestHeaders.empty(), java.util.List.of(), "none"));

        // auth 추가 + GET read-path 활성화로 인덱싱되는 엔드포인트 (id 정렬 순)
        assertThat(asset.endpoints()).extracting(e -> e.id())
                .containsExactly("delete-api-bookings-id", "get-api-bookings-id",
                        "get-api-orders", "get-api-orders-id",
                        "get-api-profiles-by-name-name",
                        "post-api-auth-login", "post-api-bookings",
                        "post-api-bookings-id-advance",
                        "post-api-orders",
                        "post-api-orders-batch", "post-api-orders-by-ids",
                        "post-api-orders-search", "post-api-pricing",
                        "post-api-promo", "post-api-signups",
                        "post-web-orders",
                        "post-web-users-userid-submit",
                        "put-api-bookings-id");

        // JPA endpoint: 201/404/400 path가 모두 발견된다 (Phase 1 메트릭의 핵심)
        List<ExploredPath> orderPaths = pathsOf(asset, "post-api-orders");
        assertThat(orderPaths.stream().map(ExploredPath::expectedStatus).distinct())
                .contains(201, 404, 400);

        // MyBatis endpoint: 200 + 400 path와 동적 SQL 캡처
        List<ExploredPath> searchPaths = pathsOf(asset, "post-api-orders-search");
        assertThat(searchPaths.stream().map(ExploredPath::expectedStatus).distinct())
                .contains(200, 400);
        ExploredPath searchHappy = searchPaths.stream()
                .filter(p -> p.expectedStatus() == 200).findFirst().orElseThrow();
        List<CapturedSql> searchSql = asset.sql().stream()
                .filter(s -> s.pathId().equals(searchHappy.id())).toList();
        assertThat(searchSql).anyMatch(s -> s.tableName().equals("orders")
                && s.sqlKind().equals("SELECT"));

        // origin 판정은 Phase 0과 동일하게 유지된다
        ExploredPath orderHappy = orderPaths.stream()
                .filter(p -> p.expectedStatus() == 201).findFirst().orElseThrow();
        CapturedSql insert = asset.sql().stream()
                .filter(s -> s.pathId().equals(orderHappy.id())
                        && s.sqlKind().equals("INSERT") && s.tableName().equals("orders"))
                .findFirst().orElseThrow();
        assertThat(insert.bindings())
                .filteredOn(b -> b.column().equals("status"))
                .extracting(SqlBinding::origin).containsExactly(BindingOrigin.LITERAL);

        // 제약-aware happy 회귀 가드(Feature A, 빌더 레벨): POST /api/bookings 는 다중 명령형 가드
        // (nights 1..30, tier 필수, VIP&&loyalty>=500 conjunction, email 정규식, checkInDate 미래)를
        // 모두 통과해야 201. 제약-aware happy가 깨지면 422만 남아 201 path가 사라진다(= 회귀 시 FAIL).
        List<ExploredPath> bookingPaths = pathsOf(asset, "post-api-bookings");
        assertThat(bookingPaths.stream().map(ExploredPath::expectedStatus).distinct())
                .contains(201);
        ExploredPath bookingHappy = bookingPaths.stream()
                .filter(p -> p.expectedStatus() == 201).findFirst().orElseThrow();
        assertThat(asset.sql().stream().filter(s -> s.pathId().equals(bookingHappy.id())))
                .anyMatch(s -> s.sqlKind().equals("INSERT") && s.tableName().equals("bookings"));
        // Stage 4 inter-field 가드(Z3 solveTuple): 201 입력은 loyaltyPoints == nights*600+7 을 만족해야 한다.
        // 필드별 경계/large 변이로는 두 필드 동시충족 불가 → solveTuple이 푼 튜플(607,1)만 201을 연다.
        // (solver 회귀 시 201 자체가 사라져 위 contains(201)이 먼저 FAIL — 이 단언은 '솔버 덕'임을 못박는다.)
        var bookingInput = bookingHappy.sampleInput();
        assertThat(bookingInput.has("loyaltyPoints") && bookingInput.has("nights")).isTrue();
        assertThat(bookingInput.get("loyaltyPoints").asLong())
                .isEqualTo(bookingInput.get("nights").asLong() * 600 + 7);

        // float inter-field 가드(작업 #4, Real solveTuple): POST /api/pricing 의 201 입력은 두 float 필드가
        // band 99.5 <= base*2 + surcharge*3 <= 100.5 를 만족해야 한다. 한 필드만 바꾸는 generic 변이로는
        // 두 필드 동시 band 진입 불가 → Real solveTuple이 푼 (base, surcharge) 튜플만 201을 연다.
        // (solver 회귀/oracle off 시 201 자체가 사라져 contains(201)이 먼저 FAIL — '솔버 덕'을 못박는다.)
        List<ExploredPath> pricingPaths = pathsOf(asset, "post-api-pricing");
        assertThat(pricingPaths.stream().map(ExploredPath::expectedStatus).distinct())
                .contains(201);
        ExploredPath pricingHappy = pricingPaths.stream()
                .filter(p -> p.expectedStatus() == 201).findFirst().orElseThrow();
        var pricingInput = pricingHappy.sampleInput();
        assertThat(pricingInput.has("base") && pricingInput.has("surcharge")).isTrue();
        double combined = pricingInput.get("base").asDouble() * 2.0
                + pricingInput.get("surcharge").asDouble() * 3.0;
        assertThat(combined).isBetween(99.5, 100.5);

        // 분기/엔진/제약 메타데이터
        assertThat(orderHappy.branchesTaken()).isNotEmpty();
        assertThat(orderHappy.discoveredBy()).isIn("heuristic", "fuzzer");
        assertThat(orderPaths.stream().filter(p -> p.expectedStatus() == 400).findFirst()
                .orElseThrow().constraints())
                .anyMatch(c -> c.contains("userId() == null"));

        // Phase 2: EXPRESS 분기 → 외부 HTTP 캡처 (literal 변이로 도달)
        List<ExploredPath> expressPaths = orderPaths.stream()
                .filter(p -> !p.capturedHttpCallIds().isEmpty()).toList();
        assertThat(expressPaths).isNotEmpty();
        assertThat(orderPaths.stream().map(ExploredPath::expectedStatus)).contains(409);
        var httpCall = asset.httpCalls().stream()
                .filter(c -> c.pathId().equals(expressPaths.get(0).id()))
                .findFirst().orElseThrow();
        assertThat(httpCall.method()).isEqualTo("GET");
        assertThat(httpCall.urlPath()).isEqualTo("/inventory/stock");
        assertThat(httpCall.query()).containsEntry("type", "EXPRESS");
        assertThat(httpCall.responseBody()).contains("available");
        assertThat(httpCall.consumedFields()).containsExactly("available");
        // OTEL javaagent가 inbound baggage를 outbound로 전파했다 (docs/06 격리 기반)
        assertThat(httpCall.baggagePropagated()).isTrue();

        // Phase 3: STOMP endpoint + 메시지 교환 캡처 (happy/missing-ref)
        // (컬렉션 WS endpoint ws-orders-count-batch가 추가됨 — scalar happy 가드는 ws-orders-count로 한정)
        assertThat(asset.wsEndpoints()).extracting(w -> w.id())
                .contains("ws-orders-count", "ws-orders-count-batch");
        var wsExchanges = asset.wsExchanges().stream()
                .filter(e -> e.wsEndpointId().equals("ws-orders-count")).toList();
        assertThat(wsExchanges).hasSize(2);
        var wsHappy = wsExchanges.get(0);
        assertThat(wsHappy.payload().get("userId").asText()).isEqualTo("probe-userId");
        assertThat(wsHappy.response().get("userId").asText()).isEqualTo("probe-userId");
        assertThat(wsHappy.response().has("count")).isTrue();
        // WS 핸들러의 파생 쿼리 SQL도 캡처된다
        assertThat(asset.sql().stream().filter(s -> s.pathId().equals(wsHappy.id())))
                .anyMatch(s -> s.sqlKind().equals("SELECT") && s.tableName().equals("orders")
                        && s.bindings().stream().anyMatch(b ->
                                b.origin() == BindingOrigin.API_PARAM
                                        && b.value().equals("probe-userId")));

        // read-path: GET /api/orders/{id} 가 탐색되어 2xx path + FK 부모 시드를 남긴다 (C#3)
        List<ExploredPath> getByIdPaths = pathsOf(asset, "get-api-orders-id");
        assertThat(getByIdPaths).isNotEmpty();
        ExploredPath getByIdHappy = getByIdPaths.stream()
                .filter(p -> p.expectedStatus() / 100 == 2).findFirst().orElseThrow();
        // 2xx로 도달하려면 대상 order + 그 FK 부모 user가 시드되어 있어야 한다
        List<RequiredSeed> getByIdSeeds = asset.seeds().stream()
                .filter(s -> s.pathId().equals(getByIdHappy.id())).toList();
        assertThat(getByIdSeeds).isNotEmpty();
        assertThat(getByIdSeeds).extracting(RequiredSeed::table)
                .contains("orders", "users");
        // GET path는 read이므로 INSERT가 아닌 SELECT SQL을 캡처한다
        assertThat(asset.sql().stream().filter(s -> s.pathId().equals(getByIdHappy.id())))
                .anyMatch(s -> s.sqlKind().equals("SELECT") && s.tableName().equals("orders"));

        // Kafka consumer 회귀 가드: @KafkaListener(order.events) 인덱싱 + raw String payload의 내부
        // readValue(OrderEventPayload) 타깃 해석 + 발행 후 consumer가 order_events INSERT(SQL 캡처).
        assertThat(asset.kafkaConsumers()).extracting(c -> c.id()).contains("kafka-order-events");
        var orderEventConsumer = asset.kafkaConsumers().stream()
                .filter(c -> c.id().equals("kafka-order-events")).findFirst().orElseThrow();
        assertThat(orderEventConsumer.topic()).isEqualTo("order.events");
        assertThat(orderEventConsumer.payloadType()).contains("OrderEventPayload");   // readValue 타깃 해석
        var orderEventExchange = asset.kafkaExchanges().stream()
                .filter(e -> e.kafkaConsumerId().equals("kafka-order-events") && !e.variant())
                .findFirst().orElseThrow();
        assertThat(asset.sql().stream().filter(s -> orderEventExchange.capturedSqlIds().contains(s.id())))
                .anyMatch(s -> s.sqlKind().equals("INSERT") && s.tableName().equals("order_events"));

        // Kafka 양-arm: happy 뒤에 반대-arm 변종(결측-필드 등)을 발행해 consumer 가드의 미커버 arm을 연다.
        // 하드 게이트(결정적): happy(variant=false, INSERT) 정확히 1개 + 변종(variant=true) ≥1개이고,
        // 변종은 반대-arm(스킵/리턴)이라 **INSERT를 만들지 않는다**(dedup의 existsById SELECT는 허용).
        // 되돌리면(happy만) 변종 0 → FAIL. (변종은 생성에서 제외되므로 B2 무영향.)
        List<io.graphrag.model.KafkaExchange> orderEventExchanges = asset.kafkaExchanges().stream()
                .filter(e -> e.kafkaConsumerId().equals("kafka-order-events")).toList();
        assertThat(orderEventExchanges).filteredOn(e -> !e.variant()).hasSize(1);   // happy 1개
        List<io.graphrag.model.KafkaExchange> variants = orderEventExchanges.stream()
                .filter(io.graphrag.model.KafkaExchange::variant).toList();
        assertThat(variants).isNotEmpty();                                          // 변종 ≥1개(최소 missing-field)
        assertThat(variants).allSatisfy(v -> assertThat(asset.sql().stream()
                .filter(s -> v.capturedSqlIds().contains(s.id())))
                .noneMatch(s -> s.sqlKind().equals("INSERT")));                      // 반대-arm = INSERT 없음

        // 시드 타깃 해석(SQL-기반 2-pass) 회귀 가드: resource명("profiles")≠table명("users")이고
        // 비-PK 컬럼 name 으로 조회 → path-string 휴리스틱은 테이블을 못 찾는다. 빌더가 캡처한
        // SELECT(from users where name=?)로 users 를 시드해야 2xx read 데이터가 나온다.
        List<ExploredPath> profilePaths = pathsOf(asset, "get-api-profiles-by-name-name");
        assertThat(profilePaths).isNotEmpty();
        List<RequiredSeed> profileSeeds = asset.seeds().stream()
                .filter(s -> profilePaths.stream().anyMatch(p -> p.id().equals(s.pathId())))
                .toList();
        // SQL-기반 해석이 동작해야 휴리스틱이 못 찾은 users 테이블이 시드된다 (회귀 시 빈 seed)
        assertThat(profileSeeds).isNotEmpty();
        assertThat(profileSeeds).extracting(RequiredSeed::table).contains("users");
        // 비-PK 컬럼 name 으로 조회하는 SELECT from users 가 캡처된다
        ExploredPath profileHappy = profilePaths.stream()
                .filter(p -> p.expectedStatus() / 100 == 2).findFirst().orElseThrow();
        assertThat(asset.sql().stream().filter(s -> s.pathId().equals(profileHappy.id())))
                .anyMatch(s -> s.sqlKind().equals("SELECT") && s.tableName().equals("users")
                        && s.bindings().stream().anyMatch(b -> b.column().equals("name")));

        // Stage 4 양-arm 시드 가드(StateGuardOracle): 저장된 단일 행 상태로 갈리는 가드의 반대 arm을
        // 대체 시드 변종으로 연다. 회귀(HTTP 탐색만)로 되돌아가면 변종 시드/arm이 사라져 FAIL.
        // GET stale 가드(check_in_date.isBefore(now)): 과거날짜(1900-01-01) 변종 행 → 404 stale arm.
        RequiredSeed staleSeed = asset.seeds().stream()
                .filter(s -> s.table().equals("bookings") && s.values().contains("1900-01-01"))
                .findFirst().orElseThrow();
        ExploredPath stalePath = pathsOf(asset, "get-api-bookings-id").stream()
                .filter(p -> p.id().equals(staleSeed.pathId())).findFirst().orElseThrow();
        assertThat(stalePath.expectedStatus()).isEqualTo(404);     // 과거 행 + includeStale=false → stale 404
        // 미래날짜 happy 행(2xx arm)도 함께 존재 → 두 arm 모두 시드됨
        assertThat(pathsOf(asset, "get-api-bookings-id"))
                .anyMatch(p -> p.expectedStatus() / 100 == 2);

        // DELETE conflict 가드(status != PENDING && != CANCELLED): CONFIRMED 변종 행 + confirm=true → 409.
        RequiredSeed confirmedSeed = asset.seeds().stream()
                .filter(s -> s.table().equals("bookings") && s.values().contains("CONFIRMED"))
                .findFirst().orElseThrow();
        ExploredPath conflictPath = pathsOf(asset, "delete-api-bookings-id").stream()
                .filter(p -> p.id().equals(confirmedSeed.pathId())).findFirst().orElseThrow();
        assertThat(conflictPath.expectedStatus()).isEqualTo(409);
        assertThat(conflictPath.sampleInput().get("confirm").asBoolean()).isTrue();   // 게이팅 검증

        // 작업 #5 상태머신 다중 전이: POST /api/bookings/{id}/advance 는 status 명시 == 로 세 arm(200/409/410).
        // 빌더가 EQ 가드(positive={PENDING,CONFIRMED,CANCELLED})를 추출하고 각 상태 변종 시드로 세 arm을 모두
        // 캡처해야 한다. 단일 변종으로 되돌리면 happy 상태 1 arm만 → 단언 FAIL.
        List<ExploredPath> advancePaths = pathsOf(asset, "post-api-bookings-id-advance");
        assertThat(advancePaths.stream().map(ExploredPath::expectedStatus).distinct())
                .contains(200, 409, 410);
        // 409·410 arm은 각각 CONFIRMED·CANCELLED 변종 시드 행에서 비롯한다(다중 변종 증거).
        assertThat(asset.seeds().stream()
                .filter(s -> advancePaths.stream().anyMatch(p -> p.id().equals(s.pathId())))
                .filter(s -> s.table().equals("bookings"))
                .flatMap(s -> s.values().stream()))
                .contains("CONFIRMED", "CANCELLED");

        // 부정-인증 경로: auth-required 엔드포인트(get-api-orders)에 무효 토큰 1회 발행 → JWT 필터 거부 arm
        // (JwtAuthFilter validate→false + JwtUtil.validate catch). discoveredBy="negative-auth" 4xx path 캡처.
        // 되돌리면(happy valid-token만) 그 path 없음 → FAIL. (생성 제외라 B2/run-e2e 무영향.)
        ExploredPath negAuthPath = pathsOf(asset, "get-api-orders").stream()
                .filter(p -> p.discoveredBy().equals("negative-auth")).findFirst().orElseThrow();
        assertThat(negAuthPath.expectedStatus()).isIn(401, 403);

        // 부정-검증 경로(B1): @Valid @RequestBody(SignupRequest)의 각 제약(@NotBlank/@Email/@Min/@Size)을
        // 한 필드만 위반시킨 변종을 발행 → MethodArgumentNotValidException 400. discoveredBy="negative-validation".
        // 되돌리면(어노테이션 위반 변종 미발행) happy 201만 → reject path 0 → FAIL. (생성 제외라 B2 무영향.)
        List<ExploredPath> signupPaths = pathsOf(asset, "post-api-signups");
        List<ExploredPath> negValPaths = signupPaths.stream()
                .filter(p -> p.discoveredBy().equals("negative-validation")).toList();
        // 필드별(name/email/age/password) 위반 path가 ≥4개, 모두 400.
        assertThat(negValPaths).hasSizeGreaterThanOrEqualTo(4);
        assertThat(negValPaths).allSatisfy(p -> assertThat(p.expectedStatus()).isEqualTo(400));
        // path-id가 필드+제약종류로 결정적으로 식별된다(고유·결정적).
        assertThat(negValPaths).extracting(ExploredPath::id)
                .contains("post-api-signups-negval-name-not_blank",
                        "post-api-signups-negval-email-email",
                        "post-api-signups-negval-age-min",
                        "post-api-signups-negval-password-size_min");
        // happy 201 path도 함께 존재(어노테이션-aware happy 합성이 모든 제약을 통과).
        assertThat(signupPaths).anyMatch(p -> p.expectedStatus() == 201);

        // @Controller 폼 인덱싱 회귀 가드: OrderWebController(@Controller, @RestController 아님)의
        // POST /web/orders가 form-urlencoded 커맨드 객체(OrderForm)로 인덱싱되고, 빌더가 폼 인코딩으로
        // 탐색해 명령형 가드(quantity 1..100)의 **양 arm**을 모두 연다. 둘 다 302 redirect(/ok vs /error).
        // 핵심: ok arm은 quantity가 [1,100]으로 폼 바인딩됐을 때만 도달 → JSON으로 보냈으면 바인딩 실패로
        // quantity=null이 되어 error arm만 나온다. 따라서 ≥2개의 구별된 path(양 arm) 존재 자체가
        // form-urlencoded 전송이 올바름을 증명한다. @Controller 미인덱싱으로 되돌리면 endpoint 자체가 사라져 FAIL.
        io.graphrag.model.Endpoint webOrders = asset.endpoints().stream()
                .filter(e -> e.id().equals("post-web-orders")).findFirst().orElseThrow();
        assertThat(webOrders.params()).extracting(io.graphrag.model.EndpointParam::kind)
                .containsExactly(io.graphrag.model.ParamKind.FORM);
        assertThat(webOrders.params().get(0).javaType())
                .isEqualTo("io.graphrag.sample.orders.OrderWebController$OrderForm");
        // valid-token 탐색 path만(authRequired라 negative-auth 무효-토큰 403 path도 함께 생기므로 제외).
        List<ExploredPath> webValidPaths = pathsOf(asset, "post-web-orders").stream()
                .filter(p -> !p.discoveredBy().equals("negative-auth")).toList();
        // 양 arm: 분기 집합이 다른 valid path가 ≥2개. 모두 302(Java HttpClient 기본 redirect=NEVER로 미추적).
        // ok arm(redirect:/ok)은 quantity가 [1,100]으로 폼 바인딩됐을 때만 도달 — JSON 전송이면 바인딩 실패로
        // quantity=null이 되어 error arm만 나온다. 즉 ≥2개의 구별된 분기 집합 존재가 form-urlencoded 정합 증명.
        assertThat(webValidPaths.stream().map(ExploredPath::branchesTaken).distinct().count())
                .isGreaterThanOrEqualTo(2L);
        assertThat(webValidPaths.stream().map(ExploredPath::expectedStatus).distinct())
                .containsExactly(302);

        // 작업 a 회귀 가드 — 클래스-레벨 path 변수 @ModelAttribute 역추출: UserOrderWebController
        // (@Controller, @RequestMapping("/web/users/{userId}"))의 {userId}는 핸들러 파라미터가 아니라
        // @ModelAttribute findUser(@PathVariable userId)에서만 해석된다. 빌더가 이를 PATH로 역추출해야
        // userId가 users 행으로 시드되고 findUser가 성공해 폼 핸들러에 진입한다(양 arm).
        io.graphrag.model.Endpoint webUserOrders = asset.endpoints().stream()
                .filter(e -> e.id().equals("post-web-users-userid-submit")).findFirst().orElseThrow();
        // userId는 FORM이 아니라 PATH로 잡혀야 한다(역추출 성공). + 폼 커맨드(OrderForm) FORM.
        assertThat(webUserOrders.params()).filteredOn(p -> p.name().equals("userId"))
                .extracting(io.graphrag.model.EndpointParam::kind)
                .containsExactly(io.graphrag.model.ParamKind.PATH);
        // userId PATH가 시드한 users 행이 존재(findUser 성공의 증거 — 미역추출 시 센티널 → orElseThrow 5xx).
        List<RequiredSeed> webUserSeeds = asset.seeds().stream()
                .filter(s -> pathsOf(asset, "post-web-users-userid-submit").stream()
                        .anyMatch(p -> p.id().equals(s.pathId())))
                .toList();
        assertThat(webUserSeeds).extracting(RequiredSeed::table).contains("users");
        // valid-token path(negative-auth 제외)가 분기 집합 다른 ≥2개 + 모두 302 — findUser 성공 후 amount 양 arm.
        // 역추출 미적용으로 되돌리면 userId 센티널 → findUser orElseThrow 5xx → valid path 1개(또는 폼 미진입) → FAIL.
        List<ExploredPath> webUserValidPaths = pathsOf(asset, "post-web-users-userid-submit").stream()
                .filter(p -> !p.discoveredBy().equals("negative-auth")).toList();
        assertThat(webUserValidPaths.stream().map(ExploredPath::branchesTaken).distinct().count())
                .isGreaterThanOrEqualTo(2L);
        assertThat(webUserValidPaths.stream().map(ExploredPath::expectedStatus).distinct())
                .containsExactly(302);

        // MyBatis mapper 사실 + still_missing 리포트
        assertThat(asset.mappers()).extracting(m -> m.statementId()).contains("search");
        assertThat(Files.exists(out.resolve("exploration-report.json"))).isTrue();
        String report = Files.readString(out.resolve("exploration-report.json"));
        assertThat(report).contains("post-api-orders").contains("totalBranches");
        // consumer 커버리지가 exploration 지표에 반영된다(F1-F3): @KafkaListener 발행 후 consumer가
        // 실행되면 그 클래스 분기가 run-wide covered 집합(coveredAppClasses)에 들어와야 한다.
        // HTTP 탐색만 집계하던 회귀로 되돌아가면 consumer 클래스가 빠져 FAIL.
        assertThat(report).contains("io.graphrag.sample.orders.OrderEventConsumer");
    }

    private static List<ExploredPath> pathsOf(GraphAsset asset, String endpointId) {
        return asset.paths().stream().filter(p -> p.endpointId().equals(endpointId)).toList();
    }
}

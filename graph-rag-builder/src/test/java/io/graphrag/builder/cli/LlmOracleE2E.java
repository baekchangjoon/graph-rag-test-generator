package io.graphrag.builder.cli;

import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.run.AuthConfig;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.GraphAsset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LLM 값 오라클 E2E — @Pattern 게이트 CouponController(REQ-011/012). 커밋된 캐시("GOLD-1234")로
 * API 무호출·결정적. off: gold-tier 깊은 분기 미도달 / on: 도달(커버리지 증가). Docker 필요.
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "sut.jar", matches = ".+")
class LlmOracleE2E {

    @TempDir
    Path out;

    private GraphAsset build(boolean llmOracle) throws Exception {
        Path sutSrc = Path.of(System.getProperty("sut.src"));
        Path sutJar = Path.of(System.getProperty("sut.jar"));
        Path sutResources = sutSrc.resolveSibling("resources");
        AuthConfig authConfig = new AuthConfig("/api/auth/login", "admin", "password",
                "token", "Authorization", "Bearer", List.of());
        return BuilderCli.build(new BuildConfig(
                sutSrc, sutResources, sutJar, out,
                "order-service", "test",
                new DbConfig(DbConfig.Type.POSTGRES, "postgres:15", "app", "app", "app"),
                60, null,
                Path.of(System.getProperty("external.stubs")),
                Map.of("EXTERNAL_INVENTORY_URL", "{{wiremock}}"),
                null, null, authConfig, false, true, null,
                null, io.graphrag.model.RequestHeaders.empty(), List.of(), "none", null, false, true,
                new io.graphrag.builder.oracle.LlmOptions(llmOracle, "claude-haiku-4-5-20251001", "api", "claude")));
    }

    private static List<ExploredPath> couponPaths(GraphAsset asset) {
        return asset.paths().stream()
                .filter(p -> p.endpointId().equals("post-api-coupons")).toList();
    }

    /** gold-tier 깊은 분기 도달 신호 = CouponController.redeem 분기가 covered(branchesTaken). */
    private static boolean reachesRedeemBranch(ExploredPath p) {
        return p.branchesTaken().stream().anyMatch(b ->
                b.classFqn().equals("io.graphrag.sample.orders.CouponController")
                        && b.method().equals("redeem"));
    }

    @Test
    @DisplayName("REQ-011: --llm-oracle off 경로 — gold-tier 깊은 분기 미도달")
    void offPathDoesNotReachGoldBranch() throws Exception {
        List<ExploredPath> coupon = couponPaths(build(false));
        assertThat(coupon).isNotEmpty();                       // 엔드포인트는 탐색됨
        assertThat(coupon).noneMatch(LlmOracleE2E::reachesRedeemBranch);   // 깊은 분기 미도달
        assertThat(coupon).noneMatch(p -> p.expectedStatus() == 200);      // 성공 경로 없음
    }

    @Test
    @DisplayName("REQ-012: 캐시된 LLM on — gold-tier 깊은 분기 도달(API 무호출, 커버리지 증가)")
    void cachedLlmOnReachesGoldBranch() throws Exception {
        List<ExploredPath> coupon = couponPaths(build(true));
        // 캐시값 "GOLD-1234"가 @Pattern + startsWith("GOLD")를 통과해 redeem 깊은 분기에 도달.
        assertThat(coupon).anyMatch(p -> p.expectedStatus() == 200
                && reachesRedeemBranch(p));
    }
}

package io.graphrag.builder.coverage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * pjacoco includes 패키지 탐지 — SUT 앱 루트로 좁혀 OTel/서드파티 계측을 배제하는지 검증.
 *
 * <p>P2-5 근본원인: 첫 세그먼트만 취한 {@code io.*}가 {@code io.opentelemetry.*}(OTel span-export)
 * 까지 계측 → 병렬에서 export starve → SQL timeout. 단일 자식 체인 하강으로 앱 루트를 탐지해야 한다.
 */
class PjacocoAgentTest {

    private static void mkpkg(Path src, String pkgPath, String... javaFiles) throws IOException {
        Path dir = src.resolve(pkgPath);
        Files.createDirectories(dir);
        for (String f : javaFiles) {
            Files.writeString(dir.resolve(f), "// test");
        }
    }

    @Test
    void detectRootPackage_descendsSingleChildChainToAppRoot(@TempDir Path src) throws IOException {
        // src/main/java/io/graphrag/sample/orders/{OrderController.java, auth/}
        mkpkg(src, "io/graphrag/sample/orders", "OrderController.java");
        mkpkg(src, "io/graphrag/sample/orders/auth", "JwtFilter.java");

        // 첫 세그먼트(io.*)가 아니라 앱 루트까지 하강해야 한다 → io.opentelemetry.* 등 제외.
        assertThat(PjacocoAgent.detectRootPackage(src)).isEqualTo("io.graphrag.sample.orders.*");
    }

    @Test
    void detectRootPackage_stopsAtBranchPoint(@TempDir Path src) throws IOException {
        // io/graphrag/sample 아래 두 앱 패키지(orders, shared) → 공통 prefix에서 멈춘다.
        mkpkg(src, "io/graphrag/sample/orders", "OrderController.java");
        mkpkg(src, "io/graphrag/sample/shared", "Util.java");

        assertThat(PjacocoAgent.detectRootPackage(src)).isEqualTo("io.graphrag.sample.*");
    }

    @Test
    void detectRootPackage_stopsWhenDirHasJavaFiles(@TempDir Path src) throws IOException {
        // io/example 에 코드가 직접 있으면 거기서 멈춘다(자식 패키지가 있어도).
        mkpkg(src, "io/example", "App.java");
        mkpkg(src, "io/example/web", "Controller.java");

        assertThat(PjacocoAgent.detectRootPackage(src)).isEqualTo("io.example.*");
    }

    @Test
    void detectRootPackage_nullForMissingOrEmpty(@TempDir Path src) {
        assertThat(PjacocoAgent.detectRootPackage(src.resolve("nope"))).isNull();
        assertThat(PjacocoAgent.detectRootPackage(null)).isNull();
    }

    @Test
    void normalizeIncludes_explicitWins() {
        assertThat(PjacocoAgent.normalizeIncludes("io.graphrag.*", "io.detected.*"))
                .isEqualTo("io.graphrag.*");
    }

    @Test
    void normalizeIncludes_globFallsBackToDetected() {
        assertThat(PjacocoAgent.normalizeIncludes("**/*", "io.graphrag.sample.orders.*"))
                .isEqualTo("io.graphrag.sample.orders.*");
    }

    /**
     * 컨테이너 JTO는 제어 서버를 0.0.0.0 에 바인드해야 Docker published 포트(host→container)가 도달한다.
     * 기본(127.0.0.1)이면 컨테이너 loopback에만 listen → published 포트 forward가 EOF로 실패(attach e2e 회귀).
     * 로컬(호스트 프로세스) JTO는 loopback 기본이라 address 옵션을 넣지 않는다.
     */
    @Test
    void containerJto_bindsControlServerToAllInterfaces(@TempDir Path work) throws IOException {
        mkpkg(work.resolve("src"), "io/graphrag/sample/orders", "OrderController.java");
        PjacocoAgent agent = PjacocoAgent.prepare(work);

        String containerJto = agent.containerJavaToolOptions(
                "/grb-agents/pjacoco-agent.jar", Path.of("/grb-pjacoco-exec"),
                6300, null, work.resolve("src"), true);
        assertThat(containerJto).contains("port=6300").contains("address=0.0.0.0");

        String localJto = agent.javaToolOptions(
                work.resolve("exec"), 6300, null, work.resolve("src"), true);
        assertThat(localJto).contains("port=6300").doesNotContain("address=");
    }
}

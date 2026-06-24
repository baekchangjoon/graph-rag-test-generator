package io.graphrag.builder.capture;

import com.sun.net.httpserver.HttpServer;
import io.graphrag.builder.cli.BuildConfig;
import io.graphrag.builder.cli.BuilderCli;
import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.run.AuthConfig;
import io.graphrag.model.CapturedHttpCall;
import io.graphrag.model.GraphAsset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-F012-015: sleuth 모드(order-web)에서 외부 호출의 응답 body가 CONTRACT가 아님을 단언 (red).
 *
 * <p>order-web(Boot 2.7/Sleuth/Brave)은 {@code postForEntity(..., Void.class)}를 사용해 응답 body를
 * 읽지 않는다. 따라서 소비 코드 기대값 추출이 불가능하며 CONTRACT provenance는 부여되지 않아야 한다.
 * 거짓 CONTRACT를 방지하는 것이 이 E2E의 핵심이다.
 *
 * <p>단언:
 * <ul>
 *   <li>order-web build 후 외부 호출 {@code httpCalls} 항목의 {@code responseProvenance != CONTRACT}
 *       (빈/SYNTHESIZED body).</li>
 * </ul>
 *
 * <p>구현(Task 2~8) 전까지 RED가 정상이며 약화 금지.
 *
 * <p>필요 조건: {@code -Dsut.jar} (order-web jar), {@code -Dsut.src} (order-web 소스 루트) 둘 다 지정.
 * 미충족 시 skip. order-web은 MySQL(Testcontainers)이 필요하다 — 해당 인프라가 가용한 환경에서만 실행된다.
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "sut.jar", matches = ".+")
@EnabledIfSystemProperty(named = "sut.src", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EgressStubBodyFidelitySleuthAbstainE2E {

    // order-web이 호출하는 reservation 외부 stub: POST /reservations → 202
    private static final String RESERVATION_PATH = "/reservations";

    @TempDir
    Path out;

    // 이 테스트가 기동한 host stub (REQ-F012-016: teardown 필수)
    private HttpServer reservationStub;
    private String reservationUrl;

    @BeforeAll
    void startReservationStub() throws Exception {
        reservationStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        reservationStub.createContext(RESERVATION_PATH, exchange -> {
            // Void.class 응답 — order-web은 body를 소비하지 않는다.
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        reservationStub.start();
        reservationUrl = "http://127.0.0.1:" + reservationStub.getAddress().getPort();
    }

    @AfterAll
    void stopReservationStub() {
        // 이 테스트가 띄운 host stub만 정리 (REQ-F012-016 teardown).
        // SUT 프로세스·MySQL 컨테이너 수명은 BuilderCli.build 내부(Testcontainers Ryuk)가 관리한다.
        if (reservationStub != null) {
            reservationStub.stop(0);
        }
    }

    @Test
    @DisplayName("REQ-F012-015: sleuth Void abstain — 거짓 CONTRACT 없음")
    void sleuthVoidResponse_doesNotYieldContractProvenance() throws Exception {
        // order-web 소스·jar: -Dsut.src / -Dsut.jar 는 order-web을 가리켜야 한다.
        // (buildOrderWebJar 방식은 SleuthEgressDiscoveryE2E 참고 — 여기서는 jar가 이미 제공되었다고 가정.)
        Path sutSrc = Path.of(System.getProperty("sut.src"));
        Path sutJar = Path.of(System.getProperty("sut.jar"));
        Path sutResources = sutSrc.resolveSibling("resources");
        Path buildOut = Files.createTempDirectory(out, "build");
        Path noExternalStubs = Files.createTempDirectory(out, "no-stubs");

        // order-web은 auth가 없다.
        // sleuth 모드: traceMode="sleuth" — OtelAgent 미부착, ZipkinSpanReceiver 기동.
        // RESERVATION_URL = 직접 host stub URL.
        GraphAsset asset = BuilderCli.build(new BuildConfig(
                sutSrc, sutResources, sutJar, buildOut,
                "order-web", "test",
                new DbConfig(DbConfig.Type.MYSQL, "mysql:8.0", "orderdb", "app", "apppw"),
                60, null, noExternalStubs,
                Map.of("RESERVATION_URL", reservationUrl),
                null, null,
                null,   // auth 없음
                false, false, null,
                null, io.graphrag.model.RequestHeaders.empty(), List.of(),
                "sleuth", null, false));

        // ── 단언: 외부 호출 httpCalls 항목에 CONTRACT provenance가 없어야 한다 ──────────
        // order-web은 Void.class 응답이므로 소비 코드 기대값을 추출할 수 없다 → CONTRACT 불가.
        List<CapturedHttpCall> reservationCalls = asset.httpCalls().stream()
                .filter(c -> c.urlPath().contains(RESERVATION_PATH))
                .toList();

        // reservation 외부 호출이 그래프에 기록됐는지 우선 확인(빌더가 sleuth 발견을 해야 함).
        assertThat(reservationCalls)
                .as("sleuth 모드에서 POST /reservations 외부 호출이 그래프에 기록돼야 한다")
                .isNotEmpty();

        // 핵심: 어떤 외부 호출도 CONTRACT provenance를 가지면 안 된다.
        boolean anyContract = reservationCalls.stream()
                .anyMatch(c -> c.responseProvenance() == CapturedHttpCall.Provenance.CONTRACT);
        assertThat(anyContract)
                .as("Void 응답(소비 코드 없음)이므로 CONTRACT provenance가 부여되지 않아야 한다(거짓 CONTRACT 방지)")
                .isFalse();
    }
}

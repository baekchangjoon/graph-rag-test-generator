package io.graphrag.builder.run;

import io.graphrag.builder.env.HttpCaptureServer;
import io.graphrag.builder.explore.RawHttpExchange;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.ExternalCallSite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B2 재탐색 루프의 핵심: unmatched(404) 외부 호출 → 매칭 → 합성·등록(멱등) (REQ-008, REQ-014).
 * 루프 자체는 EndpointExplorationRunner.synthesizeStubsForUnmatched 헬퍼로 추출해 SUT/DB 없이 검증한다.
 */
class ExternalStubReExploreTest {

    private HttpCaptureServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private ExternalStubSynthesizer synthesizer() {
        server = new HttpCaptureServer();
        server.start(null, null);
        return new ExternalStubSynthesizer(server, new ShapeJsonSynthesizer(Map.of()));
    }

    private static RawHttpExchange ex(String method, String path, int status) {
        return new RawHttpExchange(method, path, Map.of(), null, status, "", false, "");
    }

    private static final BodyShape INV_SHAPE = new BodyShape("InventoryResponse",
            List.of(new BodyShape.BodyField("available", "Integer")), false);

    @Test
    void registersStubForUnmatched404AndServes200OnReplay() throws Exception {
        ExternalStubSynthesizer syn = synthesizer();
        List<ExternalCallSite> sites = List.of(
                new ExternalCallSite("GET", "/inventory/stock", Optional.of(INV_SHAPE)));
        List<RawHttpExchange> firstRound = List.of(ex("GET", "/inventory/stock", 404));

        var result = EndpointExplorationRunner.synthesizeStubsForUnmatched(
                firstRound, sites, syn);

        assertThat(result.newlyRegistered()).isEqualTo(1);

        // 재invoke 시뮬: 등록된 stub이 같은 path 요청에 200 + 합성 body를 반환.
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + "/inventory/stock")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).isEqualTo("{\"available\":1}");
    }

    @Test
    void noNewRegistrationWhenAlreadyRegistered() {
        ExternalStubSynthesizer syn = synthesizer();
        List<ExternalCallSite> sites = List.of(
                new ExternalCallSite("GET", "/inventory/stock", Optional.of(INV_SHAPE)));
        List<RawHttpExchange> round = List.of(ex("GET", "/inventory/stock", 404));

        assertThat(EndpointExplorationRunner.synthesizeStubsForUnmatched(round, sites, syn)
                .newlyRegistered()).isEqualTo(1);
        // 두 번째 라운드: 같은 unmatched지만 이미 등록됨 → 새 등록 0 → 루프 수렴 신호.
        assertThat(EndpointExplorationRunner.synthesizeStubsForUnmatched(round, sites, syn)
                .newlyRegistered()).isEqualTo(0);
    }

    @Test
    void non404ExchangesAreIgnored() {
        ExternalStubSynthesizer syn = synthesizer();
        List<ExternalCallSite> sites = List.of(
                new ExternalCallSite("GET", "/inventory/stock", Optional.of(INV_SHAPE)));
        // 이미 200을 받은(매칭된) 외부 호출은 재합성 대상이 아니다.
        List<RawHttpExchange> round = List.of(ex("GET", "/inventory/stock", 200));

        assertThat(EndpointExplorationRunner.synthesizeStubsForUnmatched(round, sites, syn)
                .newlyRegistered()).isEqualTo(0);
    }

    @Test
    void unmatchedCallWithNoSiteRecordsLoudFail() {
        ExternalStubSynthesizer syn = synthesizer();
        List<ExternalCallSite> sites = List.of();   // 인덱싱에서 매칭되는 site 없음
        List<RawHttpExchange> round = List.of(ex("GET", "/unknown/path", 404));

        var result = EndpointExplorationRunner.synthesizeStubsForUnmatched(round, sites, syn);

        assertThat(result.newlyRegistered()).isEqualTo(0);
        assertThat(result.loudFails())
                .anyMatch(lf -> lf.reason().equals("unmatched-external-call")
                        && lf.target().contains("/unknown/path"));
    }

    @Test
    void matchedSiteWithEmptyShapeRecordsUnwiredLoudFail() {
        ExternalStubSynthesizer syn = synthesizer();
        List<ExternalCallSite> sites = List.of(
                new ExternalCallSite("GET", "/inventory/stock", Optional.empty()));
        List<RawHttpExchange> round = List.of(ex("GET", "/inventory/stock", 404));

        var result = EndpointExplorationRunner.synthesizeStubsForUnmatched(round, sites, syn);

        assertThat(result.newlyRegistered()).isEqualTo(0);
        assertThat(result.loudFails())
                .anyMatch(lf -> lf.reason().equals("unwired-external-dep")
                        && lf.target().contains("/inventory/stock"));
    }
}

package io.graphrag.builder.env;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import io.graphrag.builder.explore.RawHttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 분석용 임베디드 WireMock (docs/03 L4의 recorder).
 * 외부 시스템의 minimal valid 응답은 운영자가 스텁 디렉터리로 제공한다.
 */
public class HttpCaptureServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HttpCaptureServer.class);

    private final WireMockServer server;
    private int drainedCount;

    public HttpCaptureServer() {
        this.server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    }

    public void start(Path stubsDir) {
        server.start();
        loadStubs(stubsDir);
        log.info("analysis wiremock on {}", baseUrl());
    }

    private void loadStubs(Path stubsDir) {
        if (stubsDir == null || !Files.isDirectory(stubsDir)) {
            return;
        }
        try (Stream<Path> files = Files.list(stubsDir)) {
            files.filter(p -> p.toString().endsWith(".json")).sorted().forEach(file -> {
                try {
                    server.addStubMapping(StubMapping.buildFrom(Files.readString(file)));
                    log.info("loaded external stub: {}", file.getFileName());
                } catch (Exception e) {
                    throw new IllegalStateException("invalid stub mapping: " + file, e);
                }
            });
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    public String baseUrl() {
        return server.baseUrl();
    }

    /** 마지막 호출 이후 SUT가 발행한 외부 HTTP 교환 (발생 순서). */
    public List<RawHttpExchange> drainNewExchanges() {
        List<ServeEvent> all = server.getAllServeEvents();   // 최신순
        int fresh = all.size() - drainedCount;
        drainedCount = all.size();
        List<RawHttpExchange> exchanges = new ArrayList<>();
        for (int i = fresh - 1; i >= 0; i--) {
            ServeEvent event = all.get(i);
            Map<String, String> query = new LinkedHashMap<>();
            event.getRequest().getQueryParams().forEach((name, value) ->
                    query.put(name, value.firstValue()));
            String baggage = event.getRequest().getHeader("baggage");
            exchanges.add(new RawHttpExchange(
                    event.getRequest().getMethod().getName(),
                    event.getRequest().getUrl().split("\\?")[0],
                    query,
                    event.getRequest().getBodyAsString(),
                    event.getResponse().getStatus(),
                    event.getResponse().getBodyAsString(),
                    baggage != null && baggage.contains("test-id=")));
        }
        return exchanges;
    }

    @Override
    public void close() {
        server.stop();
    }
}

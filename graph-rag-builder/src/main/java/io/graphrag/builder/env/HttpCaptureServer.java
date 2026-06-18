package io.graphrag.builder.env;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.requestfilter.RequestFilterAction;
import com.github.tomakehurst.wiremock.extension.requestfilter.RequestWrapper;
import com.github.tomakehurst.wiremock.extension.requestfilter.StubRequestFilterV2;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
// ResponseDefinition.notAuthorised() 미사용: 401에 마커 헤더를 붙이기 위해 ResponseDefinitionBuilder 사용
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
 *
 * <p>attach 모드에서는 서버가 0.0.0.0에 바인딩되어 컨테이너(host.docker.internal)에서 접근 가능하다.
 * 이를 보호하기 위해 per-run 토큰을 URL 경로 prefix로 사용한다(SUT가 outbound 헤더를 제어하지
 * 못하므로 헤더 토큰은 불가능; 우리는 SUT가 사용할 base URL을 제어한다). RequestFilter가
 * prefix를 검증/제거하며, drainNewExchanges는 기록된 URL에서 토큰을 벗기고 토큰 없는(미인가)
 * 요청은 제외한다.
 */
public class HttpCaptureServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HttpCaptureServer.class);

    private WireMockServer server;
    private String authToken;
    private int drainedCount;

    public HttpCaptureServer() {
    }

    public void start(Path stubsDir) {
        start(stubsDir, null);
    }

    public void start(Path stubsDir, String authToken) {
        this.authToken = authToken;
        WireMockConfiguration cfg = WireMockConfiguration.options().dynamicPort();   // 기본 0.0.0.0 바인딩
        if (authToken != null) {
            cfg.extensions(new TokenPrefixFilter(authToken));
        }
        this.server = new WireMockServer(cfg);
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

    public int port() {
        return server.port();
    }

    /** 분석(loopback) base URL. */
    public String baseUrl() {
        return server.baseUrl();
    }

    /** attach 모드에서 컨테이너의 SUT가 사용할 base URL (host gateway + per-run 토큰 prefix). */
    public String hostBaseUrl() {
        return "http://host.docker.internal:" + port() + (authToken == null ? "" : "/" + authToken);
    }

    /** 마지막 호출 이후 SUT가 발행한 외부 HTTP 교환 (발생 순서). */
    public List<RawHttpExchange> drainNewExchanges() {
        List<ServeEvent> all = server.getAllServeEvents();   // 최신순
        int fresh = all.size() - drainedCount;
        drainedCount = all.size();
        List<RawHttpExchange> exchanges = new ArrayList<>();
        for (int i = fresh - 1; i >= 0; i--) {
            ServeEvent event = all.get(i);
            String path = event.getRequest().getUrl().split("\\?")[0];
            if (authToken != null) {
                // 토큰 필터가 401 처리한 미인가 probe → 캡처 제외.
                if (event.getResponse() != null
                        && event.getResponse().getHeaders() != null
                        && event.getResponse().getHeaders().getHeader(TokenPrefixFilter.UNAUTH_HEADER).isPresent()) {
                    continue;
                }
                // 필터가 절대 URL에서 prefix를 이미 제거하므로 보통 path엔 prefix가 없다.
                // 만약 prefix가 남아있다면(방어적) 여기서 제거한다.
                String prefix = "/" + authToken;
                if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                    path = path.equals(prefix) ? "/" : path.substring(prefix.length());
                }
            }
            Map<String, String> query = new LinkedHashMap<>();
            event.getRequest().getQueryParams().forEach((name, value) ->
                    query.put(name, value.firstValue()));
            String baggage = event.getRequest().getHeader("baggage");
            exchanges.add(new RawHttpExchange(
                    event.getRequest().getMethod().getName(),
                    path,
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
        if (server != null && server.isRunning()) {
            server.stop();
        }
    }

    /**
     * per-run 토큰을 URL 경로 prefix로 검증하는 WireMock RequestFilter.
     * prefix가 없으면 401, 있으면 절대 URL에서 prefix를 제거한 뒤 스텁 매칭으로 넘긴다.
     */
    private static final class TokenPrefixFilter implements StubRequestFilterV2 {
        /** 미인가(토큰 없는) probe의 401 응답에 부착하는 마커. drainNewExchanges가 이를 보고 캡처에서 제외한다. */
        static final String UNAUTH_HEADER = "X-Grb-Unauthorized";

        private final String prefix;   // "/" + token

        TokenPrefixFilter(String token) {
            this.prefix = "/" + token;
        }

        @Override
        public String getName() {
            return "grb-token-prefix";
        }

        @Override
        public RequestFilterAction filter(Request request, ServeEvent serveEvent) {
            String url = request.getUrl();
            boolean authorised = url.equals(prefix)
                    || url.startsWith(prefix + "/")
                    || url.startsWith(prefix + "?");
            if (!authorised) {
                ResponseDefinition unauthorised = new ResponseDefinitionBuilder()
                        .withStatus(401)
                        .withHeader(UNAUTH_HEADER, "1")
                        .build();
                return RequestFilterAction.stopWith(unauthorised);
            }
            String stripped = url.substring(prefix.length());   // prefix 제거(쿼리 포함)
            if (stripped.isEmpty() || stripped.charAt(0) != '/') {
                stripped = "/" + stripped;   // 선행 '/' 보장
            }
            String strippedPath = stripped;
            Request wrapped = RequestWrapper.create()
                    .transformAbsoluteUrl(absUrl -> {
                        int p = absUrl.indexOf('/', absUrl.indexOf("//") + 2);
                        return p < 0 ? absUrl : absUrl.substring(0, p) + strippedPath;
                    })
                    .wrap(request);
            return RequestFilterAction.continueWith(wrapped);
        }
    }
}

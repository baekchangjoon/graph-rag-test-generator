package io.graphrag.builder.env;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.requestfilter.RequestFilterAction;
import com.github.tomakehurst.wiremock.extension.requestfilter.RequestWrapper;
import com.github.tomakehurst.wiremock.extension.requestfilter.StubRequestFilterV2;
import com.github.tomakehurst.wiremock.http.HttpHeader;
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
    private final TraceKey traceKey;

    public HttpCaptureServer() {
        this(new NoTraceKey());
    }

    public HttpCaptureServer(TraceKey traceKey) {
        this.traceKey = traceKey != null ? traceKey : new NoTraceKey();
    }

    /** 합성 stub을 런타임에 등록한다(B2 재탐색 루프용). server.addStubMapping 위임. */
    public void registerStub(StubMapping mapping) {
        server.addStubMapping(mapping);
    }

    /** 등록된 stub을 UUID로 제거한다(변형 stub 교체/정리용, REQ-008). server.removeStubMapping 위임. */
    public void removeStub(java.util.UUID id) {
        server.removeStubMapping(id);
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

    /**
     * 마지막 호출 이후 SUT가 발행한 외부 HTTP 교환 (발생 순서).
     *
     * <p>전제: 탐색 루프가 SUT 요청을 완전히 끝낸 뒤(동기) 호출한다 — 진행 중(in-flight)인 outbound
     * 호출이 없다고 가정한다. count-delta 슬라이싱은 {@code getAllServeEvents()}의 최신순 안정 정렬에
     * 의존한다. outbound 호출이 비동기가 되면 count 대신 마지막 event id 추적으로 바꿔야 한다.
     */
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
                // 필터의 transformAbsoluteUrl이 serve event 기록 전에 prefix를 제거하므로 현재 경로에선
                // path에 prefix가 남지 않는다. 아래는 향후 필터 변경(예: rewrite 비활성) 대비 방어 fallback.
                String prefix = "/" + authToken;
                if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                    path = path.equals(prefix) ? "/" : path.substring(prefix.length());
                }
            }
            Map<String, String> query = new LinkedHashMap<>();
            event.getRequest().getQueryParams().forEach((name, value) ->
                    query.put(name, value.firstValue()));
            String baggage = event.getRequest().getHeader("baggage");
            Map<String, String> headers = new LinkedHashMap<>();
            if (event.getRequest().getHeaders() != null) {
                for (HttpHeader h : event.getRequest().getHeaders().all()) {
                    headers.put(h.key(), h.firstValue());
                }
            }
            String traceId = traceKey.readTraceId(headers).orElse("");
            exchanges.add(new RawHttpExchange(
                    event.getRequest().getMethod().getName(),
                    path,
                    query,
                    event.getRequest().getBodyAsString(),
                    event.getResponse().getStatus(),
                    event.getResponse().getBodyAsString(),
                    baggage != null && baggage.contains("test-id="),
                    traceId));
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
            // 단순 String 비교(상수시간 아님)로 충분: 토큰은 256-bit per-run secret이라 추측 불가하고,
            // loopback/host-gateway에 노출되는 짧은 분석 구간 동안만 유효하다(startsWith 타이밍 공격은
            // JIT 노이즈로 비현실적). OTLP 리시버는 헤더 토큰이라 MessageDigest.isEqual을 쓰지만,
            // 여기선 경로 prefix 매칭이라 동일 기법을 적용하기 어렵고 위협 모델상 불필요하다.
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
            String stripped = url.substring(prefix.length());   // prefix 제거(path+query 포함)
            if (stripped.isEmpty() || stripped.charAt(0) != '/') {
                stripped = "/" + stripped;   // 선행 '/' 보장
            }
            String strippedPathAndQuery = stripped;
            Request wrapped = RequestWrapper.create()
                    .transformAbsoluteUrl(absUrl -> {
                        // scheme://authority 까지만 보존하고 그 뒤(토큰 포함 path+query)는 stripped로 교체.
                        // authorityEnd<0 이면 absUrl에 path가 없다는 뜻이므로 stripped를 그대로 덧붙인다.
                        // 어느 분기든 토큰을 포함한 원본 path를 되돌려주지 않는다(토큰 누출 방지).
                        int authorityEnd = absUrl.indexOf('/', absUrl.indexOf("//") + 2);
                        String base = authorityEnd < 0 ? absUrl : absUrl.substring(0, authorityEnd);
                        return base + strippedPathAndQuery;
                    })
                    .wrap(request);
            return RequestFilterAction.continueWith(wrapped);
        }
    }
}

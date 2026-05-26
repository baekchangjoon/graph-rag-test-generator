package io.graphrag.generator.archive;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphrag.generator.core.MultiPathSynthesisInput;
import io.graphrag.generator.core.PathContext;
import io.graphrag.model.CapturedHttpCall;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.JsonMappers;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * graph-rag-builder의 REST API를 호출하는 클라이언트.
 *
 * <p>도구 1과 도구 2를 직접 의존성으로 묶지 않고 HTTP로 연동 — 도구 분리 원칙 준수.
 */
public final class BuilderClient {

    private static final ObjectMapper MAPPER = JsonMappers.standard();

    private final HttpClient http;
    private final String baseUrl;

    public BuilderClient(String baseUrl) {
        this(baseUrl, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    public BuilderClient(String baseUrl, HttpClient http) {
        this.baseUrl = stripTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.http = Objects.requireNonNull(http, "http");
    }

    public Optional<Endpoint> findEndpoint(String id) throws IOException, InterruptedException {
        HttpResponse<String> res = get("/endpoints/" + URLEncoder.encode(id, StandardCharsets.UTF_8));
        if (res.statusCode() == 404) return Optional.empty();
        if (res.statusCode() != 200) {
            throw new IOException("builder returned " + res.statusCode() + ": " + res.body());
        }
        return Optional.of(MAPPER.readValue(res.body(), Endpoint.class));
    }

    public List<ExploredPath> pathsByEndpoint(String endpointId) throws IOException, InterruptedException {
        HttpResponse<String> res = get("/endpoints/"
                + URLEncoder.encode(endpointId, StandardCharsets.UTF_8) + "/paths");
        if (res.statusCode() != 200) return List.of();
        return MAPPER.readValue(res.body(), new TypeReference<List<ExploredPath>>() {});
    }

    public List<CapturedSql> capturedSqlByPath(String pathId) throws IOException, InterruptedException {
        HttpResponse<String> res = get("/paths/" + URLEncoder.encode(pathId, StandardCharsets.UTF_8) + "/captured-sql");
        if (res.statusCode() != 200) return List.of();
        return MAPPER.readValue(res.body(), new TypeReference<List<CapturedSql>>() {});
    }

    public List<CapturedHttpCall> capturedHttpByPath(String pathId) throws IOException, InterruptedException {
        HttpResponse<String> res = get("/paths/" + URLEncoder.encode(pathId, StandardCharsets.UTF_8) + "/captured-http");
        if (res.statusCode() != 200) return List.of();
        return MAPPER.readValue(res.body(), new TypeReference<List<CapturedHttpCall>>() {});
    }

    /**
     * 주어진 endpoint id에 대해 builder의 모든 사실을 종합 → MultiPathSynthesisInput.
     */
    public MultiPathSynthesisInput buildInput(String endpointId, String testPackage)
            throws IOException, InterruptedException {
        Endpoint ep = findEndpoint(endpointId)
                .orElseThrow(() -> new IllegalArgumentException("endpoint not found: " + endpointId));
        List<PathContext> contexts = pathsByEndpoint(endpointId).stream()
                .map(p -> {
                    try {
                        return new PathContext(p,
                                capturedSqlByPath(p.id()),
                                capturedHttpByPath(p.id()));
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                })
                .collect(Collectors.toList());
        return new MultiPathSynthesisInput(ep, contexts, testPackage);
    }

    private HttpResponse<String> get(String pathSuffix) throws IOException, InterruptedException {
        return http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + pathSuffix))
                        .header("Accept", "application/json")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}

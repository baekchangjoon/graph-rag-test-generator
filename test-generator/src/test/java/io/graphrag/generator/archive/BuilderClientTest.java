package io.graphrag.generator.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.graphrag.generator.core.MultiPathSynthesisInput;
import io.graphrag.model.Binding;
import io.graphrag.model.BindingOrigin;
import io.graphrag.model.CapturedHttpCall;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.CapturedSqlSource;
import io.graphrag.model.CapturedSqlType;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.HttpClientType;
import io.graphrag.model.HttpMethod;
import io.graphrag.model.JsonMappers;
import io.graphrag.model.PathExplorerKind;
import io.graphrag.model.SampleInput;
import io.graphrag.model.SourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BuilderClientTest {

    private final ObjectMapper mapper = JsonMappers.standard();
    private WireMockServer wm;
    private BuilderClient client;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        client = new BuilderClient(wm.baseUrl());
    }

    @AfterEach
    void tearDown() {
        if (wm != null) wm.stop();
    }

    @Test
    void findEndpointReturns404Empty() throws Exception {
        wm.stubFor(get(urlPathMatching("/endpoints/.*"))
                .willReturn(aResponse().withStatus(404)));
        assertThat(client.findEndpoint("UNKNOWN")).isEmpty();
    }

    @Test
    void findEndpointParsesJsonResponse() throws Exception {
        Endpoint ep = new Endpoint("POST:/api/orders", HttpMethod.POST, "/api/orders",
                "demo", "C", "m", false, List.of());
        wm.stubFor(get(urlPathMatching("/endpoints/.*"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(mapper.writeValueAsString(ep))));

        assertThat(client.findEndpoint(ep.id())).isPresent()
                .get().satisfies(found -> assertThat(found.method()).isEqualTo(HttpMethod.POST));
    }

    @Test
    void pathsByEndpointReturnsList() throws Exception {
        ExploredPath p = new ExploredPath("p1", "POST:/api/orders", PathExplorerKind.MANUAL,
                new SampleInput(Map.of(), Map.of(), Map.of(), Map.of()),
                null, List.of(), 201, null, "sig", "v1");
        wm.stubFor(get(urlPathMatching("/endpoints/.*/paths"))
                .willReturn(aResponse().withStatus(200)
                        .withBody(mapper.writeValueAsString(List.of(p)))));

        assertThat(client.pathsByEndpoint("POST:/api/orders")).hasSize(1);
    }

    @Test
    void capturedSqlAndHttpAreFetched() throws Exception {
        CapturedSql sql = new CapturedSql("s-1", "p1", CapturedSqlType.INSERT,
                "INSERT INTO x VALUES (?)",
                List.of(new Binding(0, 1, BindingOrigin.COMPUTED, null)),
                CapturedSqlSource.JPA_ENTITYMANAGER,
                new SourceLocation("X", "y", 1),
                List.of("x"), List.of());
        CapturedHttpCall http = new CapturedHttpCall("h-1", "p1", "GET", "/x", "/x",
                List.of(), Map.of(), null, List.of(), 200, "{}",
                List.of(), HttpClientType.OTHER, "ext");

        wm.stubFor(get(urlPathEqualTo("/paths/p1/captured-sql"))
                .willReturn(aResponse().withStatus(200)
                        .withBody(mapper.writeValueAsString(List.of(sql)))));
        wm.stubFor(get(urlPathEqualTo("/paths/p1/captured-http"))
                .willReturn(aResponse().withStatus(200)
                        .withBody(mapper.writeValueAsString(List.of(http)))));

        assertThat(client.capturedSqlByPath("p1")).hasSize(1);
        assertThat(client.capturedHttpByPath("p1")).hasSize(1);
    }

    @Test
    void buildInputAggregatesEverythingForKnownEndpoint() throws Exception {
        Endpoint ep = new Endpoint("POST:/api/orders", HttpMethod.POST, "/api/orders",
                "demo", "C", "m", false, List.of());
        ExploredPath p = new ExploredPath("p1", ep.id(), PathExplorerKind.MANUAL,
                new SampleInput(Map.of(), Map.of(), Map.of(), Map.of()),
                null, List.of(), 201, null, "sig", "v1");

        wm.stubFor(get(urlPathMatching("/endpoints/[^/]+$"))
                .willReturn(aResponse().withStatus(200).withBody(mapper.writeValueAsString(ep))));
        wm.stubFor(get(urlPathMatching("/endpoints/.+/paths"))
                .willReturn(aResponse().withStatus(200).withBody(mapper.writeValueAsString(List.of(p)))));
        wm.stubFor(get(urlPathEqualTo("/paths/p1/captured-sql"))
                .willReturn(aResponse().withStatus(200).withBody("[]")));
        wm.stubFor(get(urlPathEqualTo("/paths/p1/captured-http"))
                .willReturn(aResponse().withStatus(200).withBody("[]")));

        MultiPathSynthesisInput input = client.buildInput(ep.id(), "gen");
        assertThat(input.endpoint().id()).isEqualTo(ep.id());
        assertThat(input.paths()).hasSize(1);
    }

    @Test
    void buildInputThrowsWhenEndpointNotFound() {
        wm.stubFor(get(urlPathMatching("/endpoints/.+"))
                .willReturn(aResponse().withStatus(404)));
        assertThatThrownBy(() -> client.buildInput("UNKNOWN", "gen"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

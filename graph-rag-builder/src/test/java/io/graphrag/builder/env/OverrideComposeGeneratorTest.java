package io.graphrag.builder.env;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class OverrideComposeGeneratorTest {
    @Test
    void injectsLoggingAgentsAndPortsOntoAppService() throws Exception {
        var spec = new OverrideComposeGenerator.Spec(
                "app", "/host/agents", 8080, 58080, 6300, 16300,
                "-javaagent:/grb-agents/jacocoagent.jar=output=tcpserver,address=*,port=6300"
                        + " -javaagent:/grb-agents/otel-javaagent.jar",
                Map.of("com.example.mapper.OrderMapper", "TRACE"),
                Map.of("OTEL_TRACES_EXPORTER", "none", "OTEL_PROPAGATORS", "tracecontext,baggage"));
        String yaml = new OverrideComposeGenerator().generate(spec);
        JsonNode root = new YAMLMapper().readTree(yaml);
        JsonNode app = root.path("services").path("app");
        assertTrue(app.path("environment").path("JAVA_TOOL_OPTIONS").asText().contains("jacocoagent.jar"));
        assertEquals("none", app.path("environment").path("OTEL_TRACES_EXPORTER").asText());
        // SPRING_APPLICATION_JSON 은 YAML 안의 '문자열'로 보존돼야(이중 인코딩) — round-trip 검증
        assertTrue(app.path("environment").path("SPRING_APPLICATION_JSON").isTextual());
        String saj = app.path("environment").path("SPRING_APPLICATION_JSON").asText();
        assertTrue(saj.contains("logging.level.org.hibernate.SQL"));
        assertTrue(saj.contains("org.hibernate.orm.jdbc.bind"));
        assertTrue(saj.contains("com.example.mapper.OrderMapper"));
        // app + jacoco ports published "host:container"
        boolean appPort = false, jacocoPort = false;
        for (JsonNode p : app.path("ports")) {
            if (p.asText().equals("58080:8080")) appPort = true;
            if (p.asText().equals("16300:6300")) jacocoPort = true;
        }
        assertTrue(appPort && jacocoPort);
        assertEquals("/host/agents:/grb-agents:ro", app.path("volumes").get(0).asText());
        // 기본(편의 생성자)은 extra_hosts/batch 무주입
        assertTrue(app.path("extra_hosts").isMissingNode());
        assertFalse(app.path("environment").path("SPRING_APPLICATION_JSON").asText()
                .contains("batch_size"));
    }

    @Test
    void otelAttachMode_addsHostGatewayAndDisablesBatch() throws Exception {
        var spec = new OverrideComposeGenerator.Spec(
                "app", "/host/agents", 8080, 58080, 6300, 16300,
                "-javaagent:/grb-agents/otel-javaagent.jar",
                Map.of(),
                Map.of("OTEL_EXPORTER_OTLP_ENDPOINT", "http://host.docker.internal:64048",
                        "OTEL_EXPORTER_OTLP_HEADERS", "x-graphrag-token=s3cr3t"),
                true, true);   // addHostGateway, disableBatch
        String yaml = new OverrideComposeGenerator().generate(spec);
        JsonNode app = new YAMLMapper().readTree(yaml).path("services").path("app");

        boolean hostGateway = false;
        for (JsonNode h : app.path("extra_hosts")) {
            if (h.asText().equals("host.docker.internal:host-gateway")) hostGateway = true;
        }
        assertTrue(hostGateway, "extra_hosts host-gateway 주입");
        assertEquals("http://host.docker.internal:64048",
                app.path("environment").path("OTEL_EXPORTER_OTLP_ENDPOINT").asText());
        String saj = app.path("environment").path("SPRING_APPLICATION_JSON").asText();
        assertTrue(saj.contains("spring.jpa.properties.hibernate.jdbc.batch_size"), "batch_size=0 병합");
        assertTrue(saj.contains("logging.level.org.hibernate.SQL"), "기존 로깅 유지");
    }

    @Test
    void injectsLoggingAndEncodingOntoExtraCaptureServices() throws Exception {
        var spec = new OverrideComposeGenerator.Spec(
                "a", "/host/agents", 8080, 58080, 6300, 16300,
                "-javaagent:/grb-agents/jacocoagent.jar=output=tcpserver,address=*,port=6300",
                Map.of(), Map.of(), false, false,
                java.util.List.of("b", "c"));   // extraLogServices
        String yaml = new OverrideComposeGenerator().generate(spec);
        JsonNode services = new YAMLMapper().readTree(yaml).path("services");

        for (String svc : java.util.List.of("b", "c")) {
            JsonNode env = services.path(svc).path("environment");
            String saj = env.path("SPRING_APPLICATION_JSON").asText();
            assertTrue(saj.contains("logging.level.org.hibernate.SQL"), svc + " H6 SQL 로깅레벨");
            // H5 BasicBinder 로그레벨이 주입돼야 H5 SUT가 bind 라인을 출력한다(스펙 §8)
            assertTrue(saj.contains("logging.level.org.hibernate.type.descriptor.sql.BasicBinder"),
                    svc + " H5 BasicBinder 로깅레벨");
            assertTrue(env.path("JAVA_TOOL_OPTIONS").asText().contains("-Dfile.encoding=UTF-8"),
                    svc + " 인코딩 주입");
            // 보조 서비스엔 에이전트/포트/볼륨 미주입
            assertFalse(env.path("JAVA_TOOL_OPTIONS").asText().contains("jacocoagent.jar"));
            assertTrue(services.path(svc).path("ports").isMissingNode());
            assertTrue(services.path(svc).path("volumes").isMissingNode());
        }
        // appService(a)는 기존대로 에이전트/포트 유지 + H5 로깅레벨도 포함
        assertTrue(services.path("a").path("environment").path("JAVA_TOOL_OPTIONS").asText()
                .contains("jacocoagent.jar"));
        assertTrue(services.path("a").path("environment").path("SPRING_APPLICATION_JSON").asText()
                .contains("logging.level.org.hibernate.type.descriptor.sql.BasicBinder"));
    }

    @Test
    void attachAlwaysAddsHostGateway_evenWhenBatchNotDisabled() throws Exception {
        var spec = new OverrideComposeGenerator.Spec(
                "app", "/host/agents", 8080, 58080, 6300, 16300,
                "-javaagent:/grb-agents/otel-javaagent.jar",
                Map.of(),
                Map.of("WIREMOCK_TARGET", "http://host.docker.internal:65000/tok"),
                true, false);   // addHostGateway, disableBatch=false (non-otel attach)
        String yaml = new OverrideComposeGenerator().generate(spec);
        JsonNode app = new YAMLMapper().readTree(yaml).path("services").path("app");

        boolean hostGateway = false;
        for (JsonNode h : app.path("extra_hosts")) {
            if (h.asText().equals("host.docker.internal:host-gateway")) hostGateway = true;
        }
        assertTrue(hostGateway, "attach 모드는 batch 미차단이어도 host-gateway 주입");
        String saj = app.path("environment").path("SPRING_APPLICATION_JSON").asText();
        assertFalse(saj.contains("spring.jpa.properties.hibernate.jdbc.batch_size"),
                "disableBatch=false 이면 batch_size=0 병합 안 함");
        assertTrue(saj.contains("logging.level.org.hibernate.SQL"), "기존 로깅 유지");
    }
}

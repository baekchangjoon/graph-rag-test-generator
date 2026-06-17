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
    }
}

package io.graphrag.builder.env;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.graphrag.model.Json;

import java.util.Map;
import java.util.TreeMap;

/**
 * 사용자 compose 위에 머지할 override compose를 생성한다 (attach 모드).
 * app 서비스에 SQL+bind 로깅(SPRING_APPLICATION_JSON), 커버리지/otel agent(JAVA_TOOL_OPTIONS + volume),
 * app·jacoco 포트 publish를 주입한다. SUT 이미지·소스는 건드리지 않는다(무수정).
 */
public final class OverrideComposeGenerator {

    /**
     * @param appService          compose 내 SUT(app) 서비스명
     * @param hostAgentsDir       호스트의 agents 디렉터리(jacoco/otel jar) — /grb-agents:ro 로 마운트
     * @param appContainerPort    app 컨테이너 내부 포트(예: 8080)
     * @param appHostPort         호스트 publish 포트
     * @param jacocoContainerPort jacoco tcpserver 컨테이너 포트
     * @param jacocoHostPort      jacoco 호스트 publish 포트
     * @param javaToolOptions     주입할 JAVA_TOOL_OPTIONS (jacoco container opts + otel)
     * @param mybatisNamespaces   mapper namespace → "TRACE"
     * @param extraEnv            OTEL 등 추가 환경변수
     * @param addHostGateway      true면 {@code extra_hosts: [host.docker.internal:host-gateway]} 추가
     *                            (OTEL attach: 컨테이너→호스트 OTLP 리시버 도달)
     * @param disableBatch        true면 SPRING_APPLICATION_JSON에 hibernate.jdbc.batch_size=0 병합
     *                            (OTEL bind 캡처가 batch insert에서 누락되지 않도록)
     */
    public record Spec(String appService, String hostAgentsDir,
                       int appContainerPort, int appHostPort,
                       int jacocoContainerPort, int jacocoHostPort,
                       String javaToolOptions, Map<String, String> mybatisNamespaces,
                       Map<String, String> extraEnv,
                       boolean addHostGateway, boolean disableBatch) {

        /** 기존 호출부 호환 편의 생성자 (host-gateway/batch 비활성). */
        public Spec(String appService, String hostAgentsDir,
                    int appContainerPort, int appHostPort,
                    int jacocoContainerPort, int jacocoHostPort,
                    String javaToolOptions, Map<String, String> mybatisNamespaces,
                    Map<String, String> extraEnv) {
            this(appService, hostAgentsDir, appContainerPort, appHostPort,
                    jacocoContainerPort, jacocoHostPort, javaToolOptions, mybatisNamespaces,
                    extraEnv, false, false);
        }
    }

    private static final YAMLMapper YAML = new YAMLMapper();

    public String generate(Spec spec) {
        try {
            ObjectNode root = Json.mapper().createObjectNode();
            ObjectNode services = root.putObject("services");
            ObjectNode app = services.putObject(spec.appService());

            ObjectNode env = app.putObject("environment");
            env.put("JAVA_TOOL_OPTIONS", spec.javaToolOptions());
            env.put("SPRING_APPLICATION_JSON", springApplicationJson(spec.mybatisNamespaces(), spec.disableBatch()));
            // OTEL 전파 env (analysis 모드의 otel.env와 동등: exporter none + baggage / otel 모드: otlp export)
            new TreeMap<>(spec.extraEnv()).forEach(env::put);

            ArrayNode volumes = app.putArray("volumes");
            volumes.add(spec.hostAgentsDir() + ":/grb-agents:ro");

            ArrayNode ports = app.putArray("ports");
            ports.add(spec.appHostPort() + ":" + spec.appContainerPort());
            ports.add(spec.jacocoHostPort() + ":" + spec.jacocoContainerPort());

            // OTEL attach: 컨테이너가 host.docker.internal로 호스트 OTLP 리시버에 도달(Docker 20.10+).
            if (spec.addHostGateway()) {
                app.putArray("extra_hosts").add("host.docker.internal:host-gateway");
            }

            return YAML.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("override compose 생성 실패", e);
        }
    }

    /** Hibernate SQL+bind DEBUG/TRACE + MyBatis namespace TRACE 를 한 JSON 문자열로(SUT 로그에 SQL 노출). */
    private static String springApplicationJson(Map<String, String> mybatisNamespaces, boolean disableBatch) {
        try {
            ObjectNode node = Json.mapper().createObjectNode();
            node.put("logging.level.org.hibernate.SQL", "DEBUG");
            node.put("logging.level.org.hibernate.orm.jdbc.bind", "TRACE");
            new TreeMap<>(mybatisNamespaces).forEach(
                    (ns, level) -> node.put("logging.level." + ns, level));
            if (disableBatch) {
                node.put("spring.jpa.properties.hibernate.jdbc.batch_size", "0");
            }
            return Json.mapper().writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

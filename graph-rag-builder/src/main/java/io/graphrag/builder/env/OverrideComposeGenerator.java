package io.graphrag.builder.env;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.graphrag.model.Json;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 사용자 compose 위에 머지할 override compose를 생성한다 (attach 모드).
 * app 서비스에 SQL+bind 로깅(SPRING_APPLICATION_JSON), 커버리지/otel agent(JAVA_TOOL_OPTIONS + volume),
 * app·coverage 포트 publish를 주입한다. SUT 이미지·소스는 건드리지 않는다(무수정).
 */
public final class OverrideComposeGenerator {

    /**
     * @param appService          compose 내 SUT(app) 서비스명
     * @param hostAgentsDir       호스트의 agents 디렉터리(otel/pjacoco jar) — /grb-agents:ro 로 마운트
     * @param appContainerPort    app 컨테이너 내부 포트(예: 8080)
     * @param appHostPort         호스트 publish 포트
     * @param coverageContainerPort 커버리지(pjacoco control) 에이전트 컨테이너 포트
     * @param coverageHostPort      커버리지(pjacoco control) 에이전트 호스트 publish 포트
     * @param javaToolOptions     주입할 JAVA_TOOL_OPTIONS (otel + 커버리지 agent)
     * @param mybatisNamespaces   mapper namespace → "TRACE"
     * @param extraEnv            OTEL 등 추가 환경변수
     * @param addHostGateway      true면 {@code extra_hosts: [host.docker.internal:host-gateway]} 추가
     *                            (OTEL attach: 컨테이너→호스트 OTLP 리시버 도달)
     * @param disableBatch        true면 SPRING_APPLICATION_JSON에 hibernate.jdbc.batch_size=0 병합
     *                            (OTEL bind 캡처가 batch insert에서 누락되지 않도록)
     * @param extraVolumes        추가 volume 마운트 (예: pjacoco exec dir). "hostPath:containerPath" 형식.
     */
    public record Spec(String appService, String hostAgentsDir,
                       int appContainerPort, int appHostPort,
                       int coverageContainerPort, int coverageHostPort,
                       String javaToolOptions, Map<String, String> mybatisNamespaces,
                       Map<String, String> extraEnv,
                       boolean addHostGateway, boolean disableBatch,
                       List<String> extraLogServices,
                       List<String> extraVolumes) {

        /** 12-arg 편의 생성자 (extraVolumes 없음 — 기존 12-arg 호출부 호환). */
        public Spec(String appService, String hostAgentsDir,
                    int appContainerPort, int appHostPort,
                    int coverageContainerPort, int coverageHostPort,
                    String javaToolOptions, Map<String, String> mybatisNamespaces,
                    Map<String, String> extraEnv,
                    boolean addHostGateway, boolean disableBatch,
                    List<String> extraLogServices) {
            this(appService, hostAgentsDir, appContainerPort, appHostPort,
                    coverageContainerPort, coverageHostPort, javaToolOptions, mybatisNamespaces,
                    extraEnv, addHostGateway, disableBatch, extraLogServices, List.of());
        }

        /** 9-arg 편의 생성자 (기존 호출부 호환; host-gateway/batch/extraLogServices/extraVolumes 비활성). */
        public Spec(String appService, String hostAgentsDir,
                    int appContainerPort, int appHostPort,
                    int coverageContainerPort, int coverageHostPort,
                    String javaToolOptions, Map<String, String> mybatisNamespaces,
                    Map<String, String> extraEnv) {
            this(appService, hostAgentsDir, appContainerPort, appHostPort,
                    coverageContainerPort, coverageHostPort, javaToolOptions, mybatisNamespaces,
                    extraEnv, false, false, List.of(), List.of());
        }

        /** 11-arg 생성자 (addHostGateway/disableBatch 사용 호출부 호환; 보조 서비스/extraVolumes 없음). */
        public Spec(String appService, String hostAgentsDir,
                    int appContainerPort, int appHostPort,
                    int coverageContainerPort, int coverageHostPort,
                    String javaToolOptions, Map<String, String> mybatisNamespaces,
                    Map<String, String> extraEnv,
                    boolean addHostGateway, boolean disableBatch) {
            this(appService, hostAgentsDir, appContainerPort, appHostPort,
                    coverageContainerPort, coverageHostPort, javaToolOptions, mybatisNamespaces,
                    extraEnv, addHostGateway, disableBatch, List.of(), List.of());
        }
    }

    private static final YAMLMapper YAML = new YAMLMapper();

    public static final String ENCODING_JTO = "-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8";

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
            for (String vol : spec.extraVolumes()) {
                volumes.add(vol);
            }

            ArrayNode ports = app.putArray("ports");
            ports.add(spec.appHostPort() + ":" + spec.appContainerPort());
            ports.add(spec.coverageHostPort() + ":" + spec.coverageContainerPort());

            // OTEL attach: 컨테이너가 host.docker.internal로 호스트 OTLP 리시버에 도달(Docker 20.10+).
            if (spec.addHostGateway()) {
                app.putArray("extra_hosts").add("host.docker.internal:host-gateway");
            }

            // 보조 capture-service: 로깅 레벨(SAJ) + 인코딩(JTO)만. 에이전트/포트/볼륨은 appService 전용.
            for (String svc : spec.extraLogServices()) {
                if (svc.equals(spec.appService())) {
                    continue;   // appService는 위에서 이미 완전 구성
                }
                ObjectNode extra = services.putObject(svc);
                ObjectNode extraEnvNode = extra.putObject("environment");
                extraEnvNode.put("JAVA_TOOL_OPTIONS", ENCODING_JTO);
                extraEnvNode.put("SPRING_APPLICATION_JSON",
                        springApplicationJson(spec.mybatisNamespaces(), false));
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
            node.put("logging.level.org.hibernate.type.descriptor.sql.BasicBinder", "TRACE");
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

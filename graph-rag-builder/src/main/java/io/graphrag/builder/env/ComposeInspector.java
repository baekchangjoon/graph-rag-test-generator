package io.graphrag.builder.env;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;

/** docker-compose에서 DB 서비스(postgres/mysql/mariadb)를 찾아 DbConfig로 추출한다. */
public final class ComposeInspector {

    private static final YAMLMapper YAML = new YAMLMapper();

    private ComposeInspector() {
    }

    public static DbConfig detectDb(Path composePath) {
        return detectDb(composePath, null);
    }

    /**
     * compose에서 DB 서비스를 찾아 DbConfig로 추출한다.
     * preferredService가 주어지면 그 서비스를 사용한다(multi-DB compose 대응).
     * 없으면 services 순서상 첫 DB 서비스를 사용한다.
     */
    public static DbConfig detectDb(Path composePath, String preferredService) {
        try {
            JsonNode root = YAML.readTree(composePath.toFile());
            JsonNode services = root.path("services");
            if (preferredService != null) {
                JsonNode service = services.path(preferredService);
                DbConfig.Type type = typeForImage(service.path("image").asText(""));
                if (type == null) {
                    throw new IllegalStateException("compose service '" + preferredService
                            + "' is not a DB (postgres/mysql/mariadb) in " + composePath);
                }
                return toConfig(type, service);
            }
            Iterator<Map.Entry<String, JsonNode>> it = services.fields();
            while (it.hasNext()) {
                JsonNode service = it.next().getValue();
                DbConfig.Type type = typeForImage(service.path("image").asText(""));
                if (type != null) {
                    return toConfig(type, service);
                }
            }
            throw new IllegalStateException("no DB service (postgres/mysql/mariadb) in " + composePath);
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static DbConfig toConfig(DbConfig.Type type, JsonNode service) {
        JsonNode env = service.path("environment");
        return new DbConfig(type, service.path("image").asText(""),
                envValue(env, type, "DB"),
                envValue(env, type, "USER"),
                envValue(env, type, "PASSWORD"));
    }

    private static DbConfig.Type typeForImage(String image) {
        String lower = image.toLowerCase();
        if (lower.contains("postgres")) return DbConfig.Type.POSTGRES;
        if (lower.contains("mariadb")) return DbConfig.Type.MARIADB;
        if (lower.contains("mysql")) return DbConfig.Type.MYSQL;
        return null;
    }

    /** POSTGRES_DB / MYSQL_DATABASE 등 키 차이를 흡수. environment는 map 또는 list 형식. */
    private static String envValue(JsonNode env, DbConfig.Type type, String suffix) {
        String key = switch (type) {
            case POSTGRES -> "POSTGRES_" + suffix;
            case MYSQL, MARIADB -> "MYSQL_" + (suffix.equals("DB") ? "DATABASE" : suffix);
        };
        if (env.isObject()) {
            return expand(env.path(key).asText(""));
        }
        if (env.isArray()) {
            for (JsonNode entry : env) {
                String text = entry.asText("");
                if (text.startsWith(key + "=")) {
                    return expand(text.substring(key.length() + 1));
                }
            }
        }
        return "";
    }

    /**
     * compose 변수 치환을 해석한다: {@code ${VAR:-default}}/{@code ${VAR:default}} → 실제
     * 환경변수 값이 있으면 그 값, 없으면 default. {@code ${VAR}} → 환경변수 또는 빈 문자열.
     * (Testcontainers는 fresh DB이므로 보통 default가 맞다 — 리터럴 "${...}"를 컨테이너
     * user/password로 넣으면 postgres가 기동 실패한다.)
     */
    private static String expand(String value) {
        if (value == null || !value.startsWith("${") || !value.endsWith("}")) {
            return value == null ? "" : value;
        }
        String inner = value.substring(2, value.length() - 1);   // VAR:-default | VAR:default | VAR
        String name;
        String def;
        if (inner.contains(":-")) {                  // ${VAR:-default}
            name = inner.substring(0, inner.indexOf(":-"));
            def = inner.substring(inner.indexOf(":-") + 2);
        } else if (inner.contains(":")) {            // ${VAR:default}
            name = inner.substring(0, inner.indexOf(':'));
            def = inner.substring(inner.indexOf(':') + 1);
        } else {                                      // ${VAR}
            name = inner;
            def = "";
        }
        String fromEnv = System.getenv(name.trim());
        return fromEnv != null && !fromEnv.isBlank() ? fromEnv : def;
    }
}

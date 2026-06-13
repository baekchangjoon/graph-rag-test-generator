package io.graphrag.builder.env;

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** DbConfig → type에 맞는 Testcontainers JDBC 컨테이너. compose와 동일 image 사용. */
public final class JdbcContainers {

    private JdbcContainers() {
    }

    public static JdbcDatabaseContainer<?> create(DbConfig config) {
        return switch (config.type()) {
            case POSTGRES -> new PostgreSQLContainer<>(DockerImageName.parse(config.image()))
                    .withDatabaseName(nz(config.dbName(), "app"))
                    .withUsername(nz(config.user(), "app"))
                    .withPassword(nz(config.password(), "app"));
            case MYSQL -> new MySQLContainer<>(DockerImageName.parse(config.image()))
                    .withDatabaseName(nz(config.dbName(), "app"))
                    .withUsername(nz(config.user(), "app"))
                    .withPassword(nz(config.password(), "app"));
            case MARIADB -> new MariaDBContainer<>(DockerImageName.parse(config.image()))
                    .withDatabaseName(nz(config.dbName(), "app"))
                    .withUsername(nz(config.user(), "app"))
                    .withPassword(nz(config.password(), "app"));
        };
    }

    private static String nz(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}

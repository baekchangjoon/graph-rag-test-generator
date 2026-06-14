package io.graphrag.builder.env;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class ComposeInspectorTest {

    @Test
    void detectsPostgresServiceAndCredentials(@TempDir Path dir) throws Exception {
        Path compose = dir.resolve("docker-compose.yml");
        Files.writeString(compose, """
                services:
                  db:
                    image: postgres:15
                    environment:
                      POSTGRES_DB: petclinic
                      POSTGRES_USER: petclinic
                      POSTGRES_PASSWORD: secret
                  app:
                    image: app:latest
                """);
        DbConfig config = ComposeInspector.detectDb(compose);
        assertThat(config.type()).isEqualTo(DbConfig.Type.POSTGRES);
        assertThat(config.image()).isEqualTo("postgres:15");
        assertThat(config.dbName()).isEqualTo("petclinic");
        assertThat(config.user()).isEqualTo("petclinic");
        assertThat(config.password()).isEqualTo("secret");
    }

    @Test
    void detectsMysqlService(@TempDir Path dir) throws Exception {
        Path compose = dir.resolve("docker-compose.yml");
        Files.writeString(compose, """
                services:
                  mysql:
                    image: mysql:8.4
                    environment:
                      MYSQL_DATABASE: petclinic
                      MYSQL_USER: petclinic
                      MYSQL_PASSWORD: secret
                """);
        DbConfig config = ComposeInspector.detectDb(compose);
        assertThat(config.type()).isEqualTo(DbConfig.Type.MYSQL);
        assertThat(config.dbName()).isEqualTo("petclinic");
    }

    @Test
    void preferredServiceSelectsAmongMultipleDbs(@TempDir Path dir) throws Exception {
        Path compose = dir.resolve("docker-compose.yml");
        Files.writeString(compose, """
                services:
                  mysql:
                    image: mysql:9.5
                    environment:
                      MYSQL_DATABASE: petclinic
                      MYSQL_USER: petclinic
                      MYSQL_PASSWORD: petclinic
                  postgres:
                    image: postgres:18.1
                    environment:
                      POSTGRES_DB: petclinic
                      POSTGRES_USER: petclinic
                      POSTGRES_PASSWORD: petclinic
                """);
        assertThat(ComposeInspector.detectDb(compose).type()).isEqualTo(DbConfig.Type.MYSQL);
        DbConfig pg = ComposeInspector.detectDb(compose, "postgres");
        assertThat(pg.type()).isEqualTo(DbConfig.Type.POSTGRES);
        assertThat(pg.image()).isEqualTo("postgres:18.1");
    }
}

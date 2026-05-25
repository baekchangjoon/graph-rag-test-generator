package io.graphrag.builder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;

/**
 * Graph RAG Builder의 Spring Boot 엔트리포인트.
 *
 * <p>Phase 0: REST query API만 노출. DataSource auto-config 제외 (분석 시점 동적 설정).
 */
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class})
public class BuilderApplication {
    public static void main(String[] args) {
        SpringApplication.run(BuilderApplication.class, args);
    }
}

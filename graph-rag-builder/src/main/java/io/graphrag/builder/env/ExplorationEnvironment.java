package io.graphrag.builder.env;

import java.sql.Connection;
import java.sql.SQLException;

/** build()가 의존하는 분석 환경 표면. AnalysisEnvironment(Testcontainers)와 AttachedComposeEnvironment(compose) 공통. */
public interface ExplorationEnvironment extends AutoCloseable {
    SutHandle sut();
    Connection openConnection() throws SQLException;
    DbConfig.Type dbType();
    HttpCaptureServer httpCapture();      // nullable (attach v1 → null)
    String kafkaBootstrapServers();       // nullable
    String coverageHost();
    int coveragePort();
    @Override void close();
}

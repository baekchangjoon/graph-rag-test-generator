package io.graphrag.builder.env;

import java.sql.Connection;
import java.sql.SQLException;

/** build()가 의존하는 분석 환경 표면. AnalysisEnvironment(Testcontainers)와 AttachedComposeEnvironment(compose) 공통. */
public interface ExplorationEnvironment extends AutoCloseable {
    SutHandle sut();
    Connection openConnection() throws SQLException;
    DbConfig.Type dbType();
    HttpCaptureServer httpCapture();      // nullable — attach는 --external-stubs/--sut-env 배선 시 non-null
    String kafkaBootstrapServers();       // nullable
    String coverageHost();
    int coveragePort();

    /** OTEL SQL 캡처 모드일 때 Environment가 소유한 OTLP receiver. log 모드/미지원 환경이면 null. */
    default io.graphrag.builder.capture.otlp.OtlpTraceReceiver otlpReceiver() {
        return null;
    }

    /** sleuth 모드일 때 Environment가 소유한 Zipkin span receiver. 그 외 환경이면 null. */
    default io.graphrag.builder.capture.zipkin.ZipkinSpanReceiver zipkinReceiver() {
        return null;
    }

    @Override void close();
}

package io.graphrag.testlib.spi;

import io.graphrag.testlib.api.HttpMockClient;
import io.graphrag.testlib.scope.Config;

/**
 * {@link HttpMockClient}를 만들어내는 어댑터. {@code java.util.ServiceLoader}로 발견.
 */
public interface HttpMockAdapter {
    String name();
    HttpMockClient create(Config config);
}

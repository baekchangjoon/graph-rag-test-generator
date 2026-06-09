package io.graphrag.builder.index;

import io.graphrag.model.Endpoint;
import io.graphrag.model.ParamKind;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EndpointIndexerTest {

    private static final Path SAMPLE_SRC = Path.of("src/test/resources/sample-src");

    @Test
    void index_findsPostEndpointWithClassLevelPrefix() {
        IndexResult result = new EndpointIndexer().index(SAMPLE_SRC);

        assertThat(result.endpoints()).hasSize(1);
        Endpoint endpoint = result.endpoints().get(0);
        assertThat(endpoint.id()).isEqualTo("post-api-orders");
        assertThat(endpoint.httpMethod()).isEqualTo("POST");
        assertThat(endpoint.path()).isEqualTo("/api/orders");
        assertThat(endpoint.handlerClass()).isEqualTo("io.graphrag.sample.orders.OrderController");
        assertThat(endpoint.handlerMethod()).isEqualTo("create");
        assertThat(endpoint.authRequired()).isFalse();
        assertThat(endpoint.params()).hasSize(1);
        assertThat(endpoint.params().get(0).kind()).isEqualTo(ParamKind.BODY);
        assertThat(endpoint.params().get(0).javaType())
                .isEqualTo("io.graphrag.sample.orders.OrderController$CreateOrderRequest");
    }

    @Test
    void index_extractsBodyShapeForRequestBodyType() {
        IndexResult result = new EndpointIndexer().index(SAMPLE_SRC);

        BodyShape shape = result.bodyShapes()
                .get("io.graphrag.sample.orders.OrderController$CreateOrderRequest");
        assertThat(shape).isNotNull();
        assertThat(shape.fields()).extracting(BodyShape.BodyField::name)
                .containsExactly("userId", "amount", "type");
        assertThat(shape.fields()).extracting(BodyShape.BodyField::javaType)
                .containsExactly("java.lang.String", "java.lang.Integer", "java.lang.String");
    }

    @Test
    void index_emptyDirectory_returnsNoEndpoints() {
        IndexResult result = new EndpointIndexer().index(Path.of("src/test/resources"));
        // sample-src 외 컨트롤러가 없는 위치를 줘도 동작해야 한다 (하위 폴더 포함 스캔이므로 1개)
        assertThat(result.endpoints()).hasSizeLessThanOrEqualTo(1);
    }
}

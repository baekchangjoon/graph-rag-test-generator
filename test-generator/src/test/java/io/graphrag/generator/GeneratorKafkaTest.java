package io.graphrag.generator;

import io.graphrag.generator.client.GraphRagClient;
import io.graphrag.model.BindingOrigin;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.GenerationRequest;
import io.graphrag.model.GeneratedFile;
import io.graphrag.model.GenerationResult;
import io.graphrag.model.Json;
import io.graphrag.model.KafkaConsumer;
import io.graphrag.model.KafkaExchange;
import io.graphrag.model.SqlBinding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratorKafkaTest {

    /** kafka consumer + INSERT side-effect를 가진 최소 그래프를 제공하는 테스트 클라이언트. */
    private static GraphRagClient client() {
        var payload = Json.mapper().createObjectNode().put("eventId", "e1").put("userId", "u1");
        var consumer = new KafkaConsumer("kafka-order-events", "order.events", "g",
                "x.OrderEventConsumer", "onOrderEvent", "x.OrderEventPayload");
        var exchange = new KafkaExchange("kafka-order-events-x1", "kafka-order-events", "order.events",
                payload, List.of("sql-1"));
        // 실제 JPA INSERT 컬럼 순서(type, user_id, id) — PK(id)는 첫 컬럼이 아니다.
        var insert = new CapturedSql("sql-1", "kafka-order-events-x1", "INSERT",
                "insert into order_events (type, user_id, id) values (?, ?, ?)", "order_events",
                List.of(new SqlBinding(1, "type", "t1", BindingOrigin.API_PARAM, "order_events"),
                        new SqlBinding(2, "user_id", "u1", BindingOrigin.API_PARAM, "order_events"),
                        new SqlBinding(3, "id", "e1", BindingOrigin.API_PARAM, "order_events")));
        var orderEventsTable = new io.graphrag.model.TableSchema("order_events",
                List.of(new io.graphrag.model.ColumnSchema("id", "VARCHAR", false, true),
                        new io.graphrag.model.ColumnSchema("type", "VARCHAR", false, false),
                        new io.graphrag.model.ColumnSchema("user_id", "VARCHAR", false, false)),
                List.of(), List.of());
        return new GraphRagClient() {
            public io.graphrag.model.Endpoint endpoint(String id) { throw new UnsupportedOperationException(); }
            public io.graphrag.model.ExploredPath path(String id) { throw new UnsupportedOperationException(); }
            public List<io.graphrag.model.ExploredPath> pathsForEndpoint(String e) { return List.of(); }
            public List<CapturedSql> sqlForPath(String pathId) {
                return pathId.equals("kafka-order-events-x1") ? List.of(insert) : List.of();
            }
            public List<io.graphrag.model.CapturedHttpCall> httpCallsForPath(String p) { return List.of(); }
            public boolean hasWsEndpoint(String id) { return false; }
            public io.graphrag.model.WsEndpoint wsEndpoint(String id) { throw new UnsupportedOperationException(); }
            public List<io.graphrag.model.WsExchange> wsExchangesFor(String w) { return List.of(); }
            public io.graphrag.model.WsExchange wsExchange(String id) { throw new UnsupportedOperationException(); }
            public boolean hasKafkaConsumer(String id) { return id.equals("kafka-order-events"); }
            public KafkaConsumer kafkaConsumer(String id) { return consumer; }
            public List<KafkaExchange> kafkaExchangesFor(String c) { return List.of(exchange); }
            public List<io.graphrag.model.TableSchema> tables() { return List.of(orderEventsTable); }
            public List<io.graphrag.model.RequiredSeed> seedsForPath(String p) { return List.of(); }
        };
    }

    @Test
    void generateKafka_publishesAndPollsForConsumerInsert() {
        GenerationResult result = new Generator(client()).generate(
                new GenerationRequest("kafka-order-events", null, "OrderEventConsumerTest", "io.x",
                        io.graphrag.model.AuthMode.REAL));

        assertThat(result.files()).hasSize(1);
        GeneratedFile file = result.files().get(0);
        assertThat(file.relativePath()).isEqualTo("io/x/OrderEventConsumerTest_X1.java");
        String src = file.content();
        assertThat(src).contains("KafkaHelper kafka = scope.kafka();");
        assertThat(src).contains("kafka.send(\"order.events\"");
        assertThat(src).contains("SELECT count(*) FROM order_events WHERE id = ?");
        assertThat(src).contains("\"e1\"");                       // INSERT PK 바인딩 값으로 side-effect 폴링
        assertThat(src).contains("pollUntilExists");
        assertThat(src).contains("@Execution(ExecutionMode.SAME_THREAD)");
        // 공유 토픽이라 직렬 마킹
        assertThat(result.parallelSafety().serialRequired())
                .extracting(io.graphrag.model.SerialRequired::test).contains("OrderEventConsumerTest_X1");
    }
}

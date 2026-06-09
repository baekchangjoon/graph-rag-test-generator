package io.graphrag.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JsonRoundTripTest {

    private final ObjectMapper mapper = Json.mapper();

    private <T> T roundTrip(T value, Class<T> type) throws Exception {
        String json = mapper.writeValueAsString(value);
        return mapper.readValue(json, type);
    }

    @Test
    void graphAsset_roundTrips() throws Exception {
        Endpoint endpoint = new Endpoint(
                "ep-orders-post", "POST", "/api/orders",
                "com.example.OrderController", "create",
                List.of(new EndpointParam("request", "com.example.CreateOrderRequest", ParamKind.BODY)),
                false);

        JsonNode sampleInput = mapper.readTree("{\"userId\":\"u-1\",\"amount\":100,\"type\":\"EXPRESS\"}");
        JsonNode sampleResponse = mapper.readTree("{\"id\":1,\"status\":\"PENDING\"}");
        ExploredPath path = new ExploredPath(
                "path-1", "ep-orders-post", sampleInput, 201, sampleResponse,
                List.of("sql-1", "sql-2"));

        CapturedSql sql = new CapturedSql(
                "sql-1", "path-1", "INSERT",
                "insert into orders (amount,status,type,user_id) values (?,?,?,?)",
                "orders",
                List.of(
                        new SqlBinding(1, "amount", "100", BindingOrigin.API_PARAM),
                        new SqlBinding(2, "status", "PENDING", BindingOrigin.LITERAL),
                        new SqlBinding(3, "type", "EXPRESS", BindingOrigin.API_PARAM),
                        new SqlBinding(4, "user_id", "u-1", BindingOrigin.API_PARAM)));

        TableSchema table = new TableSchema(
                "orders",
                List.of(
                        new ColumnSchema("id", "BIGINT", false, true),
                        new ColumnSchema("user_id", "VARCHAR", false, false)),
                List.of(new ForeignKey("user_id", "users", "id")),
                List.of(List.of("id")));

        GraphAsset asset = new GraphAsset(
                "order-service", "abc123",
                List.of(endpoint), List.of(path), List.of(sql), List.of(table));

        assertThat(roundTrip(asset, GraphAsset.class)).isEqualTo(asset);
    }

    @Test
    void generationRequest_roundTrips() throws Exception {
        GenerationRequest request = new GenerationRequest(
                "ep-orders-post", "path-1", "OrdersPostTest", "io.graphrag.generated", AuthMode.DISABLED);
        assertThat(roundTrip(request, GenerationRequest.class)).isEqualTo(request);
    }

    @Test
    void generationResult_roundTrips() throws Exception {
        GenerationResult result = new GenerationResult(
                List.of(new GeneratedFile("io/graphrag/generated/OrdersPostTest.java", "class A {}")),
                List.of("warning-1"),
                new ParallelSafetyReport(
                        List.of("OrdersPostTest"),
                        List.of(new SerialRequired("OtherTest", "SOCKET_NO_SESSION", "no session field"))));
        assertThat(roundTrip(result, GenerationResult.class)).isEqualTo(result);
    }

    @Test
    void testEvent_roundTrips() throws Exception {
        TestEvent event = new TestEvent(
                EventType.DB_ROW_INSERTED, "t-a1b2c3d4", "run-1",
                Instant.parse("2026-06-10T00:00:00Z"),
                mapper.readTree("{\"table\":\"users\",\"keyColumn\":\"id\",\"keyValue\":\"t-a1b2c3d4-user\"}"));
        assertThat(roundTrip(event, TestEvent.class)).isEqualTo(event);
    }

    @Test
    void unknownJsonFields_areIgnored() throws Exception {
        String json = "{\"endpointId\":\"ep\",\"pathId\":\"p\",\"testClassName\":\"T\","
                + "\"packageName\":\"x\",\"authMode\":\"DISABLED\",\"futureField\":1}";
        GenerationRequest request = mapper.readValue(json, GenerationRequest.class);
        assertThat(request.endpointId()).isEqualTo("ep");
    }
}

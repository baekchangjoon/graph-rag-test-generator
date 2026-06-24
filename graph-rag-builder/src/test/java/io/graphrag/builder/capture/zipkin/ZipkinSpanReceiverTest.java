package io.graphrag.builder.capture.zipkin;
import io.graphrag.builder.capture.otlp.SpanRecord;
import org.junit.jupiter.api.*;
import java.net.URI; import java.net.http.*;
import static org.assertj.core.api.Assertions.assertThat;
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ZipkinSpanReceiverTest {
    private ZipkinSpanReceiver r; private final HttpClient http=HttpClient.newHttpClient();
    @BeforeAll void s(){ r=new ZipkinSpanReceiver(); r.start(); }
    @AfterAll void e(){ r.close(); }
    private int post(String b) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(r.endpoint()+"/api/v2/spans"))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(b)).build(),
            HttpResponse.BodyHandlers.ofString()).statusCode(); }
    @Test @DisplayName("REQ-006: ingest CLIENT span, micros→nanos")
    void ingest() throws Exception {
        String t="1".repeat(32);
        assertThat(post("[{\"traceId\":\""+t+"\",\"id\":\"2222222222222222\",\"parentId\":\"3333333333333333\","
            +"\"kind\":\"CLIENT\",\"name\":\"post\",\"timestamp\":1700000000000000,"
            +"\"tags\":{\"http.method\":\"POST\",\"http.path\":\"/reservations\"}}]")).isEqualTo(202);
        var spans=r.spans(t); assertThat(spans).hasSize(1);
        assertThat(spans.get(0).kind()).isEqualTo("CLIENT");
        assertThat(spans.get(0).startUnixNano()).isEqualTo(1700000000000000L*1000);
    }
    @Test @DisplayName("REQ-006: reject non-32hex traceId")
    void reject() throws Exception { post("[{\"traceId\":\"XYZ\",\"id\":\"2\",\"kind\":\"CLIENT\",\"tags\":{\"http.method\":\"GET\",\"http.path\":\"/x\"}}]");
        assertThat(r.spans("XYZ")).isEmpty(); }
}

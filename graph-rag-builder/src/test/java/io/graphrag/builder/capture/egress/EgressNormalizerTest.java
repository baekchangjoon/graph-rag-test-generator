package io.graphrag.builder.capture.egress;
import io.graphrag.builder.capture.otlp.SpanRecord;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
class EgressNormalizerTest {
    private static SpanRecord span(String kind, Map<String,String> a){
        return new SpanRecord("a".repeat(32),"b".repeat(16),"c".repeat(16),"post",kind,123L,a,List.of()); }
    @Test @DisplayName("REQ-001/003: otel new semconv, query strip, status null")
    void otelNew(){ var e=EgressNormalizer.fromSpan(span("SPAN_KIND_CLIENT",
        Map.of("http.request.method","GET","url.path","/inventory/stock","url.query","type=X"))).orElseThrow();
        assertThat(e.method()).isEqualTo("GET"); assertThat(e.path()).isEqualTo("/inventory/stock"); assertThat(e.statusOrNull()).isNull(); }
    @Test @DisplayName("REQ-003: otel old semconv http.url fallback + status")
    void otelOldHttpUrl(){ var e=EgressNormalizer.fromSpan(span("SPAN_KIND_CLIENT",
        Map.of("http.method","GET","http.url","http://inventory-svc:8089/inventory/stock?type=X","http.status_code","200"))).orElseThrow();
        assertThat(e.path()).isEqualTo("/inventory/stock"); assertThat(e.statusOrNull()).isEqualTo(200); }
    @Test @DisplayName("REQ-003: otel new semconv url.full fallback")
    void otelUrlFull(){ var e=EgressNormalizer.fromSpan(span("SPAN_KIND_CLIENT",
        Map.of("http.request.method","GET","url.full","http://inv:8080/inventory/stock?type=X"))).orElseThrow();
        assertThat(e.path()).isEqualTo("/inventory/stock"); }
    @Test @DisplayName("REQ-002: zipkin CLIENT path-only")
    void zipkin(){ var e=EgressNormalizer.fromSpan(span("CLIENT",
        Map.of("http.method","POST","http.path","/reservations"))).orElseThrow();
        assertThat(e.method()).isEqualTo("POST"); assertThat(e.path()).isEqualTo("/reservations"); }
    @Test @DisplayName("REQ-003: error status tag")
    void err(){ assertThat(EgressNormalizer.fromSpan(span("CLIENT",
        Map.of("http.method","POST","http.path","/reservations","http.status_code","500","error","500"))).orElseThrow().statusOrNull()).isEqualTo(500); }
    @Test @DisplayName("REQ-003: non-http CLIENT excluded")
    void nonHttp(){ assertThat(EgressNormalizer.fromSpan(span("CLIENT",Map.of("rpc.method","Check")))).isEmpty(); }
    @Test @DisplayName("REQ-001: server span excluded")
    void server(){ assertThat(EgressNormalizer.fromSpan(span("SPAN_KIND_SERVER",Map.of("http.method","GET","http.path","/x")))).isEmpty(); }
    @Test @DisplayName("REQ-002: no path excluded")
    void noPath(){ assertThat(EgressNormalizer.fromSpan(span("CLIENT",Map.of("http.method","GET")))).isEmpty(); }
}

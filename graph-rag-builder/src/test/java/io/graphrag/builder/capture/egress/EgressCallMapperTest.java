package io.graphrag.builder.capture.egress;
import io.graphrag.model.CapturedHttpCall;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
class EgressCallMapperTest {
    @Test @DisplayName("REQ-005: defaults & real field names")
    void defaults(){ var c=EgressCallMapper.toCapturedHttpCall(new EgressCall("GET","/inventory/stock",null,"t",1L),"p1",1);
        assertThat(c.method()).isEqualTo("GET"); assertThat(c.urlPath()).isEqualTo("/inventory/stock");
        assertThat(c.responseStatus()).isEqualTo(200); assertThat(c.responseBody()).isEqualTo("");
        assertThat(c.requestBody()).isNull(); assertThat(c.query()).isEmpty();
        assertThat(c.baggagePropagated()).isFalse(); assertThat(c.responseProvenance()).isEqualTo(CapturedHttpCall.Provenance.CAPTURED);
        assertThat(c.consumedFields()).isEmpty(); }
    @Test @DisplayName("REQ-005: error status kept")
    void err(){ assertThat(EgressCallMapper.toCapturedHttpCall(new EgressCall("POST","/r",500,"t",1L),"p",1).responseStatus()).isEqualTo(500); }
    @Test @DisplayName("REQ-005: per-request dedup by (method,urlPath), redirect first")
    void dedup(){ var redirect=EgressCallMapper.toCapturedHttpCall(new EgressCall("POST","/reservations",202,"t",1L),"p",1);
        var span=EgressCallMapper.toCapturedHttpCall(new EgressCall("POST","/reservations",null,"t",2L),"p",2);
        var merged=EgressCallMapper.mergeDedup(List.of(redirect),List.of(span));
        assertThat(merged).hasSize(1); assertThat(merged.get(0).responseStatus()).isEqualTo(202); }
}

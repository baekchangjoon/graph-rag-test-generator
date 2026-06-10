package io.graphrag.testlib.stomp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StompFramesTest {

    @Test
    void encode_producesStompFrame() {
        String encoded = StompFrames.encode(new StompFrames.Frame(
                "SEND",
                Map.of("destination", "/app/orders/count"),
                "{\"userId\":\"u-1\"}"));

        assertThat(encoded).isEqualTo(
                "SEND\ndestination:/app/orders/count\n\n{\"userId\":\"u-1\"}\u0000");
    }

    @Test
    void decode_parsesFramesAndIgnoresHeartbeats() {
        String wire = "CONNECTED\nversion:1.2\n\n\u0000"
                + "\n"   // heart-beat
                + "MESSAGE\ndestination:/topic/orders\ncontent-type:application/json\n\n"
                + "{\"count\":2}\u0000";

        List<StompFrames.Frame> frames = StompFrames.decode(wire);

        assertThat(frames).hasSize(2);
        assertThat(frames.get(0).command()).isEqualTo("CONNECTED");
        assertThat(frames.get(1).command()).isEqualTo("MESSAGE");
        assertThat(frames.get(1).headers()).containsEntry("destination", "/topic/orders");
        assertThat(frames.get(1).body()).isEqualTo("{\"count\":2}");
    }

    @Test
    void roundTrip_preservesFrame() {
        StompFrames.Frame frame = new StompFrames.Frame(
                "SUBSCRIBE", Map.of("id", "sub-0", "destination", "/topic/orders"), "");
        List<StompFrames.Frame> decoded = StompFrames.decode(StompFrames.encode(frame));
        assertThat(decoded).hasSize(1);
        assertThat(decoded.get(0).command()).isEqualTo("SUBSCRIBE");
        assertThat(decoded.get(0).headers()).isEqualTo(frame.headers());
    }
}

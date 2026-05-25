package io.graphrag.builder.capture.socket;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link ProtocolDecoder}들을 endpoint별로 매칭하여 적용.
 *
 * <p>현재는 빈 registry. 프로토콜 사양 확보 시 디코더 추가 (Phase 5+).
 */
public final class ProtocolDecoderRegistry {

    private final List<ProtocolDecoder> decoders = new ArrayList<>();

    public synchronized void register(ProtocolDecoder decoder) {
        decoders.add(decoder);
    }

    public synchronized Optional<Object> decode(String hostPort, byte[] bytes) {
        for (ProtocolDecoder d : decoders) {
            if (d.matches(hostPort)) {
                Optional<Object> result = d.decode(bytes);
                if (result.isPresent()) return result;
            }
        }
        return Optional.empty();
    }

    public synchronized int size() {
        return decoders.size();
    }

    public synchronized void clear() {
        decoders.clear();
    }
}

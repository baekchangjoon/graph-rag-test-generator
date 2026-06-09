package io.graphrag.socketmock;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 포트별 expectation 보관 + byte 매칭. */
@Component
public class ExpectationRegistry {

    private final Map<Integer, List<Expectation>> byPort = new ConcurrentHashMap<>();

    public void register(Expectation expectation) {
        byPort.computeIfAbsent(expectation.listenPort(), p -> new CopyOnWriteArrayList<>())
                .add(expectation);
    }

    public Optional<byte[]> match(int port, byte[] received) {
        List<Expectation> expectations = byPort.getOrDefault(port, List.of());
        for (Expectation expectation : expectations) {
            byte[] pattern = HexCodec.parse(expectation.onReceiveHex());
            boolean matched = switch (expectation.matchMode()) {
                case EXACT -> Arrays.equals(received, pattern);
                case PREFIX -> received.length >= pattern.length
                        && Arrays.equals(Arrays.copyOf(received, pattern.length), pattern);
            };
            if (matched) {
                return Optional.of(HexCodec.parse(expectation.respondWithHex()));
            }
        }
        return Optional.empty();
    }

    public Set<Integer> ports() {
        return byPort.keySet();
    }

    public void clear() {
        byPort.clear();
    }
}

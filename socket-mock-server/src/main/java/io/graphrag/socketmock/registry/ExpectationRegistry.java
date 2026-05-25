package io.graphrag.socketmock.registry;

import io.graphrag.socketmock.domain.Expectation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 포트별 expectation 저장 + 매칭.
 *
 * <p>매칭은 prefix 기반: 수신 byte의 시작이 expectation의 onReceiveBytes와 같으면 매치.
 * 같은 포트에 여러 expectation이 있으면 stepOrder 오름차순으로 평가.
 */
@Component
public class ExpectationRegistry {

    private final ConcurrentMap<Integer, List<Expectation>> byPort = new ConcurrentHashMap<>();

    public void register(Expectation e) {
        byPort.computeIfAbsent(e.port(), k -> new ArrayList<>()).add(e);
    }

    public List<Expectation> expectationsForPort(int port) {
        List<Expectation> list = byPort.get(port);
        return list == null ? List.of() : List.copyOf(list);
    }

    public Optional<Expectation> findMatch(int port, byte[] received) {
        List<Expectation> list = byPort.get(port);
        if (list == null) return Optional.empty();
        return list.stream()
                .filter(e -> startsWith(received, e.onReceiveBytes()))
                .min(Comparator.comparingInt(Expectation::stepOrder));
    }

    public void removeSession(String sessionId) {
        for (List<Expectation> list : byPort.values()) {
            list.removeIf(e -> e.sessionId().equals(sessionId));
        }
    }

    public void clear() { byPort.clear(); }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (prefix.length > data.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
    }
}

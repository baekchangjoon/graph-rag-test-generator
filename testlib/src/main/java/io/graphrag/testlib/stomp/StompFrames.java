package io.graphrag.testlib.stomp;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 최소 STOMP 1.2 프레임 코덱. CONNECT/SUBSCRIBE/SEND/CONNECTED/MESSAGE만 다룬다.
 * spring-messaging 클라이언트 의존을 피하기 위한 자체 구현 (docs/decisions/stomp-capture.md).
 */
public final class StompFrames {

    public record Frame(String command, Map<String, String> headers, String body) {
    }

    private StompFrames() {
    }

    public static String encode(Frame frame) {
        StringBuilder out = new StringBuilder(frame.command()).append('\n');
        // 헤더 순서 결정성: 키 정렬
        new TreeMap<>(frame.headers()).forEach((key, value) ->
                out.append(key).append(':').append(value).append('\n'));
        return out.append('\n').append(frame.body()).append('\u0000').toString();
    }

    public static List<Frame> decode(String wire) {
        List<Frame> frames = new ArrayList<>();
        for (String chunk : wire.split("\u0000")) {
            String text = chunk.strip();
            if (text.isEmpty()) {
                continue;   // heart-beat
            }
            int headerEnd = text.indexOf("\n\n");
            String head = headerEnd >= 0 ? text.substring(0, headerEnd) : text;
            String body = headerEnd >= 0 ? text.substring(headerEnd + 2) : "";
            String[] lines = head.split("\n");
            Map<String, String> headers = new LinkedHashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) {
                    headers.put(lines[i].substring(0, colon), lines[i].substring(colon + 1));
                }
            }
            frames.add(new Frame(lines[0], headers, body));
        }
        return frames;
    }
}

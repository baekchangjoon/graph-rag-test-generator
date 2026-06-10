package io.graphrag.testlib.api;

import io.graphrag.testlib.stomp.StompFrames;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 생성 테스트용 최소 STOMP 클라이언트 (JDK WebSocket 기반, 의존성 0).
 * broker 토픽은 broadcast이므로 awaitMessageContaining의 마커(testId 기반
 * unique 값)로 자기 메시지만 구분한다 (docs/06 병렬 격리의 WS 형태).
 */
public final class StompHelper implements AutoCloseable {

    private final WebSocket webSocket;
    private final BlockingQueue<StompFrames.Frame> frames = new LinkedBlockingQueue<>();
    private final AtomicInteger subscriptionSeq = new AtomicInteger();
    private final StringBuilder partial = new StringBuilder();

    private StompHelper(WebSocket webSocket) {
        this.webSocket = webSocket;
    }

    public static StompHelper connect(String httpBaseUri, String wsPath, Duration timeout) {
        String wsUri = httpBaseUri.replaceFirst("^http", "ws") + wsPath;
        var holder = new Object() {
            StompHelper helper;
        };
        WebSocket.Listener listener = new WebSocket.Listener() {
            @Override
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                holder.helper.onText(data, last);
                ws.request(1);
                return null;
            }
        };
        WebSocket webSocket = HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(timeout)
                .buildAsync(URI.create(wsUri), listener)
                .join();
        StompHelper helper = new StompHelper(webSocket);
        holder.helper = helper;
        webSocket.request(1);

        helper.sendFrame(new StompFrames.Frame("CONNECT",
                Map.of("accept-version", "1.2", "heart-beat", "0,0", "host", "localhost"), ""));
        StompFrames.Frame connected = helper.awaitFrame("CONNECTED", null, timeout);
        if (connected == null) {
            helper.close();
            throw new IllegalStateException("STOMP CONNECTED not received from " + wsUri);
        }
        return helper;
    }

    private synchronized void onText(CharSequence data, boolean last) {
        partial.append(data);
        if (last) {
            StompFrames.decode(partial.toString()).forEach(frames::offer);
            partial.setLength(0);
        }
    }

    public void subscribe(String destination) {
        sendFrame(new StompFrames.Frame("SUBSCRIBE",
                Map.of("id", "sub-" + subscriptionSeq.incrementAndGet(),
                        "destination", destination), ""));
    }

    public void send(String destination, String jsonBody) {
        sendFrame(new StompFrames.Frame("SEND",
                Map.of("destination", destination, "content-type", "application/json"),
                jsonBody));
    }

    /** marker를 포함하는 MESSAGE body를 기다린다. 다른 스코프의 메시지는 무시. */
    public String awaitMessageContaining(String marker, Duration timeout) {
        StompFrames.Frame frame = awaitFrame("MESSAGE", marker, timeout);
        return frame == null ? null : frame.body();
    }

    private StompFrames.Frame awaitFrame(String command, String bodyMarker, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            try {
                StompFrames.Frame frame = frames.poll(100, TimeUnit.MILLISECONDS);
                if (frame != null && frame.command().equals(command)
                        && (bodyMarker == null || frame.body().contains(bodyMarker))) {
                    return frame;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private void sendFrame(StompFrames.Frame frame) {
        webSocket.sendText(StompFrames.encode(frame), true).join();
    }

    @Override
    public void close() {
        try {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done")
                    .orTimeout(2, TimeUnit.SECONDS).join();
        } catch (Exception ignored) {
            webSocket.abort();
        }
    }
}

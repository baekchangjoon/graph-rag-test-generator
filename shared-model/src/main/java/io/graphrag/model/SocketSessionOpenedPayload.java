package io.graphrag.model;

import java.util.Objects;

/**
 * {@link DashboardEventType#SOCKET_SESSION_OPENED} payload.
 */
public record SocketSessionOpenedPayload(String sessionId, String mockHost, int mockPort) {
    public SocketSessionOpenedPayload {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(mockHost, "mockHost");
    }
}

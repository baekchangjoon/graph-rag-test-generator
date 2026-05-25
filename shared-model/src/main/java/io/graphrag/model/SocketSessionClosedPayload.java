package io.graphrag.model;

import java.util.Objects;

/**
 * {@link DashboardEventType#SOCKET_SESSION_CLOSED} payload.
 */
public record SocketSessionClosedPayload(String sessionId) {
    public SocketSessionClosedPayload {
        Objects.requireNonNull(sessionId, "sessionId");
    }
}

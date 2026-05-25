package io.graphrag.model;

import java.time.Instant;
import java.util.Objects;

/**
 * {@link DashboardEventType#AUTH_TOKEN_ISSUED} payload.
 *
 * @param expiresAt 토큰 만료 시각. 알 수 없거나 무기한이면 null.
 */
public record AuthTokenIssuedPayload(String tokenKind, Instant expiresAt) {
    public AuthTokenIssuedPayload {
        Objects.requireNonNull(tokenKind, "tokenKind");
    }
}

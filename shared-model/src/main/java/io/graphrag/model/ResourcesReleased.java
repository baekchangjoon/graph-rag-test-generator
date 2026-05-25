package io.graphrag.model;

/**
 * {@link ScopeCleanedPayload}의 자원 해제 통계.
 */
public record ResourcesReleased(int dbRows, int httpStubs, int socketSessions) {}

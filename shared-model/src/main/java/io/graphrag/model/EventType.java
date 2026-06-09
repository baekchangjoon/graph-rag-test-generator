package io.graphrag.model;

/** testlib → dashboard 이벤트 유형 (docs/08). */
public enum EventType {
    SCOPE_CREATED,
    SCOPE_CLEANED,
    DB_ROW_INSERTED,
    DB_ROW_DELETED,
    HTTP_STUB_REGISTERED,
    HTTP_STUB_REMOVED,
    SOCKET_SESSION_OPENED,
    SOCKET_SESSION_CLOSED,
    AUTH_TOKEN_ISSUED
}

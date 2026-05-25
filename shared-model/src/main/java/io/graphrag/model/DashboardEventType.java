package io.graphrag.model;

/**
 * 대시보드 이벤트 종류. SCHEMAS.md의 DashboardEvent.type 참조.
 */
public enum DashboardEventType {
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

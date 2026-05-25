package io.graphrag.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardEventsTest {

    private final ObjectMapper mapper = JsonMappers.standard();

    @Test
    void dashboardEventTypeContainsAllNineValues() {
        assertThat(DashboardEventType.values())
                .contains(
                        DashboardEventType.SCOPE_CREATED,
                        DashboardEventType.SCOPE_CLEANED,
                        DashboardEventType.DB_ROW_INSERTED,
                        DashboardEventType.DB_ROW_DELETED,
                        DashboardEventType.HTTP_STUB_REGISTERED,
                        DashboardEventType.HTTP_STUB_REMOVED,
                        DashboardEventType.SOCKET_SESSION_OPENED,
                        DashboardEventType.SOCKET_SESSION_CLOSED,
                        DashboardEventType.AUTH_TOKEN_ISSUED);
    }

    @Test
    void scopeCreatedEventRoundTrip() throws Exception {
        DashboardEvent ev = new DashboardEvent(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                DashboardEventType.SCOPE_CREATED,
                "ordpost-a1b2c3",
                Instant.parse("2026-05-25T12:00:00Z"),
                new ScopeCreatedPayload("OrdersPostTest", "createOrder", "run-1"));

        String json = mapper.writeValueAsString(ev);
        DashboardEvent back = mapper.readValue(json, DashboardEvent.class);
        ScopeCreatedPayload p = mapper.convertValue(back.payload(), ScopeCreatedPayload.class);

        assertThat(back.type()).isEqualTo(DashboardEventType.SCOPE_CREATED);
        assertThat(back.testId()).isEqualTo("ordpost-a1b2c3");
        assertThat(p.testClass()).isEqualTo("OrdersPostTest");
        assertThat(p.testMethod()).isEqualTo("createOrder");
        assertThat(p.runId()).isEqualTo("run-1");
        assertThat(json).contains("\"test_id\":\"ordpost-a1b2c3\"");
        assertThat(json).contains("\"event_id\":");
    }

    @Test
    void scopeCleanedEventRoundTrip() throws Exception {
        DashboardEvent ev = new DashboardEvent(
                UUID.randomUUID(),
                DashboardEventType.SCOPE_CLEANED,
                "test-1",
                Instant.now(),
                new ScopeCleanedPayload(new ResourcesReleased(3, 2, 0)));

        String json = mapper.writeValueAsString(ev);
        DashboardEvent back = mapper.readValue(json, DashboardEvent.class);
        ScopeCleanedPayload p = mapper.convertValue(back.payload(), ScopeCleanedPayload.class);

        assertThat(p.resourcesReleased().dbRows()).isEqualTo(3);
        assertThat(p.resourcesReleased().httpStubs()).isEqualTo(2);
        assertThat(p.resourcesReleased().socketSessions()).isZero();
        assertThat(json).contains("\"resources_released\":");
        assertThat(json).contains("\"db_rows\":3");
    }

    @Test
    void dbRowInsertedAndDeletedRoundTrip() throws Exception {
        DbRowInsertedPayload ins = new DbRowInsertedPayload("users", "id", "u-1");
        DbRowDeletedPayload del = new DbRowDeletedPayload("users", "id", "u-1");

        DashboardEvent insEvent = new DashboardEvent(
                UUID.randomUUID(), DashboardEventType.DB_ROW_INSERTED,
                "t", Instant.now(), ins);
        DashboardEvent delEvent = new DashboardEvent(
                UUID.randomUUID(), DashboardEventType.DB_ROW_DELETED,
                "t", Instant.now(), del);

        String insJson = mapper.writeValueAsString(insEvent);
        String delJson = mapper.writeValueAsString(delEvent);

        assertThat(insJson).contains("\"key_column\":\"id\"").contains("\"key_value\":\"u-1\"");
        assertThat(delJson).contains("\"key_column\":\"id\"").contains("\"key_value\":\"u-1\"");

        DashboardEvent insBack = mapper.readValue(insJson, DashboardEvent.class);
        DbRowInsertedPayload insP = mapper.convertValue(insBack.payload(), DbRowInsertedPayload.class);
        assertThat(insP).isEqualTo(ins);
    }

    @Test
    void httpStubAndSocketAndAuthPayloads() throws Exception {
        HttpStubRegisteredPayload h = new HttpStubRegisteredPayload(
                "stub-1", "/inventory/stock", "ordpost-a1b2c3");
        SocketSessionOpenedPayload s = new SocketSessionOpenedPayload(
                "sess-1", "inventory", 9000);
        AuthTokenIssuedPayload a = new AuthTokenIssuedPayload("bearer", Instant.parse("2026-05-25T13:00:00Z"));

        String hJson = mapper.writeValueAsString(h);
        String sJson = mapper.writeValueAsString(s);
        String aJson = mapper.writeValueAsString(a);

        assertThat(hJson).contains("\"url_pattern\":");
        assertThat(hJson).contains("\"scope_baggage_value\":");
        assertThat(sJson).contains("\"mock_host\":\"inventory\"");
        assertThat(sJson).contains("\"mock_port\":9000");
        assertThat(aJson).contains("\"token_kind\":\"bearer\"");
        assertThat(aJson).contains("\"expires_at\":");

        assertThat(mapper.readValue(hJson, HttpStubRegisteredPayload.class)).isEqualTo(h);
        assertThat(mapper.readValue(sJson, SocketSessionOpenedPayload.class)).isEqualTo(s);
        assertThat(mapper.readValue(aJson, AuthTokenIssuedPayload.class)).isEqualTo(a);
    }

    @Test
    void authTokenIssuedAcceptsNullExpiry() throws Exception {
        AuthTokenIssuedPayload a = new AuthTokenIssuedPayload("bearer", null);

        String json = mapper.writeValueAsString(a);
        AuthTokenIssuedPayload back = mapper.readValue(json, AuthTokenIssuedPayload.class);

        assertThat(back.expiresAt()).isNull();
    }
}

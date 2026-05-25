package io.graphrag.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphrag.model.DashboardEvent;
import io.graphrag.model.DashboardEventType;
import io.graphrag.model.DbRowInsertedPayload;
import io.graphrag.model.ScopeCleanedPayload;
import io.graphrag.model.ScopeCreatedPayload;
import io.graphrag.model.ResourcesReleased;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DashboardWebTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;

    @Test
    void postEventReturns202() throws Exception {
        DashboardEvent ev = new DashboardEvent(
                UUID.randomUUID(),
                DashboardEventType.SCOPE_CREATED,
                "web-test-1",
                Instant.now(),
                new ScopeCreatedPayload("WebTest", "post", "run-w"));

        mvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(ev)))
                .andExpect(status().isAccepted());
    }

    @Test
    void activeShowsRegisteredTests() throws Exception {
        DashboardEvent created = new DashboardEvent(
                UUID.randomUUID(),
                DashboardEventType.SCOPE_CREATED,
                "active-show-1",
                Instant.now(),
                new ScopeCreatedPayload("C", "m", "r"));

        mvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(created)))
                .andExpect(status().isAccepted());

        mvc.perform(get("/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.test_id == 'active-show-1')]").exists());
    }

    @Test
    void cleanupRemovesFromActive() throws Exception {
        String testId = "cleanup-flow";

        mvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new DashboardEvent(UUID.randomUUID(),
                                        DashboardEventType.SCOPE_CREATED,
                                        testId, Instant.now(),
                                        new ScopeCreatedPayload("C", "m", "r")))))
                .andExpect(status().isAccepted());

        mvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new DashboardEvent(UUID.randomUUID(),
                                        DashboardEventType.SCOPE_CLEANED,
                                        testId, Instant.now(),
                                        new ScopeCleanedPayload(new ResourcesReleased(0, 0, 0))))))
                .andExpect(status().isAccepted());

        mvc.perform(get("/test/" + testId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLEANED"));
    }

    @Test
    void tablesHoldersReturnsRowsForTable() throws Exception {
        String testId = "holders-test";

        mvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new DashboardEvent(UUID.randomUUID(),
                                        DashboardEventType.SCOPE_CREATED,
                                        testId, Instant.now(),
                                        new ScopeCreatedPayload("C", "m", "r")))))
                .andExpect(status().isAccepted());

        mvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new DashboardEvent(UUID.randomUUID(),
                                        DashboardEventType.DB_ROW_INSERTED,
                                        testId, Instant.now(),
                                        new DbRowInsertedPayload("users", "id", "holders-u-1")))))
                .andExpect(status().isAccepted());

        mvc.perform(get("/tables/users/holders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.owner_test_id == 'holders-test')].key_value").value("holders-u-1"));
    }

    @Test
    void unknownTestIdReturns404() throws Exception {
        mvc.perform(get("/test/nonexistent"))
                .andExpect(status().isNotFound());
    }
}

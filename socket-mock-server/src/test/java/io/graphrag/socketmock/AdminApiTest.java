package io.graphrag.socketmock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphrag.socketmock.api.ExpectationRequest;
import io.graphrag.socketmock.registry.ExpectationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private ExpectationRegistry registry;

    private int port1;
    private int port2;

    @BeforeEach
    void allocatePorts() throws Exception {
        registry.clear();
        try (ServerSocket s1 = new ServerSocket(0); ServerSocket s2 = new ServerSocket(0)) {
            port1 = s1.getLocalPort();
            port2 = s2.getLocalPort();
        }
    }

    @Test
    void registerExpectationReturnsIdAndAddsToRegistry() throws Exception {
        ExpectationRequest req = new ExpectationRequest(port1, "sess-1", "01 02", "FF", 0);

        mvc.perform(post("/__admin/expectations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.port").value(port1));

        assertThat(registry.expectationsForPort(port1)).hasSize(1);
    }

    @Test
    void deleteSessionRemovesAllForThatSession() throws Exception {
        mvc.perform(post("/__admin/expectations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(
                        new ExpectationRequest(port1, "sess-X", "01", "0F", 0))));
        mvc.perform(post("/__admin/expectations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(
                        new ExpectationRequest(port1, "sess-Y", "02", "0E", 0))));

        mvc.perform(delete("/__admin/sessions/sess-X"))
                .andExpect(status().isNoContent());

        assertThat(registry.expectationsForPort(port1)).hasSize(1);
        assertThat(registry.expectationsForPort(port1).get(0).sessionId()).isEqualTo("sess-Y");
    }

    @Test
    void clearWipesAllExpectations() throws Exception {
        mvc.perform(post("/__admin/expectations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(
                        new ExpectationRequest(port2, "s", "01", "FF", 0))));

        mvc.perform(delete("/__admin/expectations"))
                .andExpect(status().isNoContent());

        assertThat(registry.expectationsForPort(port2)).isEmpty();
    }
}

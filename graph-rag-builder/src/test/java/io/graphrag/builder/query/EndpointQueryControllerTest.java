package io.graphrag.builder.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphrag.builder.persistence.GraphArchive;
import io.graphrag.model.Endpoint;
import io.graphrag.model.HttpMethod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class EndpointQueryControllerTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void configureArchiveDir(DynamicPropertyRegistry r) {
        r.add("graph.archive.dir", () -> tmp.toString());
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private GraphArchive archive;

    @Test
    void getEndpointsReturnsList() throws Exception {
        archive.addEndpoint(new Endpoint(
                "POST:/api/orders", HttpMethod.POST, "/api/orders",
                "demo-sut", "OrdersController", "createOrder", false, List.of()));

        mvc.perform(get("/endpoints"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("POST:/api/orders"));
    }

    @Test
    void getEndpointByIdReturnsEndpoint() throws Exception {
        // Note: ID with slashes (e.g. "GET:/api/users") requires URL encoding by client.
        // 컨트롤러는 {id:.+} 정규식으로 슬래시를 허용. 테스트에서는 단순 ID로 검증.
        archive.addEndpoint(new Endpoint(
                "GET:api.users", HttpMethod.GET, "/api/users",
                "demo-sut", "UsersController", "list", false, List.of()));

        mvc.perform(get("/endpoints/{id}", "GET:api.users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handler_class").value("UsersController"));
    }

    @Test
    void getUnknownEndpointReturns404() throws Exception {
        mvc.perform(get("/endpoints/UNKNOWN"))
                .andExpect(status().isNotFound());
    }

    @Test
    void versionReturnsBuilderVersion() throws Exception {
        mvc.perform(get("/version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("0.1.0-SNAPSHOT"))
                .andExpect(jsonPath("$.schema_version").value(1));
    }
}

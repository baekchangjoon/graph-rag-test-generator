package io.graphrag.socketmock.api;

import io.graphrag.socketmock.domain.Expectation;
import io.graphrag.socketmock.registry.ExpectationRegistry;
import io.graphrag.socketmock.server.NettyServerManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Socket mock 서비스의 관리 REST API.
 */
@RestController
@RequestMapping("/__admin")
public class AdminController {

    private final ExpectationRegistry registry;
    private final NettyServerManager serverManager;

    public AdminController(ExpectationRegistry registry, NettyServerManager serverManager) {
        this.registry = registry;
        this.serverManager = serverManager;
    }

    @PostMapping("/expectations")
    public ResponseEntity<Map<String, Object>> register(@RequestBody ExpectationRequest req) {
        Expectation e = Expectation.builder()
                .port(req.port())
                .sessionId(req.sessionId())
                .onReceiveHex(req.onReceiveHex())
                .respondHex(req.respondHex())
                .stepOrder(req.stepOrder() == null ? 0 : req.stepOrder())
                .build();
        registry.register(e);
        int boundPort = serverManager.ensureBound(req.port());
        return ResponseEntity.ok(Map.of("id", e.id(), "port", boundPort));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> removeSession(@PathVariable String sessionId) {
        registry.removeSession(sessionId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/expectations")
    public ResponseEntity<Void> clear() {
        registry.clear();
        return ResponseEntity.noContent().build();
    }
}

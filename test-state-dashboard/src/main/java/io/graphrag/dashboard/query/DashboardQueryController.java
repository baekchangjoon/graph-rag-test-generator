package io.graphrag.dashboard.query;

import io.graphrag.dashboard.domain.TestRunState;
import io.graphrag.dashboard.store.TestRunRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 대시보드 조회 API.
 */
@RestController
public class DashboardQueryController {

    private final TestRunRegistry registry;

    public DashboardQueryController(TestRunRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/active")
    public List<TestRunState> active() {
        return registry.listActive();
    }

    @GetMapping("/leaked")
    public List<TestRunState> leaked() {
        return registry.listLeaked();
    }

    @GetMapping("/test/{testId}")
    public ResponseEntity<TestRunState> test(@PathVariable String testId) {
        return registry.get(testId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/tables/{name}/holders")
    public List<TestRunRegistry.TableRowHolder> holders(@PathVariable String name) {
        return registry.tableHolders(name);
    }
}

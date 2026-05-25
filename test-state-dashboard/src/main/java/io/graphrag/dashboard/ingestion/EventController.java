package io.graphrag.dashboard.ingestion;

import io.graphrag.dashboard.store.TestRunRegistry;
import io.graphrag.model.DashboardEvent;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * testlib가 보내는 이벤트 수신 + 등록.
 *
 * <p>실패 시에도 즉시 202 Accepted (fire-and-forget 컨벤션). 잘못된 형식만 400.
 */
@RestController
@RequestMapping("/events")
public class EventController {

    private final TestRunRegistry registry;

    public EventController(TestRunRegistry registry) {
        this.registry = registry;
    }

    @PostMapping
    public ResponseEntity<Void> postEvent(@RequestBody DashboardEvent event) {
        registry.handle(event);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/batch")
    public ResponseEntity<Void> postBatch(@RequestBody List<DashboardEvent> events) {
        for (DashboardEvent e : events) {
            registry.handle(e);
        }
        return ResponseEntity.accepted().build();
    }
}

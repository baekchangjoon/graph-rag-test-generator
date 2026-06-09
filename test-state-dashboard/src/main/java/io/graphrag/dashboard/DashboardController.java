package io.graphrag.dashboard;

import io.graphrag.model.TestEvent;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class DashboardController {

    private final TestRunStore store;

    public DashboardController(TestRunStore store) {
        this.store = store;
    }

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void receive(@RequestBody TestEvent event) {
        store.apply(event);
    }

    @GetMapping("/active")
    public List<TestRun> active() {
        return store.active();
    }

    @GetMapping("/leaked")
    public List<TestRun> leaked() {
        return store.leaked();
    }

    @GetMapping("/test/{testId}")
    public ResponseEntity<TestRun> find(@PathVariable String testId) {
        TestRun run = store.find(testId);
        return run == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(run);
    }
}

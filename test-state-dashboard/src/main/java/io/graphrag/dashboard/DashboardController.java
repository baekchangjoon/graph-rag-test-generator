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

    /** testId 형식 제한: 로그 인젝션 방지 + runs 키 카디널리티 제한. */
    private static final java.util.regex.Pattern TEST_ID =
            java.util.regex.Pattern.compile("[A-Za-z0-9._-]{1,128}");

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void receive(@RequestBody TestEvent event) {
        if (event.testId() == null || !TEST_ID.matcher(event.testId()).matches()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "invalid testId");
        }
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

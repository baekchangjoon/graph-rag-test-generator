package io.graphrag.fixture.recursive;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ProvenanceIndexerIT 픽스처 — 재귀 슬라이서의 depth cap·순환 종료 케이스.
 * run() → step1() → (methodA()↔methodB() 상호 재귀 | step2() → step3() → step4())
 * maxDepth=3으로 실행하면 step4() 호출(depth 4)이 DEPTH_CAP로 기록되어야 하고,
 * methodA()↔methodB() 순환은 방문 집합으로 자연 종료해야 한다(무한루프 없이).
 */
@RestController
@RequestMapping("/api/recursive")
public class RecursiveController {

    private final RecursiveService service;

    public RecursiveController(RecursiveService service) {
        this.service = service;
    }

    @GetMapping
    public String run() {
        return service.step1();
    }
}

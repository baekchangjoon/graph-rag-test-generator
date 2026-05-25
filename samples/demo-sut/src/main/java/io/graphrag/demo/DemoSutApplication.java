package io.graphrag.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Demo SUT (System Under Test) — Phase 0 PoC 대상.
 * 실제 entity, repository, controller는 Phase 0 E2E 작업에서 구현.
 */
@SpringBootApplication
public class DemoSutApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoSutApplication.class, args);
    }
}

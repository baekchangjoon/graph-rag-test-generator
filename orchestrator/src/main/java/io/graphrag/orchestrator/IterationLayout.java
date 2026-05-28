package io.graphrag.orchestrator;

import java.nio.file.Path;

/**
 * Stable per-iteration directory layout. Every stage's output lands at a
 * predictable path so a partial run can be inspected on disk without re-running.
 *
 * <pre>
 * &lt;out&gt;/
 *   iter-1/
 *     stage1-discovery/{paths,endpoints}.json
 *     stage2-config.yml
 *     stage3-archive/
 *       &lt;path-id&gt;/{captured_sql,paths,endpoints,captured_http}.json
 *       quarantine/...                                (if strict-mode quarantined)
 *     stage4-tests/&lt;package-path&gt;/*.java
 *     stage5-jacoco.xml
 *     stage6-feedback/
 *       coverage-delta.json
 *       termination-decision.json
 *       next-iteration-hints.json                    (omitted on terminate)
 *   iter-2/...
 *   final-report.md
 * </pre>
 */
record IterationLayout(Path iterRoot) {

    Path stage1Discovery() { return iterRoot.resolve("stage1-discovery"); }
    Path stage1Paths()     { return stage1Discovery().resolve("paths.json"); }
    Path stage1Endpoints() { return stage1Discovery().resolve("endpoints.json"); }
    Path stage2Config()    { return iterRoot.resolve("stage2-config.yml"); }
    Path stage3Archive()   { return iterRoot.resolve("stage3-archive"); }
    Path stage4Tests()     { return iterRoot.resolve("stage4-tests"); }
    Path stage5Jacoco()    { return iterRoot.resolve("stage5-jacoco.xml"); }
    Path stage6Feedback()  { return iterRoot.resolve("stage6-feedback"); }
    Path stage6Delta()     { return stage6Feedback().resolve("coverage-delta.json"); }
    Path stage6Decision()  { return stage6Feedback().resolve("termination-decision.json"); }
    Path stage6Hints()     { return stage6Feedback().resolve("next-iteration-hints.json"); }
}

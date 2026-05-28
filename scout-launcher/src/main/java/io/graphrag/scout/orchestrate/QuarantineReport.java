package io.graphrag.scout.orchestrate;

import java.util.List;

/**
 * Summary of a strict-mode quarantine pass. Returned by
 * {@link Quarantine#apply(java.nio.file.Path, java.util.List, java.io.PrintStream)} so
 * callers (and tests) can assert against the outcome without reading log output.
 *
 * @param quarantined path-ids whose per-path subdir was moved into the quarantine area
 *                    (or would have been, had it existed)
 * @param totalSteps  total scout steps inspected, regardless of outcome
 */
public record QuarantineReport(List<String> quarantined, int totalSteps) {
    public QuarantineReport {
        quarantined = List.copyOf(quarantined);
    }
}

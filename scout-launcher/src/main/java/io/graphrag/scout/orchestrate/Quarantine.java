package io.graphrag.scout.orchestrate;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Strict-mode post-scout pass — moves the per-path archive subdirectory of every scout
 * step whose live status disagreed with its configured {@code expected-status} into
 * {@code <archive-dir>/quarantine/<path-id>/}.
 *
 * <p>The point is to make false-positive Stage 1 predictions noisy instead of silently
 * polluting the main archive (R3 from the rev.2 risk doc). A future T5 coverage-feedback
 * pass can read the quarantine to feed back into the next iteration's
 * {@code --exclude-paths} hint.
 *
 * <p>Quarantine is a strict-mode-only behavior; {@link PipelineRunner} only calls this
 * when {@code output.strictMode == true}, so the existing relaxed flow stays untouched.
 */
public final class Quarantine {

    public static final String DIR_NAME = "quarantine";

    private Quarantine() {}

    public static QuarantineReport apply(Path archiveDir,
                                         List<ScoutResult> results,
                                         PrintStream log) {
        List<String> moved = new ArrayList<>();
        for (ScoutResult r : results) {
            int expected = r.step().expectedStatus();
            if (expected <= 0) continue;                       // 0 == "don't check"
            if (r.responseStatus() == expected) continue;
            moved.add(r.step().pathId());
            relocate(archiveDir, r.step().pathId());
            log.println("[scout][quarantine] " + r.step().pathId()
                    + ": expected " + expected + " got " + r.responseStatus()
                    + " (" + r.step().method() + " " + r.step().path() + ")");
        }
        log.println("[scout][quarantine] quarantined " + moved.size() + " of "
                + results.size() + " step(s)");
        return new QuarantineReport(moved, results.size());
    }

    private static void relocate(Path archiveDir, String pathId) {
        Path src = archiveDir.resolve(pathId);
        if (!Files.exists(src)) return;
        Path dst = archiveDir.resolve(DIR_NAME).resolve(pathId);
        try {
            Files.createDirectories(dst.getParent());
            // Move atomically when possible — on a same-filesystem mv this is a rename.
            Files.move(src, dst,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            // Fall back to non-atomic move (e.g. cross-device). Still better than nothing.
            try {
                Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                /* best-effort: leave the subdir in place if both moves fail */
            }
        }
    }
}

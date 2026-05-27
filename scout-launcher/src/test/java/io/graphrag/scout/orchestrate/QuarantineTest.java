package io.graphrag.scout.orchestrate;

import io.graphrag.scout.config.CaptureStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QuarantineTest {

    @Test
    void moves_mismatched_path_subdirs_into_quarantine(@TempDir Path archive) throws IOException {
        Path goodDir = Files.createDirectories(archive.resolve("list-owners"));
        Files.writeString(goodDir.resolve("captured_sql.json"), "[\"happy\"]");
        Path badDir = Files.createDirectories(archive.resolve("get-owner-99"));
        Files.writeString(badDir.resolve("captured_sql.json"), "[\"oops\"]");

        ScoutResult ok = result("list-owners", "GET", "/api/owners", 200, 200);
        ScoutResult mismatch = result("get-owner-99", "GET", "/api/owners/99", 200, 404);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        QuarantineReport report = Quarantine.apply(archive, List.of(ok, mismatch),
                new PrintStream(out));

        assertThat(report.quarantined()).containsExactly("get-owner-99");
        assertThat(Files.exists(archive.resolve("list-owners"))).isTrue();
        assertThat(Files.exists(archive.resolve("get-owner-99"))).isFalse();
        assertThat(Files.exists(archive.resolve("quarantine/get-owner-99/captured_sql.json"))).isTrue();
        assertThat(out.toString())
                .contains("quarantined 1 of 2")
                .contains("get-owner-99")
                .contains("expected 200")
                .contains("got 404");
    }

    @Test
    void ignores_steps_with_zero_expected_status(@TempDir Path archive) throws IOException {
        // expectedStatus == 0 is the "don't check" sentinel — never quarantine those.
        Path dir = Files.createDirectories(archive.resolve("health"));
        Files.writeString(dir.resolve("captured_sql.json"), "[]");

        ScoutResult r = result("health", "GET", "/actuator/health", 0, 503);
        QuarantineReport report = Quarantine.apply(archive, List.of(r),
                new PrintStream(new ByteArrayOutputStream()));

        assertThat(report.quarantined()).isEmpty();
        assertThat(Files.exists(dir)).isTrue();
    }

    @Test
    void reports_zero_quarantines_when_all_match(@TempDir Path archive) throws IOException {
        Files.createDirectories(archive.resolve("p1"));
        ScoutResult r = result("p1", "GET", "/x", 200, 200);

        QuarantineReport report = Quarantine.apply(archive, List.of(r),
                new PrintStream(new ByteArrayOutputStream()));

        assertThat(report.quarantined()).isEmpty();
        assertThat(Files.exists(archive.resolve("p1"))).isTrue();
        assertThat(Files.exists(archive.resolve("quarantine"))).isFalse();
    }

    @Test
    void tolerates_missing_per_path_directory(@TempDir Path archive) {
        // A scout step that produced zero JDBC traffic leaves no per-path subdir; the bridge
        // won't have written anything. Quarantine should not crash, just skip.
        ScoutResult r = result("ghost", "GET", "/x", 200, 404);
        QuarantineReport report = Quarantine.apply(archive, List.of(r),
                new PrintStream(new ByteArrayOutputStream()));

        // Status mismatch is still recorded so callers can surface it elsewhere.
        assertThat(report.quarantined()).containsExactly("ghost");
        assertThat(Files.exists(archive.resolve("ghost"))).isFalse();
    }

    private static ScoutResult result(String pathId, String method, String path,
                                      int expected, int actual) {
        CaptureStep step = new CaptureStep(pathId, method, path, null, null, Map.of(), expected);
        return new ScoutResult(step, Map.of(), null, actual, Map.of(), "");
    }
}

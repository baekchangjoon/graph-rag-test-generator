package io.graphrag.scout.orchestrate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PreserveFilesTest {

    @Test
    void wipe_preserves_named_top_level_files(@TempDir Path archive) throws IOException {
        Files.writeString(archive.resolve("paths.json"), "[\"keep me\"]");
        Files.writeString(archive.resolve("endpoints.json"), "[\"keep me\"]");
        Files.writeString(archive.resolve("captured_sql.json"), "[\"wipe me\"]");
        Path subdir = Files.createDirectories(archive.resolve("list-owners"));
        Files.writeString(subdir.resolve("captured_sql.json"), "[\"wipe me\"]");

        ArchiveWiper.wipe(archive, List.of("paths.json", "endpoints.json"));

        assertThat(Files.exists(archive)).isTrue();
        assertThat(Files.readString(archive.resolve("paths.json"))).isEqualTo("[\"keep me\"]");
        assertThat(Files.readString(archive.resolve("endpoints.json"))).isEqualTo("[\"keep me\"]");
        assertThat(Files.exists(archive.resolve("captured_sql.json"))).isFalse();
        assertThat(Files.exists(subdir)).isFalse();
    }

    @Test
    void wipe_with_empty_preserve_list_clears_everything(@TempDir Path parent) throws IOException {
        Path archive = parent.resolve("archive");
        Files.createDirectories(archive);
        Files.writeString(archive.resolve("paths.json"), "[]");
        Files.writeString(archive.resolve("captured_sql.json"), "[]");

        ArchiveWiper.wipe(archive, List.of());

        assertThat(Files.exists(archive.resolve("paths.json"))).isFalse();
        assertThat(Files.exists(archive.resolve("captured_sql.json"))).isFalse();
        // The wipe leaves the archive directory itself in place since prepareOutputDir's
        // createDirectories would recreate it anyway — both forms work for callers.
    }

    @Test
    void wipe_is_noop_on_missing_archive(@TempDir Path parent) {
        Path missing = parent.resolve("does-not-exist");
        // Must not throw.
        ArchiveWiper.wipe(missing, List.of("paths.json"));
        assertThat(Files.exists(missing)).isFalse();
    }

    @Test
    void wipe_preserves_files_only_by_name_not_by_path(@TempDir Path archive) throws IOException {
        // The current contract is "name match anywhere in the tree". A file called
        // paths.json inside a per-path subdir is also preserved — useful when both Stage 1
        // top-level paths.json AND a manually-pre-seeded per-step paths.json should survive.
        Files.writeString(archive.resolve("paths.json"), "[]");
        Path subdir = Files.createDirectories(archive.resolve("p1"));
        Files.writeString(subdir.resolve("paths.json"), "[]");
        Files.writeString(subdir.resolve("captured_sql.json"), "[]");

        ArchiveWiper.wipe(archive, List.of("paths.json"));

        assertThat(Files.exists(archive.resolve("paths.json"))).isTrue();
        assertThat(Files.exists(subdir.resolve("paths.json"))).isTrue();
        assertThat(Files.exists(subdir.resolve("captured_sql.json"))).isFalse();
    }
}

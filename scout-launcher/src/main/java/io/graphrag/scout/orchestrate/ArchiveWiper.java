package io.graphrag.scout.orchestrate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Wipes the contents of the archive directory before a scout run, optionally preserving
 * named files anywhere in the tree.
 *
 * <p>Extracted from {@code PipelineRunner.prepareOutputDir()} so the file-name match logic
 * can be unit-tested without spinning up Docker compose.
 *
 * <p>The preserve list matches by <strong>file name</strong>, not by relative path — a
 * file called {@code paths.json} is kept whether it lives at the root or inside a
 * per-path subdir. This makes both "Stage 1 top-level seed" and "per-path manual seed"
 * use-cases work with the same config entry.
 */
public final class ArchiveWiper {

    private ArchiveWiper() {}

    public static void wipe(Path archive, List<String> preserveFiles) {
        if (!Files.isDirectory(archive)) return;
        Set<String> keep = new HashSet<>(preserveFiles);

        try (Stream<Path> stream = Files.walk(archive)) {
            stream.sorted((a, b) -> -a.toString().compareTo(b.toString()))
                  .forEach(p -> deleteIfNotPreserved(p, archive, keep));
        } catch (IOException ignored) {
            /* best-effort: a partial wipe is acceptable; the next run will retry */
        }
    }

    private static void deleteIfNotPreserved(Path p, Path archive, Set<String> keep) {
        if (p.equals(archive)) return;
        if (Files.isRegularFile(p) && keep.contains(p.getFileName().toString())) return;
        if (Files.isDirectory(p)) {
            // Only delete the directory if it is empty after the file pass — preserved
            // children may still live inside.
            try (Stream<Path> children = Files.list(p)) {
                if (children.findAny().isPresent()) return;
            } catch (IOException ignored) {
                return;
            }
        }
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            /* best-effort */
        }
    }
}

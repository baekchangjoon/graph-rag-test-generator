package io.graphrag.builder.index;

import java.nio.file.Path;
import java.util.List;

/** Spoon 파싱 루트 합집합(parseRoots) + 경로 파생용 단일 대표(primary). */
public record SourceRoots(List<Path> parseRoots, Path primary) {

    public SourceRoots {
        if (parseRoots == null || parseRoots.isEmpty()) {
            throw new IllegalArgumentException("parseRoots must be non-empty");
        }
        parseRoots = List.copyOf(parseRoots);
    }

    public static SourceRoots single(Path dir) {
        return new SourceRoots(List.of(dir), dir);
    }

    public static SourceRoots of(List<Path> roots, Path primary) {
        return new SourceRoots(roots, primary);
    }

    public boolean isMulti() {
        return parseRoots.size() > 1;
    }
}

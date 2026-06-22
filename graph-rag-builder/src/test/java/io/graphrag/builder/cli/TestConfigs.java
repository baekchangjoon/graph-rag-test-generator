package io.graphrag.builder.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** 테스트 전용 BuildConfig 헬퍼 — 필수 필드만 채우고 나머지는 기본값. */
class TestConfigs {

    static BuildConfig minimal(Path src, Path out, boolean noInc) {
        return new BuildConfig(src, src.resolveSibling("resources"), null, out,
                "sut", "unknown", null, 0, null, null, null, null, null, null,
                false, false, null, null, null, null, null, null, noInc);
    }

    /** src 디렉터리를 dst로 재귀 복사한다. dst가 없으면 생성한다. */
    static void copyTree(Path src, Path dst) {
        try (Stream<Path> stream = Files.walk(src)) {
            stream.forEach(source -> {
                Path target = dst.resolve(src.relativize(source));
                try {
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(source, target);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

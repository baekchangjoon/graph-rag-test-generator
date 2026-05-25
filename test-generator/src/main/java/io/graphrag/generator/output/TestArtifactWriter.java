package io.graphrag.generator.output;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 합성된 Java 소스를 파일로 출력.
 *
 * <p>{@code base/com/example/tests/OrdersPostTest.java} 형태 (Maven/Gradle 컨벤션).
 *
 * <p>도구 2의 산출물을 실제 파일 시스템에 떨어뜨리는 어댑터. CLI나 호출자가 사용.
 */
public final class TestArtifactWriter {

    private final Path baseDir;

    public TestArtifactWriter(Path baseDir) {
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir");
    }

    /**
     * 주어진 패키지/클래스명에 맞춰 디렉터리 구조를 생성하고 source를 UTF-8로 쓴다.
     * 기존 파일은 덮어쓴다.
     *
     * @return 생성된 파일 경로
     */
    public Path write(String packageName, String className, String source) throws IOException {
        if (packageName == null || packageName.isBlank()) {
            throw new IllegalArgumentException("packageName must be non-blank");
        }
        if (className == null || className.isBlank()) {
            throw new IllegalArgumentException("className must be non-blank");
        }
        Objects.requireNonNull(source, "source");

        Path dir = baseDir;
        for (String part : packageName.split("\\.")) {
            dir = dir.resolve(part);
        }
        Files.createDirectories(dir);
        Path file = dir.resolve(className + ".java");
        Files.writeString(file, source, StandardCharsets.UTF_8);
        return file;
    }
}

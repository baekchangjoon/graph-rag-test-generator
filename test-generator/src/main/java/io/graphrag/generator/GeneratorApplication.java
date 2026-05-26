package io.graphrag.generator;

/**
 * test-generator CLI 엔트리포인트.
 *
 * <p>실 처리는 {@link CliRunner#run(String[])}. {@code System.exit}로 종료 코드 전달.
 */
public final class GeneratorApplication {

    private GeneratorApplication() {}

    public static void main(String[] args) {
        int code = CliRunner.run(args);
        System.exit(code);
    }
}

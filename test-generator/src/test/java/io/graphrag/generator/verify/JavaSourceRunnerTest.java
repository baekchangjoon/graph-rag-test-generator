package io.graphrag.generator.verify;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JavaSourceRunnerTest {

    @Test
    void runsTrivialPassingTest() {
        String src = """
                package gen;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                public class TrivialPassingTest {
                    @Test void simple() { assertEquals(2, 1 + 1); }
                }
                """;
        RunResult r = JavaSourceRunner.compileAndRun("gen.TrivialPassingTest", src);
        assertThat(r.compiled()).isTrue();
        assertThat(r.testsRun()).isEqualTo(1);
        assertThat(r.failures()).isZero();
        assertThat(r.allPassed()).isTrue();
    }

    @Test
    void runsFailingTestAndReportsFailure() {
        String src = """
                package gen;
                import org.junit.jupiter.api.Test;
                public class TrivialFailingTest {
                    @Test void boom() { throw new AssertionError("intended"); }
                }
                """;
        RunResult r = JavaSourceRunner.compileAndRun("gen.TrivialFailingTest", src);
        assertThat(r.compiled()).isTrue();
        assertThat(r.testsRun()).isEqualTo(1);
        assertThat(r.failures()).isEqualTo(1);
        assertThat(r.allPassed()).isFalse();
        assertThat(r.diagnostics().stream().anyMatch(d -> d.contains("intended"))).isTrue();
    }

    @Test
    void reportsCompileErrorWithoutRunning() {
        String src = "package gen; public class Broken { void m() return 5; } }";
        RunResult r = JavaSourceRunner.compileAndRun("gen.Broken", src);
        assertThat(r.compiled()).isFalse();
        assertThat(r.diagnostics()).isNotEmpty();
        assertThat(r.testsRun()).isZero();
    }

    @Test
    void runsMultipleTestsAndCountsAll() {
        String src = """
                package gen;
                import org.junit.jupiter.api.Test;
                public class MultiTests {
                    @Test void a() {}
                    @Test void b() {}
                    @Test void c() {}
                }
                """;
        RunResult r = JavaSourceRunner.compileAndRun("gen.MultiTests", src);
        assertThat(r.testsRun()).isEqualTo(3);
        assertThat(r.failures()).isZero();
    }
}

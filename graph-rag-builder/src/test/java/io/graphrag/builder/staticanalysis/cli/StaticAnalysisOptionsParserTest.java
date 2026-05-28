package io.graphrag.builder.staticanalysis.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StaticAnalysisOptionsParserTest {

    @Test
    void parses_required_flags() {
        StaticAnalysisOptions opts = StaticAnalysisOptionsParser.parse(new String[] {
                "--sut-source", "/tmp/petclinic",
                "--project", "petclinic",
                "--out", "/tmp/out"
        });
        assertThat(opts.sutSource()).isEqualTo(Path.of("/tmp/petclinic"));
        assertThat(opts.project()).isEqualTo("petclinic");
        assertThat(opts.out()).isEqualTo(Path.of("/tmp/out"));
    }

    @Test
    void defaults_code_version_and_max_paths() {
        StaticAnalysisOptions opts = StaticAnalysisOptionsParser.parse(new String[] {
                "--sut-source", "/tmp/p",
                "--project", "p",
                "--out", "/tmp/o"
        });
        assertThat(opts.codeVersion()).isEqualTo("static-1");
        assertThat(opts.maxPathsPerEndpoint()).isEqualTo(10);
        assertThat(opts.excludePaths()).isEmpty();
    }

    @Test
    void rejects_unknown_flag() {
        assertThatThrownBy(() -> StaticAnalysisOptionsParser.parse(new String[] {
                "--sut-source", "/tmp/p", "--bogus", "x"
        })).isInstanceOf(IllegalArgumentException.class)
           .hasMessageContaining("--bogus");
    }

    @Test
    void rejects_missing_required_flag() {
        assertThatThrownBy(() -> StaticAnalysisOptionsParser.parse(new String[] {
                "--sut-source", "/tmp/p", "--project", "p"
        })).isInstanceOf(IllegalArgumentException.class)
           .hasMessageContaining("--out");
    }

    @Test
    void parses_exclude_paths_csv() {
        StaticAnalysisOptions opts = StaticAnalysisOptionsParser.parse(new String[] {
                "--sut-source", "/tmp/p",
                "--project", "p",
                "--out", "/tmp/o",
                "--exclude-paths", "GET:/a,POST:/b"
        });
        assertThat(opts.excludePaths()).containsExactlyInAnyOrder("GET:/a", "POST:/b");
    }

    @Test
    void parses_max_paths_per_endpoint() {
        StaticAnalysisOptions opts = StaticAnalysisOptionsParser.parse(new String[] {
                "--sut-source", "/tmp/p",
                "--project", "p",
                "--out", "/tmp/o",
                "--max-paths-per-endpoint", "5"
        });
        assertThat(opts.maxPathsPerEndpoint()).isEqualTo(5);
    }

    @Test
    void usage_contains_all_flag_names() {
        String usage = StaticAnalysisOptionsParser.usage();
        assertThat(usage)
                .contains("--sut-source")
                .contains("--project")
                .contains("--out")
                .contains("--code-version")
                .contains("--max-paths-per-endpoint")
                .contains("--exclude-paths");
    }
}

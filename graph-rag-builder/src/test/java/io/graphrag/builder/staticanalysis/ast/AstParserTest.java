package io.graphrag.builder.staticanalysis.ast;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AstParserTest {

    @Test
    void empty_dir_yields_empty_result(@TempDir Path tmp) throws Exception {
        AstParseResult r = AstParser.parse(tmp);
        assertThat(r.parsedFiles()).isEmpty();
        assertThat(r.failures()).isEmpty();
    }

    @Test
    void parses_single_valid_file(@TempDir Path tmp) throws Exception {
        Path pkgDir = tmp.resolve("a/b");
        Files.createDirectories(pkgDir);
        Path src = pkgDir.resolve("Hello.java");
        Files.writeString(src, """
            package a.b;
            public class Hello { String greet() { return "hi"; } }
            """);

        AstParseResult r = AstParser.parse(tmp);

        assertThat(r.failures()).isEmpty();
        assertThat(r.parsedFiles()).hasSize(1);
        ParsedFile p = r.parsedFiles().get(0);
        assertThat(p.sourcePath()).isEqualTo(src);
        assertThat(p.packageName()).isEqualTo("a.b");
        assertThat(p.className()).isEqualTo("Hello");
        assertThat(p.cu()).isNotNull();
    }

    @Test
    void package_info_files_are_tolerated(@TempDir Path tmp) throws Exception {
        Path pkgDir = tmp.resolve("a/b");
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve("package-info.java"), "package a.b;\n");

        AstParseResult r = AstParser.parse(tmp);

        assertThat(r.failures()).isEmpty();
        assertThat(r.parsedFiles()).hasSize(1);
        // package-info has no class — className is "".
        assertThat(r.parsedFiles().get(0).packageName()).isEqualTo("a.b");
        assertThat(r.parsedFiles().get(0).className()).isEmpty();
    }

    @Test
    void result_is_sorted_by_path(@TempDir Path tmp) throws Exception {
        Path a = tmp.resolve("a/A.java");
        Path b = tmp.resolve("b/B.java");
        Path c = tmp.resolve("c/C.java");
        Files.createDirectories(a.getParent());
        Files.createDirectories(b.getParent());
        Files.createDirectories(c.getParent());
        Files.writeString(c, "package c; class C {}\n");
        Files.writeString(a, "package a; class A {}\n");
        Files.writeString(b, "package b; class B {}\n");

        AstParseResult r = AstParser.parse(tmp);

        assertThat(r.parsedFiles()).extracting(ParsedFile::className)
                .containsExactly("A", "B", "C");
    }

    @Test
    void parse_is_deterministic(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("p"));
        Files.writeString(tmp.resolve("p/X.java"), "package p; class X {}\n");
        Files.writeString(tmp.resolve("p/Y.java"), "package p; class Y {}\n");

        AstParseResult r1 = AstParser.parse(tmp);
        AstParseResult r2 = AstParser.parse(tmp);

        assertThat(r1.parsedFiles()).extracting(ParsedFile::className)
                .isEqualTo(r2.parsedFiles().stream().map(ParsedFile::className).toList());
    }

    @Test
    void broken_file_isolated_in_failures(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("p"));
        Files.writeString(tmp.resolve("p/Good.java"), "package p; class Good {}\n");
        Files.writeString(tmp.resolve("p/Bad.java"), "package p; class { this is not java }\n");

        AstParseResult r = AstParser.parse(tmp);

        assertThat(r.parsedFiles()).extracting(ParsedFile::className).containsExactly("Good");
        assertThat(r.failures()).hasSize(1);
        assertThat(r.failures().get(0).sourcePath().getFileName().toString()).isEqualTo("Bad.java");
        assertThat(r.failures().get(0).message()).isNotBlank();
    }
}

package io.graphrag.generator.output;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestArtifactWriterTest {

    @Test
    void writesClassToExpectedPathAndReturnsIt(@TempDir Path tmp) throws IOException {
        TestArtifactWriter writer = new TestArtifactWriter(tmp);

        String source = "package com.example.tests;\n\nclass OrdersPostTest {}\n";
        Path written = writer.write("com.example.tests", "OrdersPostTest", source);

        assertThat(written).isEqualTo(
                tmp.resolve("com/example/tests/OrdersPostTest.java"));
        assertThat(Files.readString(written)).isEqualTo(source);
    }

    @Test
    void createsIntermediateDirectories(@TempDir Path tmp) throws IOException {
        TestArtifactWriter writer = new TestArtifactWriter(tmp);

        writer.write("a.b.c.d.e", "X", "package a.b.c.d.e; class X {}\n");

        assertThat(tmp.resolve("a/b/c/d/e/X.java").toFile()).exists();
    }

    @Test
    void overwritesExisting(@TempDir Path tmp) throws IOException {
        TestArtifactWriter writer = new TestArtifactWriter(tmp);

        writer.write("p", "C", "package p; class C { /* v1 */ }\n");
        Path second = writer.write("p", "C", "package p; class C { /* v2 */ }\n");

        assertThat(Files.readString(second)).contains("/* v2 */");
    }

    @Test
    void rejectsBlankPackageOrClass(@TempDir Path tmp) {
        TestArtifactWriter writer = new TestArtifactWriter(tmp);

        assertThatThrownBy(() -> writer.write("", "X", "src"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> writer.write("p", "", "src"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void writesUtf8Content(@TempDir Path tmp) throws IOException {
        TestArtifactWriter writer = new TestArtifactWriter(tmp);
        String src = "package p; class C { /* 한글 주석 */ }\n";

        Path file = writer.write("p", "C", src);

        assertThat(Files.readString(file)).contains("한글 주석");
    }
}

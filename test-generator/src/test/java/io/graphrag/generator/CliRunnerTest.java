package io.graphrag.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphrag.generator.core.MultiPathSynthesisInput;
import io.graphrag.generator.core.PathContext;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.HttpMethod;
import io.graphrag.model.JsonMappers;
import io.graphrag.model.PathExplorerKind;
import io.graphrag.model.SampleInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CliRunnerTest {

    private final ObjectMapper mapper = JsonMappers.standard();

    private MultiPathSynthesisInput sampleInput() {
        Endpoint ep = new Endpoint(
                "POST:/api/orders", HttpMethod.POST, "/api/orders",
                "demo-sut", "OrdersController", "create", false, List.of());
        ExploredPath p = new ExploredPath("p1", ep.id(), PathExplorerKind.MANUAL,
                new SampleInput(Map.of(), Map.of(), Map.of(),
                        Map.of("userId", "u-1", "amount", 100, "type", "EXPRESS")),
                null, List.of(), 201, null, "cov-p1", "v1");
        return new MultiPathSynthesisInput(ep,
                List.of(new PathContext(p, List.of())),
                "com.example.tests");
    }

    @Test
    void runWritesGeneratedFileAndReturnsZero(@TempDir Path tmp) throws Exception {
        Path specFile = tmp.resolve("spec.json");
        Path outDir = tmp.resolve("out");
        Files.writeString(specFile, mapper.writeValueAsString(sampleInput()));

        int code = CliRunner.run(new String[] {
                "--spec", specFile.toString(),
                "--out", outDir.toString()
        });

        assertThat(code).isZero();
        Path generated = outDir.resolve("com/example/tests/OrdersPostTest.java");
        assertThat(generated.toFile()).exists();
        String content = Files.readString(generated);
        assertThat(content)
                .contains("package com.example.tests;")
                .contains("class OrdersPostTest");
    }

    @Test
    void missingRequiredArgsReturnsNonZero(@TempDir Path tmp) {
        int code = CliRunner.run(new String[] {"--spec", tmp.resolve("missing.json").toString()});
        assertThat(code).isNotZero();
    }

    @Test
    void unknownFlagReportsError(@TempDir Path tmp) throws Exception {
        Path specFile = tmp.resolve("spec.json");
        Files.writeString(specFile, mapper.writeValueAsString(sampleInput()));

        int code = CliRunner.run(new String[] {
                "--spec", specFile.toString(),
                "--out", tmp.resolve("out").toString(),
                "--bogus", "x"
        });
        assertThat(code).isNotZero();
    }

    @Test
    void nonexistentSpecFileReturnsNonZero(@TempDir Path tmp) {
        int code = CliRunner.run(new String[] {
                "--spec", tmp.resolve("nope.json").toString(),
                "--out", tmp.resolve("out").toString()
        });
        assertThat(code).isNotZero();
    }
}

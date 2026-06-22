package io.graphrag.builder.cli;

import io.graphrag.builder.index.SharedSpoonModel;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BuilderStaticIndexTest {

    @Test
    void staticIndexingBuildsSpoonModelOnce() throws Exception {
        Path src = Files.createTempDirectory("sut-src");
        Files.writeString(src.resolve("FooController.java"),
                "package p; import org.springframework.web.bind.annotation.*;"
                + "@RestController class FooController { @GetMapping(\"/foo\") String foo(){return \"x\";} }");
        SharedSpoonModel.resetBuildCount();

        BuilderCli.indexStatically(src);   // Task 3에서 노출하는 정적 인덱싱 진입(테스트 훅)

        assertThat(SharedSpoonModel.buildCount()).isEqualTo(1);
    }
}

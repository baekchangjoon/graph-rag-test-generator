package io.graphrag.builder.index;

import org.junit.jupiter.api.Test;
import spoon.reflect.CtModel;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SharedSpoonModelTest {

    @Test
    void buildIncrementsCountAndParsesTypes() throws Exception {
        Path src = Files.createTempDirectory("shared-spoon");
        Files.writeString(src.resolve("Foo.java"), "package p; class Foo {}");
        SharedSpoonModel.resetBuildCount();

        CtModel model = SharedSpoonModel.build(src);

        assertThat(SharedSpoonModel.buildCount()).isEqualTo(1);
        assertThat(model.getAllTypes()).anyMatch(t -> t.getSimpleName().equals("Foo"));
    }
}

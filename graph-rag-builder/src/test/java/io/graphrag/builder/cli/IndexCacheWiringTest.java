package io.graphrag.builder.cli;

import io.graphrag.builder.index.SharedSpoonModel;
import io.graphrag.builder.store.StaticIndex;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class IndexCacheWiringTest {

    private static BuildConfig cfg(Path src, Path out, boolean noInc) {
        return TestConfigs.minimal(src, out, noInc);
    }

    @Test
    void unchangedRebuildUsesCacheZeroBuilds() throws Exception {   // REQ-003
        Path src = Files.createTempDirectory("sut");
        Path out = Files.createTempDirectory("out");
        Files.writeString(src.resolve("FooController.java"),
                "package p; import org.springframework.web.bind.annotation.*;"
                + "@RestController class FooController { @GetMapping(\"/foo\") String f(){return \"x\";} }");

        SharedSpoonModel.resetBuildCount();
        StaticIndex first = BuilderCli.staticIndexWithCache(cfg(src, out, false));
        assertThat(SharedSpoonModel.buildCount()).isEqualTo(1);

        SharedSpoonModel.resetBuildCount();
        StaticIndex second = BuilderCli.staticIndexWithCache(cfg(src, out, false));
        assertThat(SharedSpoonModel.buildCount()).isEqualTo(0);     // 캐시 복원
        assertThat(second.index().endpoints()).hasSameSizeAs(first.index().endpoints());
    }

    @Test
    void changedFileTriggersRebuild() throws Exception {            // REQ-004
        Path src = Files.createTempDirectory("sut2");
        Path out = Files.createTempDirectory("out2");
        Files.writeString(src.resolve("FooController.java"),
                "package p; import org.springframework.web.bind.annotation.*;"
                + "@RestController class FooController { @GetMapping(\"/foo\") String f(){return \"x\";} }");
        BuilderCli.staticIndexWithCache(cfg(src, out, false));

        Files.writeString(src.resolve("FooController.java"),
                "package p; import org.springframework.web.bind.annotation.*;"
                + "@RestController class FooController { @GetMapping(\"/bar\") String f(){return \"x\";} }");
        SharedSpoonModel.resetBuildCount();
        StaticIndex after = BuilderCli.staticIndexWithCache(cfg(src, out, false));
        assertThat(SharedSpoonModel.buildCount()).isEqualTo(1);     // 변경 감지 → 재빌드
        assertThat(after.index().endpoints()).anyMatch(e -> e.path().equals("/bar"));
    }

    @Test
    void noIncrementalForcesRebuild() throws Exception {            // REQ-007 배선 확인
        Path src = Files.createTempDirectory("sut3");
        Path out = Files.createTempDirectory("out3");
        Files.writeString(src.resolve("FooController.java"),
                "package p; import org.springframework.web.bind.annotation.*;"
                + "@RestController class FooController { @GetMapping(\"/foo\") String f(){return \"x\";} }");
        BuilderCli.staticIndexWithCache(cfg(src, out, false));
        SharedSpoonModel.resetBuildCount();
        BuilderCli.staticIndexWithCache(cfg(src, out, true));       // --no-incremental
        assertThat(SharedSpoonModel.buildCount()).isEqualTo(1);
    }
}

package io.graphrag.builder.cli;

import io.graphrag.builder.index.SharedSpoonModel;
import io.graphrag.builder.store.StaticIndex;
import io.graphrag.model.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 정적 인덱싱 증분화 E2E — 기존 sample-src 픽스처 재사용.
 *
 * <p>복사 구조:
 * <ul>
 *   <li>tmpdir/         → sutSrc (java 루트; io/graphrag/sample/... 하위)</li>
 *   <li>tmpdir/../resources/ → sutResources (mapper XML; TestConfigs.minimal이 src.resolveSibling("resources") 사용)</li>
 * </ul>
 * sample-src/io/** 는 tmpdir/io/** 로, sample-src/mapper/** 는 tmpdir/../resources/mapper/** 로 복사.
 */
class IncrementalIndexE2E {

    private static final Path SAMPLE_SRC = Path.of("src/test/resources/sample-src");

    @Test
    @DisplayName("REQ-006: 증분 빌드 산출물 == --no-incremental 풀 리빌드 산출물")
    void incrementalEqualsFullRebuild() throws Exception {
        SampleCopy copy = copySample();
        Path out = Files.createTempDirectory("out");

        StaticIndex incremental = BuilderCli.staticIndexWithCache(
                TestConfigs.minimal(copy.src(), out, false));
        StaticIndex full = BuilderCli.staticIndexWithCache(
                TestConfigs.minimal(copy.src(), out.resolve("full"), true));

        assertThat(Json.mapper().writeValueAsString(incremental))
                .isEqualTo(Json.mapper().writeValueAsString(full));
    }

    @Test
    @DisplayName("REQ-003: 무변경 재빌드는 Spoon 0회 + 산출물 동일")
    void noChangeRebuildZeroBuilds() throws Exception {
        SampleCopy copy = copySample();
        Path out = Files.createTempDirectory("out");

        StaticIndex first = BuilderCli.staticIndexWithCache(
                TestConfigs.minimal(copy.src(), out, false));

        SharedSpoonModel.resetBuildCount();

        StaticIndex second = BuilderCli.staticIndexWithCache(
                TestConfigs.minimal(copy.src(), out, false));

        assertThat(SharedSpoonModel.buildCount()).isEqualTo(0);
        assertThat(Json.mapper().writeValueAsString(second))
                .isEqualTo(Json.mapper().writeValueAsString(first));
    }

    @Test
    @DisplayName("REQ-005: 핸들러 파일 삭제 시 엔드포인트 제거 + 풀 리빌드 동일")
    void deletedFileRemovesEndpoint() throws Exception {
        SampleCopy copy = copySample();
        Path out = Files.createTempDirectory("out");

        // 초기 빌드 (캐시 채움)
        BuilderCli.staticIndexWithCache(TestConfigs.minimal(copy.src(), out, false));

        // OrderController.java 삭제
        Path controller = copy.src().resolve(
                "io/graphrag/sample/orders/OrderController.java");
        Files.delete(controller);

        // 삭제 후 증분 빌드
        StaticIndex after = BuilderCli.staticIndexWithCache(
                TestConfigs.minimal(copy.src(), out, false));

        // 동일 소스로 풀 리빌드
        StaticIndex full = BuilderCli.staticIndexWithCache(
                TestConfigs.minimal(copy.src(), out.resolve("full"), true));

        assertThat(Json.mapper().writeValueAsString(after))
                .isEqualTo(Json.mapper().writeValueAsString(full));
    }

    @Test
    @DisplayName("REQ-009: mapper XML 수정 시 mappers 갱신 + 풀 리빌드 동일")
    void mapperXmlEditUpdatesFragment() throws Exception {
        SampleCopy copy = copySample();
        Path out = Files.createTempDirectory("out");

        // 초기 빌드 (캐시 채움)
        BuilderCli.staticIndexWithCache(TestConfigs.minimal(copy.src(), out, false));

        // mapper XML 수정 (resolveSibling("resources")가 sutResources)
        Path xml = copy.src().resolveSibling("resources")
                .resolve("mapper/OrderSearchMapper.xml");
        String original = Files.readString(xml);
        Files.writeString(xml,
                original.replace("</mapper>", "<select id='countAll'>select count(*) from orders</select></mapper>"));

        // XML 수정 후 증분 빌드
        StaticIndex after = BuilderCli.staticIndexWithCache(
                TestConfigs.minimal(copy.src(), out, false));

        // 동일 소스로 풀 리빌드
        StaticIndex full = BuilderCli.staticIndexWithCache(
                TestConfigs.minimal(copy.src(), out.resolve("full"), true));

        assertThat(Json.mapper().writeValueAsString(after))
                .isEqualTo(Json.mapper().writeValueAsString(full));
    }

    /**
     * sample-src를 임시 디렉터리로 복사한다.
     *
     * <ul>
     *   <li>java 루트(sutSrc): tmpdir/src/</li>
     *   <li>resources 루트(sutResources): tmpdir/resources/  (src.resolveSibling("resources"))</li>
     * </ul>
     */
    private SampleCopy copySample() throws Exception {
        Path base = Files.createTempDirectory("inc-e2e");
        Path src = base.resolve("src");
        Path resources = base.resolve("resources");

        // java sources: sample-src/io/** → src/io/**
        TestConfigs.copyTree(SAMPLE_SRC.resolve("io"), src.resolve("io"));

        // mapper XML: sample-src/mapper/** → resources/mapper/**
        TestConfigs.copyTree(SAMPLE_SRC.resolve("mapper"), resources.resolve("mapper"));

        return new SampleCopy(src);
    }

    private record SampleCopy(Path src) {}
}

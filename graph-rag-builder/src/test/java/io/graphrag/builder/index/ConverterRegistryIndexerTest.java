package io.graphrag.builder.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** ConverterRegistryIndexer: 전역 Formatter/Converter convertedTypes + 컨트롤러-local @InitBinder editor 수집. */
class ConverterRegistryIndexerTest {

    @Test
    void collectsFormatterAndConverterTargetTypes_globally(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Reg.java"), """
                package x;
                import org.springframework.format.Formatter;
                import org.springframework.core.convert.converter.Converter;
                class Color {}
                class Brand {}
                class ColorFormatter implements Formatter<Color> {
                    public Color parse(String t, java.util.Locale l) { return new Color(); }
                    public String print(Color c, java.util.Locale l) { return ""; }
                }
                class BrandConverter implements Converter<String, Brand> {
                    public Brand convert(String s) { return new Brand(); }
                }
                """);

        ConverterRegistryIndexer.Registry reg = new ConverterRegistryIndexer().index(dir);

        // Formatter<Color> → Color, Converter<String,Brand> → Brand(2번째 인자).
        assertThat(reg.convertedTypes()).contains("x.Color", "x.Brand");
    }

    @Test
    void collectsInitBinderRegisterCustomEditor_perController(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Ctl.java"), """
                package x;
                import org.springframework.stereotype.Controller;
                import org.springframework.web.bind.WebDataBinder;
                import org.springframework.web.bind.annotation.InitBinder;
                class Sku {}
                @Controller
                class EditorCtl {
                    @InitBinder
                    void init(WebDataBinder binder) {
                        binder.registerCustomEditor(Sku.class, new java.beans.PropertyEditorSupport());
                    }
                }
                """);

        ConverterRegistryIndexer.Registry reg = new ConverterRegistryIndexer().index(dir);

        // @InitBinder registerCustomEditor(Sku.class) → 그 컨트롤러 FQN에만 Sku.
        assertThat(reg.controllerEditors()).containsKey("x.EditorCtl");
        assertThat(reg.controllerEditors().get("x.EditorCtl")).contains("x.Sku");
        // Sku는 전역 convertedTypes가 아니다(컨트롤러-local 스코프).
        assertThat(reg.convertedTypes()).doesNotContain("x.Sku");
    }

    @Test
    void wildcardImportFormatter_resolvesTargetTypeBySimpleNameFallback(@TempDir Path dir) throws Exception {
        // 와일드카드 import로 제네릭 인자가 bare simple-name으로 파싱될 수 있는 경로 — 모델 교차참조로 해석.
        Files.writeString(dir.resolve("Wild.java"), """
                package x;
                import org.springframework.format.*;
                class Hue {}
                class HueFormatter implements Formatter<Hue> {
                    public Hue parse(String t, java.util.Locale l) { return new Hue(); }
                    public String print(Hue c, java.util.Locale l) { return ""; }
                }
                """);

        ConverterRegistryIndexer.Registry reg = new ConverterRegistryIndexer().index(dir);

        assertThat(reg.convertedTypes()).contains("x.Hue");
    }
}

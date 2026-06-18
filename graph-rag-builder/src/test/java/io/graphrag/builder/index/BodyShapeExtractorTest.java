package io.graphrag.builder.index;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import spoon.reflect.CtModel;

class BodyShapeExtractorTest {

    private static CtModel model(String src) {
        spoon.Launcher l = new spoon.Launcher();
        l.getEnvironment().setNoClasspath(true);
        l.getEnvironment().setComplianceLevel(17);
        l.addInputResource(new spoon.support.compiler.VirtualFile(src, "In.java"));
        return l.buildModel();
    }

    private static spoon.reflect.reference.CtTypeReference<?> firstParamType(CtModel m) {
        for (var t : m.getAllTypes()) {
            for (var mt : t.getMethods()) {
                if (!mt.getParameters().isEmpty()) {
                    return mt.getParameters().get(0).getType();
                }
            }
        }
        throw new IllegalStateException("no param");
    }

    private static final String DTO = "package p; class Dto { String name; int amount; }";

    @Test
    void listOfDto_isCollectionWithElementFields() {
        CtModel m = model(DTO + " class In { void h(java.util.List<p.Dto> b){} }");
        var type = firstParamType(m);

        var shape = BodyShapeExtractor.extractFromType(m, type);

        assertThat(shape).isPresent();
        assertThat(shape.get().collection()).isTrue();
        assertThat(shape.get().javaType()).isEqualTo("p.Dto");
        assertThat(shape.get().fields()).extracting(BodyShape.BodyField::name)
                .contains("name", "amount");
    }

    @Test
    void arrayOfDto_isCollection() {
        CtModel m = model(DTO + " class In { void h(p.Dto[] b){} }");
        var type = firstParamType(m);

        var shape = BodyShapeExtractor.extractFromType(m, type);

        assertThat(shape).isPresent();
        assertThat(shape.get().collection()).isTrue();
        assertThat(shape.get().javaType()).isEqualTo("p.Dto");
    }

    @Test
    void setOfDto_isCollection() {
        CtModel m = model(DTO + " class In { void h(java.util.Set<p.Dto> b){} }");
        var shape = BodyShapeExtractor.extractFromType(m, firstParamType(m));

        assertThat(shape).isPresent();
        assertThat(shape.get().collection()).isTrue();
        assertThat(shape.get().javaType()).isEqualTo("p.Dto");
    }

    @Test
    void collectionOfDto_isCollection() {
        CtModel m = model(DTO + " class In { void h(java.util.Collection<p.Dto> b){} }");
        var shape = BodyShapeExtractor.extractFromType(m, firstParamType(m));

        assertThat(shape).isPresent();
        assertThat(shape.get().collection()).isTrue();
        assertThat(shape.get().javaType()).isEqualTo("p.Dto");
    }

    @Test
    void iterableOfDto_isCollection() {
        CtModel m = model(DTO + " class In { void h(java.lang.Iterable<p.Dto> b){} }");
        var shape = BodyShapeExtractor.extractFromType(m, firstParamType(m));

        assertThat(shape).isPresent();
        assertThat(shape.get().collection()).isTrue();
        assertThat(shape.get().javaType()).isEqualTo("p.Dto");
    }

    @Test
    void listOfString_isCollectionWithEmptyFields() {
        CtModel m = model("class In { void h(java.util.List<java.lang.String> b){} }");
        var shape = BodyShapeExtractor.extractFromType(m, firstParamType(m));

        assertThat(shape).isPresent();
        assertThat(shape.get().collection()).isTrue();
        assertThat(shape.get().javaType()).isEqualTo("java.lang.String");
        assertThat(shape.get().fields()).isEmpty();
    }

    @Test
    void listOfEnum_isCollectionWithEmptyFields_enumTreatedAsScalar() {
        CtModel m = model("package p; enum E { A, B } class In { void h(java.util.List<p.E> b){} }");
        var shape = BodyShapeExtractor.extractFromType(m, firstParamType(m));

        assertThat(shape).isPresent();
        assertThat(shape.get().collection()).isTrue();
        assertThat(shape.get().javaType()).isEqualTo("p.E");
        assertThat(shape.get().fields()).isEmpty();
    }

    @Test
    void rawList_isEmpty() {
        CtModel m = model("class In { void h(java.util.List b){} }");
        var shape = BodyShapeExtractor.extractFromType(m, firstParamType(m));

        assertThat(shape).isEmpty();
    }

    @Test
    void plainDto_isNonCollection() {
        CtModel m = model(DTO + " class In { void h(p.Dto b){} }");
        var shape = BodyShapeExtractor.extractFromType(m, firstParamType(m));

        assertThat(shape).isPresent();
        assertThat(shape.get().collection()).isFalse();
        assertThat(shape.get().javaType()).isEqualTo("p.Dto");
    }

    @Test
    void bodyTypeKey_listOfDto() {
        CtModel m = model(DTO + " class In { void h(java.util.List<p.Dto> b){} }");
        assertThat(BodyShapeExtractor.bodyTypeKey(firstParamType(m)))
                .isEqualTo("java.util.List<p.Dto>");
    }

    @Test
    void bodyTypeKey_arrayOfDto() {
        CtModel m = model(DTO + " class In { void h(p.Dto[] b){} }");
        assertThat(BodyShapeExtractor.bodyTypeKey(firstParamType(m)))
                .isEqualTo("p.Dto[]");
    }

    @Test
    void bodyTypeKey_setOfDto() {
        CtModel m = model(DTO + " class In { void h(java.util.Set<p.Dto> b){} }");
        assertThat(BodyShapeExtractor.bodyTypeKey(firstParamType(m)))
                .isEqualTo("java.util.Set<p.Dto>");
    }

    @Test
    void bodyTypeKey_plainDto() {
        CtModel m = model(DTO + " class In { void h(p.Dto b){} }");
        assertThat(BodyShapeExtractor.bodyTypeKey(firstParamType(m)))
                .isEqualTo("p.Dto");
    }
}

package io.graphrag.builder.index;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import spoon.reflect.CtModel;
import spoon.reflect.reference.CtTypeReference;

/**
 * REQ-001, REQ-010: BodyShapeExtractor depth-3+ 중첩 DTO 전개 테스트.
 * MAX_NESTING_DEPTH=4 기준으로 3단 중첩(l1.l2.value, l1.l2.count)이 스칼라 리프로 전개됨을 검증.
 */
class BodyShapeExtractorGenericTest {

    private static CtModel model(String src) {
        spoon.Launcher l = new spoon.Launcher();
        l.getEnvironment().setNoClasspath(true);
        l.getEnvironment().setComplianceLevel(17);
        l.addInputResource(new spoon.support.compiler.VirtualFile(src, "In.java"));
        return l.buildModel();
    }

    /** 핸들러 메서드의 첫 번째 파라미터 타입을 반환. */
    private static CtTypeReference<?> firstParamType(CtModel m) {
        for (var t : m.getAllTypes()) {
            for (var mt : t.getMethods()) {
                if (!mt.getParameters().isEmpty()) {
                    return mt.getParameters().get(0).getType();
                }
            }
        }
        throw new IllegalStateException("no param");
    }

    /**
     * REQ-001, REQ-010: depth-3 중첩 DTO(Root→Level1→Level2)의 스칼라 필드가
     * dot-path 리프로 전개된다.
     * cap=4이므로 l1.l2.value / l1.l2.count 모두 스칼라 리프여야 한다.
     */
    @Test
    void deepNested() {
        String src = "package p; "
                + "record Root(p.Level1 l1) {} "
                + "record Level1(p.Level2 l2) {} "
                + "record Level2(String value, int count) {} "
                + "class In { void h(p.Root b){} }";
        CtModel m = model(src);

        var shape = BodyShapeExtractor.extractFromTypeFlattened(m, firstParamType(m));

        assertThat(shape).isPresent();
        assertThat(shape.get().fields())
                .extracting(BodyShape.BodyField::name)
                .contains("l1.l2.value", "l1.l2.count");
        // 스칼라 리프 javaType 검증
        assertThat(shape.get().fields())
                .filteredOn(f -> "l1.l2.value".equals(f.name()))
                .extracting(BodyShape.BodyField::javaType)
                .containsExactly("java.lang.String");
        assertThat(shape.get().fields())
                .filteredOn(f -> "l1.l2.count".equals(f.name()))
                .extracting(BodyShape.BodyField::javaType)
                .containsExactly("int");
        // "l1.l2" (Level2 타입 리프) 는 없어야 한다 — 완전 전개됨
        assertThat(shape.get().fields())
                .extracting(BodyShape.BodyField::name)
                .doesNotContain("l1.l2");
    }
}

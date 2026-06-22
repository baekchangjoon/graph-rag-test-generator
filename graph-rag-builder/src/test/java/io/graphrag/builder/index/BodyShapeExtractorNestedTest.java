package io.graphrag.builder.index;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import spoon.reflect.CtModel;
import spoon.reflect.reference.CtTypeReference;

/**
 * REQ-005: BodyShapeExtractor 재귀 dot-path 평탄화 테스트.
 * 중첩 DTO 필드를 "parent.child" dot-path 스칼라 리프로 전개함을 검증.
 * extractFromTypeFlattened(JSON @RequestBody 전용)를 대상으로 한다.
 */
class BodyShapeExtractorNestedTest {

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

    /** REQ-005: 단일 중첩 DTO가 dot-path 리프로 전개된다. */
    @Test
    void nestedField_flattensToDotPath() {
        String src = "package p; "
                + "record Order(p.Address address) {} "
                + "record Address(String city) {} "
                + "class In { void h(p.Order b){} }";
        CtModel m = model(src);

        var shape = BodyShapeExtractor.extractFromTypeFlattened(m, firstParamType(m));

        assertThat(shape).isPresent();
        assertThat(shape.get().fields())
                .extracting(BodyShape.BodyField::name)
                .contains("address.city");
        // 스칼라 리프의 javaType은 java.lang.String이어야 한다
        assertThat(shape.get().fields())
                .filteredOn(f -> "address.city".equals(f.name()))
                .extracting(BodyShape.BodyField::javaType)
                .containsExactly("java.lang.String");
        // 원래의 "address" (타입명만) 는 없어야 한다 — 평탄화 완료
        assertThat(shape.get().fields())
                .extracting(BodyShape.BodyField::name)
                .doesNotContain("address");
    }

    /**
     * REQ-005: MAX_NESTING_DEPTH(=2)를 초과하는 체인은 depth 2에서 타입 자체를 리프로 emit.
     * 예: A.b.c.d 경로에서 d(depth=2)는 확장되지 않고 타입 FQN을 javaType으로 가진 리프.
     */
    @Test
    void nestedDepth_cappedAtMax() {
        // A -> B -> C -> D (스칼라 아님) : depth 0=A, 1=B, 2=C, 3=D
        // D는 depth=2이므로 expand 안 됨 → path "b.c.d" 가 리프
        String src = "package p; "
                + "record A(p.B b) {} "
                + "record B(p.C c) {} "
                + "record C(p.D d) {} "
                + "record D(String x) {} "
                + "class In { void h(p.A b){} }";
        CtModel m = model(src);

        var shape = BodyShapeExtractor.extractFromTypeFlattened(m, firstParamType(m));

        assertThat(shape).isPresent();
        // depth cap: "b.c.d" 가 리프(D 타입)로 emit되어야 하고, "b.c.d.x" 는 없어야 한다
        assertThat(shape.get().fields())
                .extracting(BodyShape.BodyField::name)
                .contains("b.c.d")
                .doesNotContain("b.c.d.x");
        // dot-segment 수: 최대 3개 (root=0이므로 depth2 경로 = 3 dot-segments "b.c.d")
        assertThat(shape.get().fields())
                .extracting(BodyShape.BodyField::name)
                .allSatisfy(name -> {
                    long dots = name.chars().filter(ch -> ch == '.').count();
                    assertThat(dots).isLessThanOrEqualTo(3);
                });
    }

    /**
     * REQ-005: 자기 참조(사이클) record는 무한 재귀 없이 종료.
     * per-path visited로 사이클 차단, 스칼라 필드는 정상 emit.
     */
    @Test
    void cyclicNested_perPathGuard() {
        String src = "package p; record Node(p.Node parent, String name) {} "
                + "class In { void h(p.Node b){} }";
        CtModel m = model(src);

        // 무한 재귀가 없어야 하므로 타임아웃 없이 완료되어야 한다
        var shape = BodyShapeExtractor.extractFromTypeFlattened(m, firstParamType(m));

        assertThat(shape).isPresent();
        // "name" 스칼라 리프는 존재해야 함
        assertThat(shape.get().fields())
                .extracting(BodyShape.BodyField::name)
                .contains("name");
        // "parent" 가 사이클로 차단되어 리프(타입 FQN)로 emit됨 (무한 확장 없음)
        assertThat(shape.get().fields())
                .extracting(BodyShape.BodyField::name)
                .contains("parent");
    }

    /**
     * REQ-005: 같은 타입을 참조하는 형제 필드 둘 다 정상 전개됨.
     * per-path(스택-로컬) cycle guard 증명 — global visited면 두 번째가 잘린다.
     */
    @Test
    void siblingSameType_bothExpanded() {
        String src = "package p; "
                + "record Order(p.Address billing, p.Address shipping) {} "
                + "record Address(String city) {} "
                + "class In { void h(p.Order b){} }";
        CtModel m = model(src);

        var shape = BodyShapeExtractor.extractFromTypeFlattened(m, firstParamType(m));

        assertThat(shape).isPresent();
        assertThat(shape.get().fields())
                .extracting(BodyShape.BodyField::name)
                .contains("billing.city", "shipping.city");
        // 둘 다 java.lang.String javaType
        assertThat(shape.get().fields())
                .filteredOn(f -> "billing.city".equals(f.name()) || "shipping.city".equals(f.name()))
                .extracting(BodyShape.BodyField::javaType)
                .containsOnly("java.lang.String");
    }
}

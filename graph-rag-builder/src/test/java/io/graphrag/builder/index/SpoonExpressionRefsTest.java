package io.graphrag.builder.index;

import org.junit.jupiter.api.Test;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.visitor.filter.TypeFilter;
import static org.assertj.core.api.Assertions.assertThat;

class SpoonExpressionRefsTest {
    @Test
    void recordAccessorAndLiteral() {
        Launcher l = new Launcher();
        l.getEnvironment().setNoClasspath(true);
        l.getEnvironment().setComplianceLevel(17);
        l.addInputResource("src/test/resources/sample-src");
        CtModel model = l.buildModel();
        // "EMBARGOED".equals(stock.region()) 호출을 찾는다.
        CtInvocation<?> eq = model.getElements(new TypeFilter<>(CtInvocation.class)).stream()
                .filter(i -> "equals".equals(i.getExecutable().getSimpleName())
                        && SpoonExpressionRefs.stringLiteral(i.getTarget()) != null
                        && "EMBARGOED".equals(SpoonExpressionRefs.stringLiteral(i.getTarget())))
                .findFirst().orElseThrow();
        assertThat(SpoonExpressionRefs.fieldRef(eq.getArguments().get(0))).isEqualTo("region");
    }
}

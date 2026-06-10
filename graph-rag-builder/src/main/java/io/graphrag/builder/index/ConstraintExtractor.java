package io.graphrag.builder.index;

import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtConditional;
import spoon.reflect.code.CtIf;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.visitor.filter.TypeFilter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * handler 메서드의 분기 조건식을 정적으로 수집한다 (roadmap 1.2의 constraint 정보).
 * 콘콜릭(JDart) 부재를 보완하는 텍스트 수준 제약 — docs/decisions/explorer-engines.md.
 */
public class ConstraintExtractor {

    public record ConditionSpan(int startLine, int endLine, String text) {
    }

    public List<ConditionSpan> extract(Path srcDir, String classFqn, String methodName) {
        Launcher launcher = new Launcher();
        launcher.addInputResource(srcDir.toString());
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.getEnvironment().setComplianceLevel(17);
        CtModel model = launcher.buildModel();

        List<ConditionSpan> conditions = new ArrayList<>();
        for (CtType<?> type : model.getAllTypes()) {
            if (!type.getQualifiedName().replace('$', '.').equals(classFqn)) {
                continue;
            }
            for (CtMethod<?> method : type.getMethods()) {
                if (!method.getSimpleName().equals(methodName)) {
                    continue;
                }
                method.getElements(new TypeFilter<>(CtIf.class)).forEach(ctIf ->
                        addSpan(conditions, ctIf.getCondition().getPosition().getLine(),
                                ctIf.getCondition().getPosition().getEndLine(),
                                ctIf.getCondition().toString()));
                method.getElements(new TypeFilter<>(CtConditional.class)).forEach(ternary ->
                        addSpan(conditions, ternary.getCondition().getPosition().getLine(),
                                ternary.getCondition().getPosition().getEndLine(),
                                ternary.getCondition().toString()));
            }
        }
        conditions.sort((a, b) -> Integer.compare(a.startLine(), b.startLine()));
        return conditions;
    }

    private static void addSpan(List<ConditionSpan> conditions, int start, int end, String text) {
        conditions.add(new ConditionSpan(start, Math.max(start, end), text));
    }
}

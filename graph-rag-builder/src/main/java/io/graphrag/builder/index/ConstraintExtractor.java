package io.graphrag.builder.index;

import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.BinaryOperatorKind;
import spoon.reflect.code.CtBinaryOperator;
import spoon.reflect.code.CtConditional;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtIf;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.visitor.filter.TypeFilter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * handler 메서드의 분기 조건식을 정적으로 수집한다 (roadmap 1.2의 constraint 정보).
 * 콘콜릭(JDart) 부재를 보완하는 텍스트 수준 제약 — docs/decisions/explorer-engines.md.
 */
public class ConstraintExtractor {

    public record ConditionSpan(int startLine, int endLine, String text) {
    }

    /** field op literal 형태의 정수 비교식. 리터럴이 좌변이면 op를 flip해 우변 정규화. */
    public record Comparison(String fieldRef, String op, long literal, int line) {
    }

    private static final Map<BinaryOperatorKind, String> REL_OPS = Map.of(
            BinaryOperatorKind.GT, ">", BinaryOperatorKind.GE, ">=",
            BinaryOperatorKind.LT, "<", BinaryOperatorKind.LE, "<=",
            BinaryOperatorKind.EQ, "==", BinaryOperatorKind.NE, "!=");

    private static final Map<String, String> FLIP = Map.of(
            ">", "<", ">=", "<=", "<", ">", "<=", ">=", "==", "==", "!=", "!=");

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

    /**
     * handler 메서드의 비교식을 AST에서 직접 추출한다(권고 2: toString 정규식 회피).
     * field op literal / literal op field 형태만, 정수 리터럴만 1차 지원.
     */
    public List<Comparison> extractComparisons(Path srcDir, String classFqn, String methodName) {
        Launcher launcher = new Launcher();
        launcher.addInputResource(srcDir.toString());
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.getEnvironment().setComplianceLevel(17);
        CtModel model = launcher.buildModel();

        List<Comparison> comparisons = new ArrayList<>();
        for (CtType<?> type : model.getAllTypes()) {
            if (!type.getQualifiedName().replace('$', '.').equals(classFqn)) {
                continue;
            }
            for (CtMethod<?> method : type.getMethods()) {
                if (!method.getSimpleName().equals(methodName)) {
                    continue;
                }
                method.getElements(new TypeFilter<>(CtBinaryOperator.class)).forEach(op -> {
                    String opStr = REL_OPS.get(op.getKind());
                    if (opStr != null) {
                        addComparison(comparisons, op.getLeftHandOperand(),
                                op.getRightHandOperand(), opStr, op.getPosition().getLine());
                    }
                });
            }
        }
        comparisons.sort(Comparator.comparingInt(Comparison::line)
                .thenComparing(Comparison::fieldRef));
        return comparisons;
    }

    private static void addComparison(List<Comparison> out, CtExpression<?> left,
                                      CtExpression<?> right, String op, int line) {
        OptionalLong leftLit = literalLong(left);
        OptionalLong rightLit = literalLong(right);
        String leftRef = fieldRef(left);
        String rightRef = fieldRef(right);
        if (rightLit.isPresent() && leftRef != null) {
            out.add(new Comparison(leftRef, op, rightLit.getAsLong(), line));
        } else if (leftLit.isPresent() && rightRef != null) {
            out.add(new Comparison(rightRef, FLIP.get(op), leftLit.getAsLong(), line));
        }
    }

    private static OptionalLong literalLong(CtExpression<?> expr) {
        if (expr instanceof CtLiteral<?> lit && lit.getValue() instanceof Number n
                && !(lit.getValue() instanceof Double) && !(lit.getValue() instanceof Float)) {
            return OptionalLong.of(n.longValue());
        }
        return OptionalLong.empty();
    }

    private static String fieldRef(CtExpression<?> expr) {
        if (expr instanceof CtInvocation<?> inv) {
            String m = inv.getExecutable().getSimpleName();
            if (m.startsWith("get") && m.length() > 3) {
                return Character.toLowerCase(m.charAt(3)) + m.substring(4);
            }
            if (m.startsWith("is") && m.length() > 2) {
                return Character.toLowerCase(m.charAt(2)) + m.substring(3);
            }
            return m;   // record accessor: amount()
        }
        if (expr instanceof CtVariableRead<?> vr) {
            return vr.getVariable().getSimpleName();
        }
        if (expr instanceof CtFieldRead<?> fr) {
            return fr.getVariable().getSimpleName();
        }
        return null;
    }
}

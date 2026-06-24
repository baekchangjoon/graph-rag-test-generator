package io.graphrag.builder.index;

import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtVariableRead;

/** Spoon 표현식에서 필드 참조명·String 리터럴을 뽑는 공유 헬퍼(ConstraintExtractor·ResponseStringLiteralExtractor 공용). */
public final class SpoonExpressionRefs {
    private SpoonExpressionRefs() {}

    /** record accessor f()→"f", getF()→"f", isF()→"f", CtVariableRead/CtFieldRead simple-name; 아니면 null. */
    public static String fieldRef(CtExpression<?> expr) {
        if (expr instanceof CtInvocation<?> inv) {
            String m = inv.getExecutable().getSimpleName();
            if (m.startsWith("get") && m.length() > 3) {
                return Character.toLowerCase(m.charAt(3)) + m.substring(4);
            }
            if (m.startsWith("is") && m.length() > 2) {
                return Character.toLowerCase(m.charAt(2)) + m.substring(3);
            }
            return m;
        }
        if (expr instanceof CtVariableRead<?> vr) {
            return vr.getVariable().getSimpleName();
        }
        if (expr instanceof CtFieldRead<?> fr) {
            return fr.getVariable().getSimpleName();
        }
        return null;
    }

    /** CtLiteral<String>이면 그 값, 아니면 null. */
    public static String stringLiteral(CtExpression<?> expr) {
        if (expr instanceof CtLiteral<?> lit && lit.getValue() instanceof String s) {
            return s;
        }
        return null;
    }
}

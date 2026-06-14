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
import spoon.reflect.code.CtTypeAccess;
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

    /**
     * field op literal 형태의 정수 비교식. 리터럴이 좌변이면 op를 flip해 우변 정규화.
     * classFqn/method: 비교식이 발생한 위치(전 계층 추출이므로 태깅 — rec-1 라인 매칭에 사용).
     */
    public record Comparison(String classFqn, String method, String fieldRef,
                             String op, long literal, int line) {
    }

    /**
     * field.equals("LIT") / "LIT".equals(field) 형태의 문자열 동치. value = 비교 대상 리터럴.
     * 숫자 Comparison의 문자열 짝 — 해당 필드의 문자열 입력 후보로 환류된다.
     */
    public record StringEquality(String classFqn, String method, String fieldRef,
                                 String value, int line) {
    }

    /** 메서드 내 한 {@code &&} 조건의 원자들(동시 만족 대상). joint 입력 합성의 근거. */
    public record Conjunction(String classFqn, String method, int line, List<Atom> atoms) {
    }

    /** conjunction의 leaf 원자. NUMERIC만 numLiteral 유효; ENUM_EQ/STRING_EQ는 value 사용. */
    public record Atom(Kind kind, String fieldRef, String op, long numLiteral, String value) {
        public enum Kind { NUMERIC, ENUM_EQ, STRING_EQ }
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
     * SUT 소스 모델 전체(컨트롤러/서비스/공통/도메인 등 모든 클래스·메서드)의 비교식을
     * AST에서 직접 추출한다(정규식 아님). field op literal / literal op field, 정수 리터럴만.
     * 각 비교는 발생 위치 (classFqn, method, line)로 태깅된다. 1회 빌드.
     */
    public List<Comparison> extractComparisons(Path srcDir) {
        Launcher launcher = new Launcher();
        launcher.addInputResource(srcDir.toString());
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.getEnvironment().setComplianceLevel(17);
        CtModel model = launcher.buildModel();

        List<Comparison> comparisons = new ArrayList<>();
        for (CtBinaryOperator<?> op : model.getElements(new TypeFilter<>(CtBinaryOperator.class))) {
            String opStr = REL_OPS.get(op.getKind());
            if (opStr == null) {
                continue;
            }
            CtMethod<?> method = op.getParent(CtMethod.class);
            CtType<?> type = op.getParent(CtType.class);
            if (method == null || type == null) {
                continue;   // 메서드 밖(필드 초기화자/정적 블록 등)은 귀속 불가 → skip
            }
            addComparison(comparisons, op.getLeftHandOperand(), op.getRightHandOperand(),
                    opStr, op.getPosition().getLine(),
                    type.getQualifiedName().replace('$', '.'), method.getSimpleName());
        }
        comparisons.sort(Comparator.comparing(Comparison::classFqn)
                .thenComparing(Comparison::method)
                .thenComparingInt(Comparison::line)
                .thenComparing(Comparison::fieldRef));
        return comparisons;
    }

    /**
     * SUT 소스 전체에서 문자열 동치 {@code field.equals("LIT")} / {@code "LIT".equals(field)}를
     * AST로 추출한다(전 계층, 1회 빌드). 숫자 extractComparisons의 문자열 짝.
     */
    public List<StringEquality> extractStringEqualities(Path srcDir) {
        Launcher launcher = new Launcher();
        launcher.addInputResource(srcDir.toString());
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.getEnvironment().setComplianceLevel(17);
        CtModel model = launcher.buildModel();

        List<StringEquality> out = new ArrayList<>();
        for (CtInvocation<?> inv : model.getElements(new TypeFilter<>(CtInvocation.class))) {
            if (!"equals".equals(inv.getExecutable().getSimpleName())
                    || inv.getArguments().size() != 1 || inv.getTarget() == null) {
                continue;
            }
            CtExpression<?> target = inv.getTarget();
            CtExpression<?> arg = inv.getArguments().get(0);
            String fieldRef = null;
            String value = null;
            String argLit = stringLiteral(arg);
            String targetLit = stringLiteral(target);
            if (argLit != null && fieldRef(target) != null) {
                fieldRef = fieldRef(target);
                value = argLit;
            } else if (targetLit != null && fieldRef(arg) != null) {
                fieldRef = fieldRef(arg);
                value = targetLit;
            }
            if (fieldRef == null) {
                continue;
            }
            CtMethod<?> method = inv.getParent(CtMethod.class);
            CtType<?> type = inv.getParent(CtType.class);
            if (method == null || type == null) {
                continue;
            }
            out.add(new StringEquality(type.getQualifiedName().replace('$', '.'),
                    method.getSimpleName(), fieldRef, value, inv.getPosition().getLine()));
        }
        out.sort(Comparator.comparing(StringEquality::classFqn)
                .thenComparing(StringEquality::method)
                .thenComparingInt(StringEquality::line)
                .thenComparing(StringEquality::fieldRef));
        return out;
    }

    /**
     * 메서드 내 {@code &&} 조건을 conjunction 단위로 추출(원자 동시성 보존). 전 계층 1회 빌드.
     * 조건 루트(CtIf/CtConditional의 getCondition)가 AND인 것만 대상 — getElements(CtBinaryOperator)로
     * 전역 AND를 훑으면 중첩 &&가 중복 수집되므로 쓰지 않는다. 서로 다른 fieldRef 2개+만 보존.
     */
    public List<Conjunction> extractConjunctions(Path srcDir) {
        Launcher launcher = new Launcher();
        launcher.addInputResource(srcDir.toString());
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.getEnvironment().setComplianceLevel(17);
        CtModel model = launcher.buildModel();

        List<CtExpression<?>> conditions = new ArrayList<>();
        for (CtIf ctIf : model.getElements(new TypeFilter<>(CtIf.class))) {
            conditions.add(ctIf.getCondition());
        }
        for (CtConditional<?> tern : model.getElements(new TypeFilter<>(CtConditional.class))) {
            conditions.add(tern.getCondition());
        }

        List<Conjunction> out = new ArrayList<>();
        for (CtExpression<?> cond : conditions) {
            if (!(cond instanceof CtBinaryOperator<?> bin)
                    || bin.getKind() != BinaryOperatorKind.AND) {
                continue;
            }
            List<CtExpression<?>> leaves = new ArrayList<>();
            flattenAnd(bin, leaves);
            List<Atom> atoms = new ArrayList<>();
            for (CtExpression<?> leaf : leaves) {
                Atom a = toAtom(leaf);
                if (a != null) {
                    atoms.add(a);
                }
            }
            if (atoms.stream().map(Atom::fieldRef).distinct().count() < 2) {
                continue;
            }
            CtMethod<?> method = bin.getParent(CtMethod.class);
            CtType<?> type = bin.getParent(CtType.class);
            if (method == null || type == null) {
                continue;
            }
            out.add(new Conjunction(type.getQualifiedName().replace('$', '.'),
                    method.getSimpleName(), bin.getPosition().getLine(), atoms));
        }
        out.sort(Comparator.comparing(Conjunction::classFqn)
                .thenComparing(Conjunction::method)
                .thenComparingInt(Conjunction::line));
        return out;
    }

    private static void flattenAnd(CtExpression<?> expr, List<CtExpression<?>> leaves) {
        if (expr instanceof CtBinaryOperator<?> bin && bin.getKind() == BinaryOperatorKind.AND) {
            flattenAnd(bin.getLeftHandOperand(), leaves);
            flattenAnd(bin.getRightHandOperand(), leaves);
        } else {
            leaves.add(expr);
        }
    }

    private static Atom toAtom(CtExpression<?> leaf) {
        if (leaf instanceof CtBinaryOperator<?> bin) {
            String op = REL_OPS.get(bin.getKind());
            if (op == null) {
                return null;
            }
            CtExpression<?> left = bin.getLeftHandOperand();
            CtExpression<?> right = bin.getRightHandOperand();
            OptionalLong leftLit = literalLong(left);
            OptionalLong rightLit = literalLong(right);
            String leftRef = fieldRef(left);
            String rightRef = fieldRef(right);
            if (rightLit.isPresent() && leftRef != null) {
                return new Atom(Atom.Kind.NUMERIC, leftRef, op, rightLit.getAsLong(), null);
            }
            if (leftLit.isPresent() && rightRef != null) {
                return new Atom(Atom.Kind.NUMERIC, rightRef, FLIP.get(op), leftLit.getAsLong(), null);
            }
            if (bin.getKind() == BinaryOperatorKind.EQ) {
                String enumConst = enumConstant(right);
                if (enumConst != null && fieldRef(left) != null) {
                    return new Atom(Atom.Kind.ENUM_EQ, fieldRef(left), "==", 0, enumConst);
                }
                enumConst = enumConstant(left);
                if (enumConst != null && fieldRef(right) != null) {
                    return new Atom(Atom.Kind.ENUM_EQ, fieldRef(right), "==", 0, enumConst);
                }
            }
            return null;
        }
        if (leaf instanceof CtInvocation<?> inv
                && "equals".equals(inv.getExecutable().getSimpleName())
                && inv.getArguments().size() == 1 && inv.getTarget() != null) {
            CtExpression<?> target = inv.getTarget();
            CtExpression<?> arg = inv.getArguments().get(0);
            String argLit = stringLiteral(arg);
            String targetLit = stringLiteral(target);
            if (argLit != null && fieldRef(target) != null) {
                return new Atom(Atom.Kind.STRING_EQ, fieldRef(target), "==", 0, argLit);
            }
            if (targetLit != null && fieldRef(arg) != null) {
                return new Atom(Atom.Kind.STRING_EQ, fieldRef(arg), "==", 0, targetLit);
            }
        }
        return null;
    }

    /** {@code Type.CONST} 정적 enum 상수 읽기면 상수 simpleName, 아니면 null. */
    private static String enumConstant(CtExpression<?> expr) {
        if (expr instanceof CtFieldRead<?> fr && fr.getTarget() instanceof CtTypeAccess) {
            return fr.getVariable().getSimpleName();
        }
        return null;
    }

    private static String stringLiteral(CtExpression<?> expr) {
        if (expr instanceof CtLiteral<?> lit && lit.getValue() instanceof String s) {
            return s;
        }
        return null;
    }

    private static void addComparison(List<Comparison> out, CtExpression<?> left,
                                      CtExpression<?> right, String op, int line,
                                      String classFqn, String method) {
        OptionalLong leftLit = literalLong(left);
        OptionalLong rightLit = literalLong(right);
        String leftRef = fieldRef(left);
        String rightRef = fieldRef(right);
        if (rightLit.isPresent() && leftRef != null) {
            out.add(new Comparison(classFqn, method, leftRef, op, rightLit.getAsLong(), line));
        } else if (leftLit.isPresent() && rightRef != null) {
            out.add(new Comparison(classFqn, method, rightRef, FLIP.get(op), leftLit.getAsLong(), line));
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

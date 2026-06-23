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
import spoon.reflect.code.CtUnaryOperator;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.code.UnaryOperatorKind;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.visitor.filter.TypeFilter;

import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

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

    /** 필드-대-필드 비교 가드의 종류 (REQ-006, REQ-008a). */
    public enum JoinKind { NUMERIC, STRING }

    /**
     * 양변이 모두 필드 참조인 비교식. NUMERIC: 관계 연산자(REL_OPS)의 양변이 필드이고 리터럴 없음.
     * STRING: {@code field.equals(field)} 형태로 양변이 필드이고 문자열 리터럴 없음.
     */
    public record JoinGuard(String classFqn, String method, int line,
                            String leftRef, String op, String rightRef, JoinKind kind) {
    }

    /** 상태 의존 가드의 종류 (Stage 4 StateGuardOracle). */
    public enum GuardKind { TEMPORAL, ENUM, BOOLEAN, NULLITY, NUMERIC }

    /**
     * 비교 피연산자(comparand)의 종류. LITERAL: 리터럴 상수(숫자·문자열·boolean); PARAM: 요청 파라미터/지역변수.
     * StateGuard의 op/comparandKind/comparand 3필드가 채워질 때 사용 (BOOLEAN/NULLITY/NUMERIC 검출 — 다음 task).
     */
    public enum ComparandKind { LITERAL, PARAM }

    /**
     * 저장된(시드된) 단일 행 상태로 분기하는 가드. by-id 엔드포인트의 양 arm을 열기 위한 대체 시드
     * 변종 합성의 근거 (docs/superpowers/plans/2026-06-15-stage4-state-guard-two-arm-seeds.md).
     * <ul>
     *   <li>TEMPORAL: {@code row.getX().isBefore/isAfter(LocalDate(Time).now())} → column=snake(X),
     *       enumType=null, negatedConstants=[]. 대체 시드는 과거(1900-01-01) 날짜로 반대 arm을 연다.</li>
     *   <li>ENUM: {@code row.getStatus() != A}(NE) / {@code == B}(EQ) → column, enumType(상수 타입 simpleName),
     *       negatedConstants={A…}(NE 정렬) + positiveConstants={B…}(EQ 정렬). 대체 시드 변종은 EQ 각 상수
     *       (그 == arm) + NE 잔여 상수 + (positive/negated 밖) 잔여 1개(else arm)로 다중 전이 arm을 연다.</li>
     * </ul>
     * <p>op/comparandKind/comparand: BOOLEAN/NULLITY/NUMERIC 가드에서 채워지는 확장 필드. TEMPORAL/ENUM에서는 null.
     */
    public record StateGuard(String classFqn, String method, int line, String column,
                             GuardKind kind, String enumType,
                             List<String> negatedConstants, List<String> positiveConstants,
                             String op, ComparandKind comparandKind, String comparand) {
        /** 후방호환 8-arg(op/comparandKind/comparand 없음 — null 위임). */
        public StateGuard(String classFqn, String method, int line, String column,
                          GuardKind kind, String enumType,
                          List<String> negatedConstants, List<String> positiveConstants) {
            this(classFqn, method, line, column, kind, enumType, negatedConstants, positiveConstants,
                    null, null, null);
        }

        /** 후방호환 7-arg(positiveConstants 없음 — TEMPORAL/기존 NE emit·테스트 보존). */
        public StateGuard(String classFqn, String method, int line, String column,
                          GuardKind kind, String enumType, List<String> negatedConstants) {
            this(classFqn, method, line, column, kind, enumType, negatedConstants, List.of());
        }
    }

    private static final Map<BinaryOperatorKind, String> REL_OPS = Map.of(
            BinaryOperatorKind.GT, ">", BinaryOperatorKind.GE, ">=",
            BinaryOperatorKind.LT, "<", BinaryOperatorKind.LE, "<=",
            BinaryOperatorKind.EQ, "==", BinaryOperatorKind.NE, "!=");

    private static final Map<String, String> FLIP = Map.of(
            ">", "<", ">=", "<=", "<", ">", "<=", ">=", "==", "==", "!=", "!=");

    /**
     * 핸들러 메서드 본문의 직접(1-hop) {@link CtInvocation} 집합과 핸들러 자신을 반환한다 (REQ-011, Phase 2).
     * 반환 집합의 각 Entry: key = 호출 대상 declaringType FQN (미해소이면 simpleName), value = 메서드 simpleName.
     * 핸들러 자신 (handlerClass, handlerMethod) 도 반드시 포함.
     * noClasspath 모드이므로 declaringType이 인터페이스 또는 null일 수 있다 — null이면 simpleName을 key로 보존.
     * 1-hop만: 호출된 메서드 내부의 추가 호출은 따라가지 않는다.
     */
    public Set<Map.Entry<String, String>> reachableMethods(Path srcDir, String handlerClass, String handlerMethod) {
        Launcher launcher = new Launcher();
        launcher.addInputResource(srcDir.toString());
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.getEnvironment().setComplianceLevel(17);
        CtModel model = launcher.buildModel();

        Set<Map.Entry<String, String>> result = new HashSet<>();
        // 핸들러 자신을 항상 포함
        result.add(new AbstractMap.SimpleEntry<>(handlerClass, handlerMethod));

        for (CtType<?> type : model.getAllTypes()) {
            if (!type.getQualifiedName().replace('$', '.').equals(handlerClass)) {
                continue;
            }
            for (CtMethod<?> method : type.getMethods()) {
                if (!method.getSimpleName().equals(handlerMethod)) {
                    continue;
                }
                for (CtInvocation<?> inv : method.getElements(new TypeFilter<>(CtInvocation.class))) {
                    var executable = inv.getExecutable();
                    String methodName = executable.getSimpleName();
                    var declaringType = executable.getDeclaringType();
                    String typeFqn;
                    if (declaringType != null) {
                        String fqn = declaringType.getQualifiedName();
                        typeFqn = (fqn != null && !fqn.isEmpty()) ? fqn.replace('$', '.') : declaringType.getSimpleName();
                    } else {
                        typeFqn = executable.getSimpleName(); // 미해소 fallback: simpleName
                    }
                    result.add(new AbstractMap.SimpleEntry<>(typeFqn, methodName));
                }
            }
        }
        return result;
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
     * SUT 소스 전체에서 양변이 모두 필드 참조인 비교식을 추출한다 (REQ-006, REQ-008a).
     * NUMERIC: REL_OPS의 관계 연산자이고 양변이 fieldRef != null이며 리터럴이 없는 것.
     * STRING: {@code field.equals(field)} 형태이고 양변이 fieldRef != null이며 문자열 리터럴이 없는 것.
     * 1회 빌드. 정렬·dedupe는 기존 패턴과 동일.
     */
    public List<JoinGuard> extractJoinGuards(Path srcDir) {
        Launcher launcher = new Launcher();
        launcher.addInputResource(srcDir.toString());
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.getEnvironment().setComplianceLevel(17);
        CtModel model = launcher.buildModel();

        List<JoinGuard> out = new ArrayList<>();

        // NUMERIC: field op field (리터럴 없음)
        for (CtBinaryOperator<?> op : model.getElements(new TypeFilter<>(CtBinaryOperator.class))) {
            String opStr = REL_OPS.get(op.getKind());
            if (opStr == null) {
                continue;
            }
            CtExpression<?> left = op.getLeftHandOperand();
            CtExpression<?> right = op.getRightHandOperand();
            String leftRef = fieldRef(left);
            String rightRef = fieldRef(right);
            if (leftRef == null || rightRef == null) {
                continue;
            }
            if (literalLong(left).isPresent() || literalLong(right).isPresent()) {
                continue;   // 한쪽이라도 리터럴이면 Comparison 대상 — JoinGuard 아님
            }
            if (enumConstant(left) != null || enumConstant(right) != null) {
                continue;   // field == Enum.CONST 는 join guard 아님 (enum 동치는 별도 처리)
            }
            CtMethod<?> method = op.getParent(CtMethod.class);
            CtType<?> type = op.getParent(CtType.class);
            if (method == null || type == null) {
                continue;
            }
            out.add(new JoinGuard(type.getQualifiedName().replace('$', '.'), method.getSimpleName(),
                    op.getPosition().getLine(), leftRef, opStr, rightRef, JoinKind.NUMERIC));
        }

        // STRING: field.equals(field) (문자열 리터럴 없음)
        for (CtInvocation<?> inv : model.getElements(new TypeFilter<>(CtInvocation.class))) {
            if (!"equals".equals(inv.getExecutable().getSimpleName())
                    || inv.getArguments().size() != 1 || inv.getTarget() == null) {
                continue;
            }
            CtExpression<?> target = inv.getTarget();
            CtExpression<?> arg = inv.getArguments().get(0);
            String targetRef = fieldRef(target);
            String argRef = fieldRef(arg);
            if (targetRef == null || argRef == null) {
                continue;
            }
            if (stringLiteral(target) != null || stringLiteral(arg) != null) {
                continue;   // 한쪽이라도 리터럴이면 StringEquality 대상 — JoinGuard 아님
            }
            CtMethod<?> method = inv.getParent(CtMethod.class);
            CtType<?> type = inv.getParent(CtType.class);
            if (method == null || type == null) {
                continue;
            }
            out.add(new JoinGuard(type.getQualifiedName().replace('$', '.'), method.getSimpleName(),
                    inv.getPosition().getLine(), targetRef, "equals", argRef, JoinKind.STRING));
        }

        out.sort(Comparator.comparing(JoinGuard::classFqn)
                .thenComparing(JoinGuard::method)
                .thenComparingInt(JoinGuard::line)
                .thenComparing(JoinGuard::leftRef));
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

    /**
     * 컬럼(snake) → 가드에서 비교된 enum 상수들. {@code accessor() == Type.CONST} / {@code != } 를
     * 전 계층에서 수집. 휴리스틱(컬럼명 추측)이 아니라 가드가 직접 알려주는 유효 enum 값 →
     * 시드 행의 enum 컬럼을 유효값으로 채워 읽기 500을 방지(Bug 3). 1회 빌드.
     */
    public Map<String, List<String>> extractEnumColumns(Path srcDir) {
        Launcher launcher = new Launcher();
        launcher.addInputResource(srcDir.toString());
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.getEnvironment().setComplianceLevel(17);
        CtModel model = launcher.buildModel();

        java.util.TreeMap<String, java.util.TreeSet<String>> acc = new java.util.TreeMap<>();
        for (CtBinaryOperator<?> op : model.getElements(new TypeFilter<>(CtBinaryOperator.class))) {
            if (op.getKind() != BinaryOperatorKind.EQ && op.getKind() != BinaryOperatorKind.NE) {
                continue;
            }
            String field = null;
            String value = enumConstant(op.getRightHandOperand());
            if (value != null && fieldRef(op.getLeftHandOperand()) != null) {
                field = fieldRef(op.getLeftHandOperand());
            } else {
                value = enumConstant(op.getLeftHandOperand());
                if (value != null && fieldRef(op.getRightHandOperand()) != null) {
                    field = fieldRef(op.getRightHandOperand());
                }
            }
            if (field == null) {
                continue;
            }
            acc.computeIfAbsent(snake(field), k -> new java.util.TreeSet<>()).add(value);
        }
        Map<String, List<String>> out = new java.util.TreeMap<>();
        acc.forEach((k, v) -> out.put(k, List.copyOf(v)));
        return out;
    }

    /**
     * 상태 의존 가드(저장 행 상태로 분기)를 전 계층에서 추출 (Stage 4). 보수적 — 인식 못하면 emit 안 함
     * (false negative만). TEMPORAL: {@code getter().isBefore/isAfter(LocalDate(Time).now())}.
     * ENUM: {@code getter() != A && != B} (NE만; == 가드는 반대-arm 의미가 달라 v1 제외). 1회 빌드.
     */
    public List<StateGuard> extractStateGuards(Path srcDir) {
        Launcher launcher = new Launcher();
        launcher.addInputResource(srcDir.toString());
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.getEnvironment().setComplianceLevel(17);
        CtModel model = launcher.buildModel();

        List<StateGuard> out = new ArrayList<>();

        // TEMPORAL: row.getX().isBefore/isAfter(LocalDate(Time).now())
        for (CtInvocation<?> inv : model.getElements(new TypeFilter<>(CtInvocation.class))) {
            String name = inv.getExecutable().getSimpleName();
            if (!name.equals("isBefore") && !name.equals("isAfter")) {
                continue;
            }
            if (inv.getArguments().size() != 1 || !isNowCall(inv.getArguments().get(0))) {
                continue;
            }
            CtExpression<?> target = inv.getTarget();   // 컬럼은 isBefore의 target getter에서 유도(‘before’ 아님)
            String ref = getterRef(target);              // 저장 행 getter만(파라미터/지역변수 날짜 비교 제외)
            CtMethod<?> method = inv.getParent(CtMethod.class);
            CtType<?> type = inv.getParent(CtType.class);
            if (ref == null || method == null || type == null) {
                continue;
            }
            out.add(new StateGuard(type.getQualifiedName().replace('$', '.'), method.getSimpleName(),
                    inv.getPosition().getLine(), snake(ref), GuardKind.TEMPORAL, null, List.of()));
        }

        // ENUM: getter() != CONST (NE) / getter() == CONST (EQ). (class|method|column)별로 모아 한 가드로.
        // NE는 negatedConstants, EQ는 positiveConstants로 누적 — 한 컬럼에 둘이 공존하면 둘 다 보존.
        java.util.LinkedHashMap<String, EnumGuardAcc> enumAcc = new java.util.LinkedHashMap<>();
        for (CtBinaryOperator<?> op : model.getElements(new TypeFilter<>(CtBinaryOperator.class))) {
            boolean ne = op.getKind() == BinaryOperatorKind.NE;
            boolean eq = op.getKind() == BinaryOperatorKind.EQ;
            if (!ne && !eq) {
                continue;
            }
            String constName = enumConstant(op.getRightHandOperand());
            String enumType = enumTypeAccess(op.getRightHandOperand());
            // 필드 측은 저장 행 getter/accessor만(bare 파라미터/지역변수 enum 비교 = pure-input → 제외)
            String field = constName != null ? getterRef(op.getLeftHandOperand()) : null;
            if (field == null) {
                constName = enumConstant(op.getLeftHandOperand());
                enumType = enumTypeAccess(op.getLeftHandOperand());
                field = constName != null ? getterRef(op.getRightHandOperand()) : null;
            }
            if (field == null || constName == null) {
                continue;
            }
            CtMethod<?> method = op.getParent(CtMethod.class);
            CtType<?> type = op.getParent(CtType.class);
            if (method == null || type == null) {
                continue;
            }
            final String classFqn = type.getQualifiedName().replace('$', '.');
            final String column = snake(field);
            final String enumTypeF = enumType;
            final String methodName = method.getSimpleName();
            final int line = op.getPosition().getLine();
            String key = classFqn + '|' + methodName + '|' + column;
            EnumGuardAcc acc = enumAcc.computeIfAbsent(key, k ->
                    new EnumGuardAcc(classFqn, methodName, line, column, enumTypeF));
            (ne ? acc.constants : acc.positives).add(constName);
            acc.line = Math.min(acc.line, line);
        }
        enumAcc.values().forEach(a -> out.add(new StateGuard(a.classFqn, a.method, a.line,
                a.column, GuardKind.ENUM, a.enumType, List.copyOf(a.constants), List.copyOf(a.positives))));

        // BOOLEAN: CtIf 조건이 (a) boolean getter 단독, (b) !getter(), (c) getter()==true/false 형태.
        // getterRef로 파라미터·지역변수 제외(저장 행 getter invocation만). boolean literal == / != 비교도 처리.
        for (CtIf ctIf : model.getElements(new TypeFilter<>(CtIf.class))) {
            CtExpression<?> cond = ctIf.getCondition();
            StateGuard bg = booleanGuardFromCondition(cond);
            if (bg == null) {
                continue;
            }
            CtMethod<?> method = ctIf.getParent(CtMethod.class);
            CtType<?> type = ctIf.getParent(CtType.class);
            if (method == null || type == null) {
                continue;
            }
            out.add(new StateGuard(type.getQualifiedName().replace('$', '.'), method.getSimpleName(),
                    ctIf.getPosition().getLine(), bg.column(), GuardKind.BOOLEAN, null,
                    List.of(), List.of(), bg.op(), ComparandKind.LITERAL, bg.comparand()));
        }

        // NULLITY: CtIf 조건이 getter() == null 또는 getter() != null 형태(저장 행 getter만).
        // null literal은 CtLiteral.getValue()==null으로 판별(boolean/enum literal과 구분).
        for (CtIf ctIf : model.getElements(new TypeFilter<>(CtIf.class))) {
            CtExpression<?> cond = ctIf.getCondition();
            StateGuard ng = nullityGuardFromCondition(cond);
            if (ng == null) {
                continue;
            }
            CtMethod<?> method = ctIf.getParent(CtMethod.class);
            CtType<?> type = ctIf.getParent(CtType.class);
            if (method == null || type == null) {
                continue;
            }
            out.add(new StateGuard(type.getQualifiedName().replace('$', '.'), method.getSimpleName(),
                    ctIf.getPosition().getLine(), ng.column(), GuardKind.NULLITY, null,
                    List.of(), List.of(), ng.op(), ComparandKind.LITERAL, "null"));
        }

        // NUMERIC: CtIf 조건이 getter() OP 정수리터럴 형태(저장 행 getter만, Double/Float 제외).
        // 음수 리터럴은 CtUnaryOperator(MINUS)로 CtLiteral을 래핑 → literalLongWithNeg으로 언랩.
        // EQ/NE는 enum·boolean·null 가드가 이미 걸러진 뒤 정수리터럴인 경우만 도달하므로
        // CtIf 조건을 재스캔해도 중복 emit이 없다(BOOLEAN/NULLITY는 CtIf루프, ENUM은 별도 ENUM 블록).
        for (CtIf ctIf : model.getElements(new TypeFilter<>(CtIf.class))) {
            CtExpression<?> cond = ctIf.getCondition();
            if (!(cond instanceof CtBinaryOperator<?> bin)) {
                continue;
            }
            String opStr = REL_OPS.get(bin.getKind());
            if (opStr == null) {
                continue;
            }
            CtExpression<?> left = bin.getLeftHandOperand();
            CtExpression<?> right = bin.getRightHandOperand();
            String getterField = getterRef(left);
            OptionalLong rightLit = literalLongWithNeg(right);
            if (getterField != null && rightLit.isPresent()) {
                // getter() OP literal
                CtMethod<?> method = ctIf.getParent(CtMethod.class);
                CtType<?> type = ctIf.getParent(CtType.class);
                if (method == null || type == null) continue;
                out.add(new StateGuard(type.getQualifiedName().replace('$', '.'), method.getSimpleName(),
                        ctIf.getPosition().getLine(), snake(getterField), GuardKind.NUMERIC, null,
                        List.of(), List.of(), opStr, ComparandKind.LITERAL,
                        String.valueOf(rightLit.getAsLong())));
                continue;
            }
            String getterFieldR = getterRef(right);
            OptionalLong leftLit = literalLongWithNeg(left);
            if (getterFieldR != null && leftLit.isPresent()) {
                // literal OP getter() → FLIP op
                CtMethod<?> method = ctIf.getParent(CtMethod.class);
                CtType<?> type = ctIf.getParent(CtType.class);
                if (method == null || type == null) continue;
                out.add(new StateGuard(type.getQualifiedName().replace('$', '.'), method.getSimpleName(),
                        ctIf.getPosition().getLine(), snake(getterFieldR), GuardKind.NUMERIC, null,
                        List.of(), List.of(), FLIP.get(opStr), ComparandKind.LITERAL,
                        String.valueOf(leftLit.getAsLong())));
                continue;
            }
            // NUMERIC-vs-PARAM: getter() OP paramRef — 가드 메서드의 파라미터를 직접 참조하는 경우.
            // 중간 계산(CtBinaryOperator 등)을 경유하는 경우는 CtVariableRead가 아니므로 자동 제외.
            String paramName = directParamName(right);
            if (getterField != null && paramName != null) {
                // getter() OP param
                CtMethod<?> method = ctIf.getParent(CtMethod.class);
                CtType<?> type = ctIf.getParent(CtType.class);
                if (method == null || type == null) continue;
                out.add(new StateGuard(type.getQualifiedName().replace('$', '.'), method.getSimpleName(),
                        ctIf.getPosition().getLine(), snake(getterField), GuardKind.NUMERIC, null,
                        List.of(), List.of(), opStr, ComparandKind.PARAM, paramName));
                continue;
            }
            String paramNameL = directParamName(left);
            if (getterFieldR != null && paramNameL != null) {
                // param OP getter() → FLIP op
                CtMethod<?> method = ctIf.getParent(CtMethod.class);
                CtType<?> type = ctIf.getParent(CtType.class);
                if (method == null || type == null) continue;
                out.add(new StateGuard(type.getQualifiedName().replace('$', '.'), method.getSimpleName(),
                        ctIf.getPosition().getLine(), snake(getterFieldR), GuardKind.NUMERIC, null,
                        List.of(), List.of(), FLIP.get(opStr), ComparandKind.PARAM, paramNameL));
            }
        }

        out.sort(Comparator.comparing(StateGuard::classFqn).thenComparing(StateGuard::method)
                .thenComparingInt(StateGuard::line));
        return out;
    }

    /**
     * CtIf 조건식에서 BOOLEAN getter 가드를 인식. 저장 행 getter invocation(getterRef != null)만.
     * (a) 단독 getter invocation → comparand="true"
     * (b) !getter() → comparand="false"
     * (c) getter() == true/false 또는 getter() != true/false (BinaryOperator)
     * 인식되면 column/op/comparand만 채운 임시 StateGuard 반환, 아니면 null.
     */
    private static StateGuard booleanGuardFromCondition(CtExpression<?> cond) {
        // (a) 단독 boolean getter invocation: if(b.getActive())
        if (cond instanceof CtInvocation<?> inv) {
            String ref = getterRef(inv);
            if (ref != null) {
                return new StateGuard(null, null, 0, snake(ref), GuardKind.BOOLEAN, null,
                        List.of(), List.of(), "==", ComparandKind.LITERAL, "true");
            }
        }
        // (b) !getter(): CtUnaryOperator(NOT, getter())
        if (cond instanceof CtUnaryOperator<?> uo && uo.getKind() == UnaryOperatorKind.NOT) {
            CtExpression<?> operand = uo.getOperand();
            if (operand instanceof CtInvocation<?> inv) {
                String ref = getterRef(inv);
                if (ref != null) {
                    return new StateGuard(null, null, 0, snake(ref), GuardKind.BOOLEAN, null,
                            List.of(), List.of(), "==", ComparandKind.LITERAL, "false");
                }
            }
        }
        // (c) getter() == true/false 또는 getter() != true/false
        if (cond instanceof CtBinaryOperator<?> bin) {
            boolean isEq = bin.getKind() == BinaryOperatorKind.EQ;
            boolean isNe = bin.getKind() == BinaryOperatorKind.NE;
            if (!isEq && !isNe) {
                return null;
            }
            CtExpression<?> left = bin.getLeftHandOperand();
            CtExpression<?> right = bin.getRightHandOperand();
            // getter() == true/false 또는 true/false == getter()
            Boolean boolLit = booleanLiteral(right);
            CtExpression<?> getterSide = left;
            if (boolLit == null) {
                boolLit = booleanLiteral(left);
                getterSide = right;
            }
            if (boolLit == null) {
                return null;
            }
            String ref = getterSide instanceof CtInvocation<?> inv ? getterRef(inv) : null;
            if (ref == null) {
                return null;
            }
            // != true → false, != false → true (flip for NE)
            boolean effectiveValue = isEq ? boolLit : !boolLit;
            return new StateGuard(null, null, 0, snake(ref), GuardKind.BOOLEAN, null,
                    List.of(), List.of(), "==", ComparandKind.LITERAL, String.valueOf(effectiveValue));
        }
        return null;
    }

    /**
     * CtIf 조건식에서 NULLITY 가드를 인식. 저장 행 getter invocation(getterRef != null)만.
     * getter() == null → op="==" / getter() != null → op="!="
     * null literal은 CtLiteral.getValue()==null으로 판별(boolean/enum literal과 구분).
     * 인식되면 column/op만 채운 임시 StateGuard 반환(comparand는 호출부에서 "null" 고정), 아니면 null.
     */
    private static StateGuard nullityGuardFromCondition(CtExpression<?> cond) {
        if (!(cond instanceof CtBinaryOperator<?> bin)) {
            return null;
        }
        boolean isEq = bin.getKind() == BinaryOperatorKind.EQ;
        boolean isNe = bin.getKind() == BinaryOperatorKind.NE;
        if (!isEq && !isNe) {
            return null;
        }
        CtExpression<?> left = bin.getLeftHandOperand();
        CtExpression<?> right = bin.getRightHandOperand();
        // getter() == null 또는 null == getter()
        boolean rightIsNull = right instanceof CtLiteral<?> lit && lit.getValue() == null;
        boolean leftIsNull = left instanceof CtLiteral<?> lit && lit.getValue() == null;
        CtExpression<?> getterSide;
        if (rightIsNull) {
            getterSide = left;
        } else if (leftIsNull) {
            getterSide = right;
        } else {
            return null;
        }
        String ref = getterSide instanceof CtInvocation<?> inv ? getterRef(inv) : null;
        if (ref == null) {
            return null;
        }
        String op = isEq ? "==" : "!=";
        return new StateGuard(null, null, 0, snake(ref), GuardKind.NULLITY, null,
                List.of(), List.of(), op, ComparandKind.LITERAL, "null");
    }

    /** boolean 리터럴이면 Boolean 값, 아니면 null. */
    private static Boolean booleanLiteral(CtExpression<?> expr) {
        if (expr instanceof CtLiteral<?> lit && lit.getValue() instanceof Boolean b) {
            return b;
        }
        return null;
    }

    /** ENUM 가드 누적기: (class,method,column)별 부정(NE)·긍정(EQ) 상수 집합(정렬). */
    private static final class EnumGuardAcc {
        final String classFqn;
        final String method;
        int line;
        final String column;
        final String enumType;
        final java.util.TreeSet<String> constants = new java.util.TreeSet<>();   // NE
        final java.util.TreeSet<String> positives = new java.util.TreeSet<>();   // EQ

        EnumGuardAcc(String classFqn, String method, int line, String column, String enumType) {
            this.classFqn = classFqn;
            this.method = method;
            this.line = line;
            this.column = column;
            this.enumType = enumType;
        }
    }

    /** {@code LocalDate.now()} / {@code LocalDateTime.now()} 호출이면 true. */
    private static boolean isNowCall(CtExpression<?> expr) {
        if (!(expr instanceof CtInvocation<?> inv) || !inv.getExecutable().getSimpleName().equals("now")) {
            return false;
        }
        if (inv.getTarget() instanceof CtTypeAccess<?> ta && ta.getAccessedType() != null) {
            String t = ta.getAccessedType().getSimpleName();
            return t.equals("LocalDate") || t.equals("LocalDateTime");
        }
        return false;
    }

    /**
     * get/is 접두사 getter invocation(저장 행 상태 접근)일 때만 fieldRef, 아니면 null.
     * record accessor(amount() 등 접두사 없는 메서드)는 저장 행 접근으로 보지 않아 제외.
     */
    private static String getterRef(CtExpression<?> expr) {
        if (!(expr instanceof CtInvocation<?> inv)) return null;
        String m = inv.getExecutable().getSimpleName();
        if (m.startsWith("get") && m.length() > 3) return Character.toLowerCase(m.charAt(3)) + m.substring(4);
        if (m.startsWith("is") && m.length() > 2)  return Character.toLowerCase(m.charAt(2)) + m.substring(3);
        return null;   // record accessor 등 비-getter는 저장 행 접근으로 보지 않음
    }

    /** {@code Type.CONST} enum 상수 읽기의 선언 타입 simpleName(예: BookingStatus), 아니면 null. */
    private static String enumTypeAccess(CtExpression<?> expr) {
        if (expr instanceof CtFieldRead<?> fr && fr.getTarget() instanceof CtTypeAccess<?> ta
                && ta.getAccessedType() != null) {
            return ta.getAccessedType().getSimpleName();
        }
        return null;
    }

    private static String snake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
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

    /**
     * 양수 정수리터럴 또는 음수 정수리터럴(CtUnaryOperator(MINUS, CtLiteral))을 longValue로 반환.
     * Double/Float 리터럴은 제외(literalLong 위임). 정수가 아니면 empty.
     */
    private static OptionalLong literalLongWithNeg(CtExpression<?> expr) {
        // 양수 리터럴: 기존 literalLong 재사용
        OptionalLong direct = literalLong(expr);
        if (direct.isPresent()) {
            return direct;
        }
        // 음수 리터럴: CtUnaryOperator(MINUS) → 내부 CtLiteral 언랩 후 부호 반영
        if (expr instanceof CtUnaryOperator<?> uo && uo.getKind() == UnaryOperatorKind.NEG) {
            OptionalLong inner = literalLong(uo.getOperand());
            if (inner.isPresent()) {
                return OptionalLong.of(-inner.getAsLong());
            }
        }
        return OptionalLong.empty();
    }

    /**
     * 표현식이 가드 메서드의 파라미터를 직접 참조하는 CtVariableRead이면 파라미터명, 아니면 null.
     * 중간 계산(CtBinaryOperator 등)을 경유하면 CtVariableRead가 아니므로 null을 반환한다.
     */
    private static String directParamName(CtExpression<?> expr) {
        if (expr instanceof CtVariableRead<?> vr && vr.getVariable().getDeclaration() instanceof CtParameter<?> p) {
            return p.getSimpleName();
        }
        return null;
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

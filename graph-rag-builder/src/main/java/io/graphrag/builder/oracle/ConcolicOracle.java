package io.graphrag.builder.oracle;

import com.microsoft.z3.ArithExpr;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import com.microsoft.z3.IntExpr;
import com.microsoft.z3.Model;
import com.microsoft.z3.Optimize;
import com.microsoft.z3.RatNum;
import com.microsoft.z3.RealExpr;
import com.microsoft.z3.Solver;
import com.microsoft.z3.Status;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * ASM 바이트코드 심볼릭 스캔 + Z3로 분기를 여는 입력값을 도출하는 InputOracle 구현.
 * 입력 필드(파라미터/접근자)에서 파생된 선형식(Σ coeff·field + const, 계수는 {@link Rational})을 추적하고,
 * 각 비교 분기의 경계 등식을 Z3로 풀어 경계값/튜플을 후보로 낸다. 소스에 리터럴로 없는 값(예: amount*3==21 → 7)도
 * 도출한다 — 정적 리터럴 추출이 못 하는 부분.
 *
 * <p>정수(domain INT) 비교는 Z3 IntExpr로 정확히 풀고, float/double(domain REAL) 비교는 Z3 Real(mkReal,
 * 분수 정확)로 푼다(작업 #4). 한 비교에 INT-origin과 REAL-origin 항이 섞이면(domain MIXED) 보수적으로 bail
 * — 정수 필드에 소수가 배정돼 역직렬화가 깨지는 것을 막는다. 1차 범위: intra-method, 선형, 단일/2-필드.
 * 비선형(변수×변수)/3+필드/long-cmp 비교 등은 보수적으로 skip.
 */
public final class ConcolicOracle implements InputOracle {

    private static final Logger log = LoggerFactory.getLogger(ConcolicOracle.class);

    @Override
    public String name() {
        return "concolic-asm-z3";
    }

    @Override
    public InputCandidates analyze(SutCode sut) {
        if (sut.bootJar() == null) {
            return InputCandidates.empty();
        }
        InputCandidates acc = InputCandidates.empty();
        try (ZipFile zip = new ZipFile(sut.bootJar().toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().startsWith("BOOT-INF/classes/") || !entry.getName().endsWith(".class")) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    acc = acc.merge(analyzeClassBytes(in.readAllBytes()));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("concolic: failed to read " + sut.bootJar(), e);
        }
        return acc;
    }

    /** 단일 클래스 바이트코드 분석 (테스트·내부용). */
    public InputCandidates analyzeClassBytes(byte[] classBytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, ClassReader.SKIP_FRAMES);
        Map<String, Set<Long>> numeric = new TreeMap<>();
        Map<String, Set<String>> strings = new TreeMap<>();
        List<Map<String, Long>> tuples = new ArrayList<>();
        Map<String, Set<Double>> reals = new TreeMap<>();
        List<Map<String, Double>> realTuples = new ArrayList<>();
        try (Context ctx = new Context()) {
            for (MethodNode m : cn.methods) {
                for (Comparison c : scanMethod(m)) {
                    if (c.terms().size() == 2) {
                        if (c.real()) {
                            solveTupleReal(ctx, c).ifPresent(realTuples::add);   // float inter-field 튜플
                        } else {
                            solveTuple(ctx, c).ifPresent(tuples::add);           // 정수 inter-field 튜플
                        }
                        continue;
                    }
                    // 단일필드 (terms.size()==1)
                    String field = c.terms().keySet().iterator().next();
                    Rational coeff = c.terms().values().iterator().next();
                    if (c.real()) {
                        solveBoundaryReal(coeff, c.constant()).ifPresent(b -> addRealBoundary(reals, field, b));
                        continue;
                    }
                    if (coeff.den() != 1 || c.constant().den() != 1) {
                        continue;   // 정수 비교인데 분수 계수 → 방어적 skip
                    }
                    solveBoundary(ctx, coeff.num(), c.constant().num()).ifPresent(b -> {
                        if (field.startsWith("len:")) {
                            // 문자열 길이 제약 → 해당 길이의 문자열 후보 (경계 ±1)
                            String real = field.substring("len:".length());
                            Set<String> sv = strings.computeIfAbsent(real, k -> new TreeSet<>());
                            for (long len : new long[]{b - 1, b, b + 1}) {
                                if (len >= 0 && len <= 256) {
                                    sv.add("x".repeat((int) len));
                                }
                            }
                        } else {
                            Set<Long> vals = numeric.computeIfAbsent(field, k -> new TreeSet<>());
                            vals.add(b - 1);
                            vals.add(b);
                            vals.add(b + 1);
                        }
                    });
                }
            }
        }
        return new InputCandidates(numeric, strings, tuples, reals, realTuples);
    }

    /** 단일 real 경계 B 부근 {B-ε, B, B+ε} 후보. ε=max(ulp(B), 1e-3). 비유한 제외. */
    private static void addRealBoundary(Map<String, Set<Double>> reals, String field, double b) {
        if (!Double.isFinite(b)) {
            return;
        }
        Set<Double> vals = reals.computeIfAbsent(field, k -> new TreeSet<>());
        double eps = Math.max(Math.ulp(b), 1e-3);
        for (double v : new double[]{b - eps, b, b + eps}) {
            if (Double.isFinite(v)) {
                vals.add(v);
            }
        }
    }

    /** 비교 분기의 선형식 Σ(coeff·field)+constant 와 0에 대한 관계. terms 1개=단일필드, 2개=inter-field. real=REAL domain. */
    private record Comparison(Map<String, Rational> terms, Rational constant, Rel rel, boolean real) {
    }

    /** 비교 opcode가 의미하는 0과의 관계(Σ+const <rel> 0). */
    private enum Rel { EQ, NE, LT, LE, GT, GE }

    /** 선형식 항의 값-도메인 출처. INT/REAL이 한 식에 섞이면 MIXED(보수적 bail). NONE=순수 상수. */
    private enum Domain {
        NONE, INT, REAL, MIXED;

        static Domain combine(Domain a, Domain b) {
            if (a == NONE) {
                return b;
            }
            if (b == NONE) {
                return a;
            }
            return a == b ? a : MIXED;
        }
    }

    private static Rel relOf(int opcode) {
        return switch (opcode) {
            case Opcodes.IFEQ, Opcodes.IF_ICMPEQ -> Rel.EQ;
            case Opcodes.IFNE, Opcodes.IF_ICMPNE -> Rel.NE;
            case Opcodes.IFLT, Opcodes.IF_ICMPLT -> Rel.LT;
            case Opcodes.IFLE, Opcodes.IF_ICMPLE -> Rel.LE;
            case Opcodes.IFGT, Opcodes.IF_ICMPGT -> Rel.GT;
            case Opcodes.IFGE, Opcodes.IF_ICMPGE -> Rel.GE;
            default -> Rel.EQ;
        };
    }

    private List<Comparison> scanMethod(MethodNode m) {
        List<Comparison> out = new ArrayList<>();
        Set<LabelNode> jumpTargets = new HashSet<>();
        for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof JumpInsnNode j) {
                jumpTargets.add(j.label);
            }
        }
        Map<Integer, String> localNames = new HashMap<>();
        if (m.localVariables != null) {
            for (LocalVariableNode lv : m.localVariables) {
                localNames.putIfAbsent(lv.index, lv.name);
            }
        }
        Map<Integer, Sym> locals = seedParams(m, localNames);
        Deque<Sym> stack = new ArrayDeque<>();
        try {
            for (AbstractInsnNode insn : m.instructions) {
                step(insn, stack, locals, jumpTargets, out);
            }
        } catch (RuntimeException e) {
            // 스택 모델이 못 따라가는 메서드 → 그때까지 모은 비교만 사용 (보수적)
            log.debug("concolic: scan aborted in {}{}: {}", m.name, m.desc, e.toString());
        }
        return out;
    }

    private Map<Integer, Sym> seedParams(MethodNode m, Map<Integer, String> localNames) {
        Map<Integer, Sym> locals = new HashMap<>();
        boolean isStatic = (m.access & Opcodes.ACC_STATIC) != 0;
        int slot = isStatic ? 0 : 1;
        for (Type arg : Type.getArgumentTypes(m.desc)) {
            String name = localNames.getOrDefault(slot, "arg" + slot);
            switch (arg.getSort()) {
                case Type.INT, Type.SHORT, Type.BYTE, Type.CHAR, Type.BOOLEAN ->
                        locals.put(slot, Sym.field(name, 1, Domain.INT));
                case Type.LONG -> locals.put(slot, Sym.field(name, 2, Domain.INT));
                case Type.FLOAT -> locals.put(slot, Sym.field(name, 1, Domain.REAL));
                case Type.DOUBLE -> locals.put(slot, Sym.field(name, 2, Domain.REAL));
                case Type.OBJECT -> {
                    if (isBoxedNumeric(arg.getDescriptor())) {
                        locals.put(slot, Sym.field(name, 1, Domain.INT));
                    } else if (isBoxedReal(arg.getDescriptor())) {
                        locals.put(slot, Sym.field(name, 1, Domain.REAL));
                    } else if (arg.getDescriptor().equals("Ljava/lang/String;")) {
                        locals.put(slot, Sym.stringValue(name));
                    } else {
                        locals.put(slot, Sym.objectParam(1));
                    }
                }
                default -> locals.put(slot, Sym.top(arg.getSize()));
            }
            slot += arg.getSize();
        }
        return locals;
    }

    private void step(AbstractInsnNode insn, Deque<Sym> stack, Map<Integer, Sym> locals,
                      Set<LabelNode> jumpTargets, List<Comparison> out) {
        if (insn instanceof LabelNode ln) {
            if (jumpTargets.contains(ln)) {
                stack.clear();   // merge point — 스택 보수적 초기화
            }
            return;
        }
        int op = insn.getOpcode();
        if (op < 0) {
            return;   // line/frame nodes
        }
        switch (op) {
            case Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2,
                 Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5 ->
                    stack.push(Sym.constant(op - Opcodes.ICONST_0, 1));
            case Opcodes.LCONST_0, Opcodes.LCONST_1 ->
                    stack.push(Sym.constant(op - Opcodes.LCONST_0, 2));
            case Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2 ->
                    stack.push(Sym.constant(Rational.of((double) (op - Opcodes.FCONST_0)), 1));
            case Opcodes.DCONST_0, Opcodes.DCONST_1 ->
                    stack.push(Sym.constant(Rational.of((double) (op - Opcodes.DCONST_0)), 2));
            case Opcodes.BIPUSH, Opcodes.SIPUSH ->
                    stack.push(Sym.constant(((IntInsnNode) insn).operand, 1));
            case Opcodes.LDC -> pushLdc(((LdcInsnNode) insn).cst, stack);
            case Opcodes.ILOAD, Opcodes.FLOAD, Opcodes.ALOAD ->
                    stack.push(localOr(locals, ((VarInsnNode) insn).var, 1));
            case Opcodes.LLOAD, Opcodes.DLOAD ->
                    stack.push(localOr(locals, ((VarInsnNode) insn).var, 2));
            case Opcodes.ISTORE, Opcodes.ASTORE, Opcodes.FSTORE,
                 Opcodes.LSTORE, Opcodes.DSTORE -> locals.put(((VarInsnNode) insn).var, pop(stack));
            case Opcodes.IADD, Opcodes.LADD, Opcodes.FADD, Opcodes.DADD ->
                    { Sym b = pop(stack); stack.push(pop(stack).add(b)); }
            case Opcodes.ISUB, Opcodes.LSUB, Opcodes.FSUB, Opcodes.DSUB ->
                    { Sym b = pop(stack); stack.push(pop(stack).sub(b)); }
            case Opcodes.IMUL, Opcodes.LMUL, Opcodes.FMUL, Opcodes.DMUL ->
                    { Sym b = pop(stack); stack.push(pop(stack).mul(b)); }
            case Opcodes.INEG, Opcodes.LNEG, Opcodes.FNEG, Opcodes.DNEG -> stack.push(pop(stack).neg());
            case Opcodes.IINC -> {
                IincInsnNode inc = (IincInsnNode) insn;
                locals.put(inc.var, localOr(locals, inc.var, 1).add(Sym.constant(inc.incr, 1)));
            }
            // 변환 opcode: 값 크기(스택 슬롯)만 바꾸고 도메인 출처는 보존(I2F는 표현만 real, origin은 INT 유지).
            case Opcodes.L2I, Opcodes.F2I, Opcodes.D2I, Opcodes.I2S, Opcodes.I2B, Opcodes.I2C,
                 Opcodes.I2F, Opcodes.L2F, Opcodes.D2F -> stack.push(pop(stack).withSize(1));
            case Opcodes.I2L, Opcodes.F2L, Opcodes.D2L, Opcodes.I2D, Opcodes.L2D, Opcodes.F2D ->
                    stack.push(pop(stack).withSize(2));
            case Opcodes.DUP -> { Sym v = pop(stack); stack.push(v); stack.push(v); }
            case Opcodes.POP -> pop(stack);
            case Opcodes.POP2 -> { Sym v = pop(stack); if (v.size == 1) pop(stack); }
            case Opcodes.GOTO, Opcodes.NOP, Opcodes.RETURN -> { }
            case Opcodes.IRETURN, Opcodes.ARETURN, Opcodes.ATHROW -> stack.clear();
            case Opcodes.LRETURN, Opcodes.DRETURN, Opcodes.FRETURN -> stack.clear();
            // 차이를 push 후 IF* 가 0과의 rel을 판정(LCMP/FCMP/DCMP 공통). 차 Sym이 도메인을 운반.
            case Opcodes.LCMP, Opcodes.FCMPL, Opcodes.FCMPG, Opcodes.DCMPL, Opcodes.DCMPG ->
                    { Sym b = pop(stack); stack.push(pop(stack).sub(b).withSize(1)); }
            case Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE ->
                    record(out, pop(stack), relOf(op));
            case Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE,
                 Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE -> {
                Sym b = pop(stack);
                record(out, pop(stack).sub(b), relOf(op));
            }
            case Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE -> { pop(stack); pop(stack); }
            case Opcodes.IFNULL, Opcodes.IFNONNULL, Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH -> pop(stack);
            case Opcodes.GETFIELD -> { pop(stack); stack.push(Sym.top(1)); }
            case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE, Opcodes.INVOKESTATIC,
                 Opcodes.INVOKESPECIAL -> invoke((MethodInsnNode) insn, stack, op);
            case Opcodes.INVOKEDYNAMIC -> {
                // 문자열 concat(makeConcatWithConstants) 등: 인자 desc만큼 pop, 반환 push
                InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) insn;
                for (int i = 0; i < Type.getArgumentTypes(indy.desc).length; i++) {
                    pop(stack);
                }
                Type ret = Type.getReturnType(indy.desc);
                if (ret.getSort() != Type.VOID) {
                    pushReturn(stack, ret);
                }
            }
            default -> applyGenericStack(insn, op, stack);
        }
    }

    private static void pushLdc(Object cst, Deque<Sym> stack) {
        if (cst instanceof Integer i) {
            stack.push(Sym.constant(i, 1));
        } else if (cst instanceof Long l) {
            stack.push(Sym.constant(l, 2));
        } else if (cst instanceof Float f) {
            stack.push(constantOrTop(f, 1));
        } else if (cst instanceof Double d) {
            stack.push(constantOrTop(d, 2));
        } else {
            stack.push(Sym.top(1));   // String/Class/MethodHandle 등
        }
    }

    /** float/double 상수 → Rational. 비유한(NaN/Inf)·overflow면 top(보수적). */
    private static Sym constantOrTop(double v, int size) {
        try {
            return Sym.constant(Rational.of(v), size);
        } catch (ArithmeticException e) {
            return Sym.top(size);
        }
    }

    private void invoke(MethodInsnNode mi, Deque<Sym> stack, int op) {
        boolean isStatic = op == Opcodes.INVOKESTATIC;
        Type[] args = Type.getArgumentTypes(mi.desc);
        // 언박싱 intValue()/longValue()/floatValue()/doubleValue() 등 → 패스스루(도메인 보존)
        if (!isStatic && args.length == 0 && isUnbox(mi)) {
            Sym recv = pop(stack);
            stack.push(recv.withSize(mi.name.equals("longValue") || mi.name.equals("doubleValue") ? 2 : 1));
            return;
        }
        // 박싱 Integer.valueOf(int)/Long.valueOf(long) → 패스스루
        if (isStatic && mi.name.equals("valueOf") && args.length == 1 && isBoxedNumeric(mi.owner.startsWith("java/lang") ? "L" + mi.owner + ";" : "")) {
            stack.push(pop(stack));
            return;
        }
        Type ret = Type.getReturnType(mi.desc);
        // String.length() (문자열 입력값 수신자) → len:field 정수 변수
        if (!isStatic && args.length == 0 && mi.name.equals("length") && ret.getSort() == Type.INT) {
            Sym recv = pop(stack);
            stack.push(recv.stringField != null ? Sym.field("len:" + recv.stringField, 1, Domain.INT) : Sym.top(1));
            return;
        }
        // 0-arg 접근자(객체 파라미터 수신자, 숫자/박싱숫자 반환) → field 변수(도메인=반환 타입)
        if (!isStatic && args.length == 0 && isNumericReturn(ret)) {
            Sym recv = pop(stack);
            if (recv.objectParam) {
                int size = ret.getSort() == Type.LONG || ret.getSort() == Type.DOUBLE ? 2 : 1;
                stack.push(Sym.field(property(mi.name), size, isRealReturn(ret) ? Domain.REAL : Domain.INT));
                return;
            }
            pushReturn(stack, ret);
            return;
        }
        // 0-arg 접근자, String 반환, 객체 파라미터 수신자 → 문자열 입력값
        if (!isStatic && args.length == 0 && ret.getDescriptor().equals("Ljava/lang/String;")) {
            Sym recv = pop(stack);
            stack.push(recv.objectParam ? Sym.stringValue(property(mi.name)) : Sym.top(1));
            return;
        }
        // 일반 호출: 인자 pop, 수신자 pop(가상), 반환 push
        for (int i = 0; i < args.length; i++) {
            pop(stack);
        }
        if (!isStatic) {
            pop(stack);
        }
        if (ret.getSort() != Type.VOID) {
            pushReturn(stack, ret);
        }
    }

    private void applyGenericStack(AbstractInsnNode insn, int op, Deque<Sym> stack) {
        // 미모델 명령: 스택 균형만 보수적으로 맞춘다 (대부분 단일 pop/push)
        switch (op) {
            case Opcodes.DUP_X1, Opcodes.DUP_X2, Opcodes.DUP2, Opcodes.DUP2_X1, Opcodes.DUP2_X2,
                 Opcodes.SWAP -> { /* 드묾 — 무시(보수적) */ }
            case Opcodes.IDIV, Opcodes.IREM, Opcodes.ISHL, Opcodes.ISHR, Opcodes.IUSHR,
                 Opcodes.IAND, Opcodes.IOR, Opcodes.IXOR, Opcodes.LDIV, Opcodes.LREM,
                 Opcodes.LAND, Opcodes.LOR, Opcodes.LXOR, Opcodes.FDIV, Opcodes.DDIV,
                 Opcodes.FREM, Opcodes.DREM -> {
                pop(stack); pop(stack); stack.push(Sym.top(1));
            }
            case Opcodes.GETSTATIC -> stack.push(Sym.top(1));
            case Opcodes.NEW -> stack.push(Sym.top(1));
            case Opcodes.CHECKCAST, Opcodes.INSTANCEOF -> { pop(stack); stack.push(Sym.top(1)); }
            default ->
                // 미처리 opcode — 스택 효과 불명. 조용한 손상 대신 깔끔히 bail(앞서 모은 비교는 보존).
                throw new IllegalStateException("unhandled opcode " + op);
        }
    }

    private void record(List<Comparison> out, Sym expr, Rel rel) {
        // 단일/2-필드 선형식만 (3+ 필드는 Sym.add가 이미 top으로 bail). MIXED(int↔float 혼합)는 bail.
        if (expr.isLinear() && !expr.terms().isEmpty() && expr.terms().size() <= 2
                && expr.domain != Domain.MIXED) {
            out.add(new Comparison(expr.terms(), expr.constant(), rel, expr.domain == Domain.REAL));
        }
    }

    private Optional<Long> solveBoundary(Context ctx, long coeff, long constant) {
        // coeff*field + constant == 0 의 정수해 → 경계값
        Solver s = ctx.mkSolver();
        IntExpr field = ctx.mkIntConst("f");
        IntExpr expr = (IntExpr) ctx.mkAdd(ctx.mkMul(ctx.mkInt(coeff), field), ctx.mkInt(constant));
        s.add(ctx.mkEq(expr, ctx.mkInt(0)));
        if (s.check() != Status.SATISFIABLE) {
            return Optional.empty();
        }
        var v = s.getModel().evaluate(field, false);
        try {
            return Optional.of(Long.parseLong(v.toString()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** 단일 real 필드 경계: coeff·f + constant == 0 → f = -constant/coeff (Rational 정확, 비유한/overflow면 empty). */
    private Optional<Double> solveBoundaryReal(Rational coeff, Rational constant) {
        if (coeff.isZero()) {
            return Optional.empty();
        }
        try {
            Rational f = constant.neg().mul(new Rational(coeff.den(), coeff.num()));
            double b = f.toDouble();
            return Double.isFinite(b) ? Optional.of(b) : Optional.empty();
        } catch (ArithmeticException e) {
            return Optional.empty();
        }
    }

    /**
     * 2-필드 선형 (in)equality Σ(coeff·var)+const &lt;rel&gt; 0 의 정수 해 튜플(field→value).
     * EQ/NE는 경계 ==0(동시충족 튜플), 순서관계는 그대로. 각 var&gt;=1 + 합 최소화 → 작은·in-range 값(결정적).
     * SAT 아니거나 len: 항이 섞이거나 분수 계수면 빈 Optional(bail).
     */
    private Optional<Map<String, Long>> solveTuple(Context ctx, Comparison c) {
        if (c.terms().keySet().stream().anyMatch(f -> f.startsWith("len:"))) {
            return Optional.empty();   // 문자열 길이는 단일필드 경로 전용
        }
        if (c.constant().den() != 1 || c.terms().values().stream().anyMatch(r -> r.den() != 1)) {
            return Optional.empty();   // 정수 튜플인데 분수 계수 → bail
        }
        Optimize opt = ctx.mkOptimize();
        com.microsoft.z3.Params params = ctx.mkParams();
        params.add("timeout", 2000);   // 2s — 큰 bootJar에서 Optimize가 늘어지지 않게(plan §2D)
        opt.setParameters(params);
        Map<String, IntExpr> vars = new TreeMap<>();
        ArithExpr sum = ctx.mkInt(c.constant().num());
        ArithExpr objective = ctx.mkInt(0);
        for (var e : c.terms().entrySet()) {
            IntExpr v = ctx.mkIntConst(e.getKey());
            vars.put(e.getKey(), v);
            sum = ctx.mkAdd(sum, ctx.mkMul(ctx.mkInt(e.getValue().num()), v));
            opt.Add(ctx.mkGe(v, ctx.mkInt(1)));        // soft 하한: degenerate all-zero 방지
            objective = ctx.mkAdd(objective, v);
        }
        IntExpr zero = ctx.mkInt(0);
        BoolExpr rel = relExpr(ctx, c.rel(), sum, zero);
        opt.Add(rel);
        opt.MkMinimize(objective);                     // 합 최소화 → 작은 값(예: nights=1) 선호
        if (opt.Check() != Status.SATISFIABLE) {
            return Optional.empty();
        }
        Model model = opt.getModel();
        Map<String, Long> tuple = new TreeMap<>();
        for (var e : vars.entrySet()) {
            try {
                tuple.put(e.getKey(), Long.parseLong(model.evaluate(e.getValue(), false).toString()));
            } catch (NumberFormatException ex) {
                return Optional.empty();
            }
        }
        return Optional.of(tuple);
    }

    /**
     * 2-필드 선형 real (in)equality 의 해 튜플(field→double). 계수는 {@link Context#mkReal(long, long)}로 분수
     * 정확하게 모델링. 각 var&gt;=δ(1e-6) + 합 최소화 → 작은·in-range·결정적 해. 모델은 RatNum으로 받아 finite
     * 만 채택(NaN/Inf 거부). SAT 아니면 빈 Optional(bail).
     */
    private Optional<Map<String, Double>> solveTupleReal(Context ctx, Comparison c) {
        Optimize opt = ctx.mkOptimize();
        com.microsoft.z3.Params params = ctx.mkParams();
        params.add("timeout", 2000);
        opt.setParameters(params);
        RealExpr delta = (RealExpr) ctx.mkReal(1, 1_000_000);   // δ=1e-6
        Map<String, RealExpr> vars = new TreeMap<>();
        ArithExpr sum = realOf(ctx, c.constant());
        ArithExpr objective = ctx.mkReal(0);
        for (var e : c.terms().entrySet()) {
            RealExpr v = ctx.mkRealConst(e.getKey());
            vars.put(e.getKey(), v);
            sum = ctx.mkAdd(sum, ctx.mkMul(realOf(ctx, e.getValue()), v));
            opt.Add(ctx.mkGe(v, delta));               // var ≥ δ: degenerate all-zero 방지
            objective = ctx.mkAdd(objective, v);
        }
        // 부등식 해를 경계에 딱 붙이지 않고 half-plane 안쪽으로 margin(1e-3)만큼 민다 — 합 최소화가
        // 경계(==0)에 수렴하면 SUT가 float로 재계산할 때 반올림(~ulp)으로 분기가 닫히는 band-edge 취약성을
        // 막는다(1e-3 ≫ ulp(100f)≈7.6e-6, band 폭 1.0 ≫ margin). 등식(EQ/NE)은 정확해만 존재 → margin 불가.
        RealExpr margin = (RealExpr) ctx.mkReal(1, 1000);
        RealExpr negMargin = (RealExpr) ctx.mkReal(-1, 1000);
        RealExpr zero = (RealExpr) ctx.mkReal(0);
        BoolExpr rel = switch (c.rel()) {
            case EQ, NE -> ctx.mkEq(sum, zero);
            case GT, GE -> ctx.mkGe(sum, margin);
            case LT, LE -> ctx.mkLe(sum, negMargin);
        };
        opt.Add(rel);
        opt.MkMinimize(objective);
        if (opt.Check() != Status.SATISFIABLE) {
            return Optional.empty();
        }
        Model model = opt.getModel();
        Map<String, Double> tuple = new TreeMap<>();
        for (var e : vars.entrySet()) {
            Optional<Double> d = ratToDouble(model.evaluate(e.getValue(), false));
            if (d.isEmpty()) {
                return Optional.empty();
            }
            tuple.put(e.getKey(), d.get());
        }
        return Optional.of(tuple);
    }

    private static BoolExpr relExpr(Context ctx, Rel rel, ArithExpr sum, ArithExpr zero) {
        return switch (rel) {
            case EQ, NE -> ctx.mkEq(sum, zero);        // EQ-family → 경계 동시충족 튜플
            case LT -> ctx.mkLt(sum, zero);
            case LE -> ctx.mkLe(sum, zero);
            case GT -> ctx.mkGt(sum, zero);
            case GE -> ctx.mkGe(sum, zero);
        };
    }

    private static ArithExpr realOf(Context ctx, Rational r) {
        // mkReal(int,int)만 있고 계수는 long 범위일 수 있어 "num/den" 문자열 numeral로 정밀도 보존
        // (den은 Rational이 항상 >0로 정규화). Gemini/GPT 리뷰의 mkReal 분수 numeral 요구 충족.
        return ctx.mkReal(r.num() + "/" + r.den());
    }

    private static Optional<Double> ratToDouble(Expr<?> e) {
        if (e instanceof RatNum rn) {
            BigDecimal n = new BigDecimal(rn.getBigIntNumerator());
            BigDecimal d = new BigDecimal(rn.getBigIntDenominator());
            if (d.signum() == 0) {
                return Optional.empty();
            }
            // DECIMAL128(34 sig-digits): double round-trip(최대 17 sig-digits)을 보장 — DECIMAL64(16)는
            // 마지막 자리에서 최근접 double을 놓칠 수 있어, exact-rational 불변식(D1/D4)을 약화시킨다(리뷰).
            double val = n.divide(d, MathContext.DECIMAL128).doubleValue();
            return Double.isFinite(val) ? Optional.of(val) : Optional.empty();
        }
        return Optional.empty();
    }

    private static Sym localOr(Map<Integer, Sym> locals, int idx, int size) {
        return locals.getOrDefault(idx, Sym.top(size));
    }

    private static Sym pop(Deque<Sym> stack) {
        if (stack.isEmpty()) {
            throw new IllegalStateException("stack underflow");
        }
        return stack.pop();
    }

    private static void pushReturn(Deque<Sym> stack, Type ret) {
        stack.push(Sym.top(ret.getSize()));
    }

    private static boolean isUnbox(MethodInsnNode mi) {
        return mi.owner.startsWith("java/lang/")
                && Set.of("intValue", "longValue", "shortValue", "byteValue",
                        "floatValue", "doubleValue").contains(mi.name);
    }

    private static boolean isBoxedNumeric(String desc) {
        return desc.equals("Ljava/lang/Integer;") || desc.equals("Ljava/lang/Long;")
                || desc.equals("Ljava/lang/Short;") || desc.equals("Ljava/lang/Byte;");
    }

    private static boolean isBoxedReal(String desc) {
        return desc.equals("Ljava/lang/Float;") || desc.equals("Ljava/lang/Double;");
    }

    private static boolean isNumericReturn(Type ret) {
        return switch (ret.getSort()) {
            case Type.INT, Type.SHORT, Type.BYTE, Type.CHAR, Type.LONG, Type.FLOAT, Type.DOUBLE -> true;
            case Type.OBJECT -> isBoxedNumeric(ret.getDescriptor()) || isBoxedReal(ret.getDescriptor());
            default -> false;
        };
    }

    private static boolean isRealReturn(Type ret) {
        return switch (ret.getSort()) {
            case Type.FLOAT, Type.DOUBLE -> true;
            case Type.OBJECT -> isBoxedReal(ret.getDescriptor());
            default -> false;
        };
    }

    private static String property(String accessor) {
        if (accessor.startsWith("get") && accessor.length() > 3) {
            return Character.toLowerCase(accessor.charAt(3)) + accessor.substring(4);
        }
        if (accessor.startsWith("is") && accessor.length() > 2) {
            return Character.toLowerCase(accessor.charAt(2)) + accessor.substring(3);
        }
        return accessor;   // record accessor
    }

    /**
     * 심볼릭 값: 선형식 Σ(coeff·field) + constant (계수/상수는 Rational). terms는 field→coeff(최대 2개; 3개째 → top).
     * terms 비면 순수 상수(domain NONE). domain은 항의 값-도메인 출처(INT/REAL/MIXED). top/objectParam/stringField는
     * 비선형. stringField는 String 입력값(길이 제약 수신자).
     */
    private record Sym(boolean top, boolean objectParam, String stringField,
                       Map<String, Rational> terms, Rational constant, int size, Domain domain) {

        static Sym top(int size) {
            return new Sym(true, false, null, Map.of(), Rational.ZERO, size, Domain.NONE);
        }

        static Sym objectParam(int size) {
            return new Sym(false, true, null, Map.of(), Rational.ZERO, size, Domain.NONE);
        }

        static Sym stringValue(String name) {
            return new Sym(false, false, name, Map.of(), Rational.ZERO, 1, Domain.NONE);
        }

        static Sym constant(Rational v, int size) {
            return new Sym(false, false, null, Map.of(), v, size, Domain.NONE);
        }

        static Sym constant(long v, int size) {
            return constant(Rational.of(v), size);
        }

        static Sym field(String name, int size, Domain domain) {
            return new Sym(false, false, null, Map.of(name, Rational.ONE), Rational.ZERO, size, domain);
        }

        boolean isLinear() {
            return !top && !objectParam && stringField == null;
        }

        Sym withSize(int newSize) {
            return new Sym(top, objectParam, stringField, terms, constant, newSize, domain);
        }

        Sym add(Sym o) {
            if (!isLinear() || !o.isLinear()) {
                return Sym.top(size);
            }
            try {
                TreeMap<String, Rational> merged = new TreeMap<>(terms);
                o.terms.forEach((k, v) -> merged.merge(k, v, Rational::add));
                merged.values().removeIf(Rational::isZero);   // 상쇄된 항 제거
                if (merged.size() > 2) {
                    return Sym.top(size);   // cap 2 fields — 3개째 등장 시 bail
                }
                Domain d = merged.isEmpty() ? Domain.NONE : Domain.combine(domain, o.domain);
                return new Sym(false, false, null, Map.copyOf(merged), constant.add(o.constant), size, d);
            } catch (ArithmeticException e) {
                return Sym.top(size);   // overflow → 보수적 bail
            }
        }

        Sym sub(Sym o) {
            return add(o.neg());
        }

        Sym neg() {
            if (!isLinear()) {
                return Sym.top(size);
            }
            try {
                TreeMap<String, Rational> n = new TreeMap<>();
                terms.forEach((k, v) -> n.put(k, v.neg()));
                return new Sym(false, false, null, Map.copyOf(n), constant.neg(), size, domain);
            } catch (ArithmeticException e) {
                return Sym.top(size);
            }
        }

        Sym mul(Sym o) {
            if (!isLinear() || !o.isLinear()) {
                return Sym.top(size);
            }
            try {
                Rational newConst = constant.mul(o.constant);
                if (terms.isEmpty()) {     // const · linear
                    return scale(o.terms, constant, newConst, o.size, o.domain);
                }
                if (o.terms.isEmpty()) {   // linear · const
                    return scale(terms, o.constant, newConst, size, domain);
                }
                return Sym.top(size);      // field·field 비선형
            } catch (ArithmeticException e) {
                return Sym.top(size);
            }
        }

        private static Sym scale(Map<String, Rational> base, Rational factor, Rational newConst,
                                 int size, Domain domain) {
            TreeMap<String, Rational> r = new TreeMap<>();
            base.forEach((k, v) -> {
                Rational scaled = v.mul(factor);
                if (!scaled.isZero()) {
                    r.put(k, scaled);
                }
            });
            Domain d = r.isEmpty() ? Domain.NONE : domain;
            return new Sym(false, false, null, Map.copyOf(r), newConst, size, d);
        }
    }
}

package io.graphrag.builder.oracle;

import com.microsoft.z3.Context;
import com.microsoft.z3.IntExpr;
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
import java.nio.file.Path;
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
 * 입력 필드(파라미터/접근자)에서 파생된 정수 선형식(coeff*field+const)을 추적하고, 각 비교 분기의
 * 경계 등식 {@code coeff*field+const==0}을 Z3로 풀어 경계값 B의 {B-1,B,B+1}을 후보로 낸다.
 * 소스에 리터럴로 없는 값(예: amount*3==21 → 7)도 도출한다 — 정적 리터럴 추출이 못 하는 부분.
 *
 * <p>1차 범위: intra-method, 정수 선형, 단일 필드. 비선형/다변수/long-cmp/문자열은 보수적으로 skip
 * (false candidate는 mutableFields 투영에서 무시되므로 안전).
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
        try (Context ctx = new Context()) {
            for (MethodNode m : cn.methods) {
                for (Comparison c : scanMethod(m)) {
                    solveBoundary(ctx, c).ifPresent(b -> {
                        Set<Long> vals = numeric.computeIfAbsent(c.field, k -> new TreeSet<>());
                        vals.add(b - 1);
                        vals.add(b);
                        vals.add(b + 1);
                    });
                }
            }
        }
        return new InputCandidates(numeric, Map.of());
    }

    /** field 비교의 선형식 coeff*field+constant (==0의 경계를 풀 대상). */
    private record Comparison(String field, long coeff, long constant) {
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
                        locals.put(slot, Sym.field(name, 1));
                case Type.LONG -> locals.put(slot, Sym.field(name, 2));
                case Type.OBJECT -> {
                    if (isBoxedNumeric(arg.getDescriptor())) {
                        locals.put(slot, Sym.field(name, 1));
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
            case Opcodes.BIPUSH, Opcodes.SIPUSH ->
                    stack.push(Sym.constant(((IntInsnNode) insn).operand, 1));
            case Opcodes.LDC -> {
                Object cst = ((LdcInsnNode) insn).cst;
                if (cst instanceof Integer i) {
                    stack.push(Sym.constant(i, 1));
                } else if (cst instanceof Long l) {
                    stack.push(Sym.constant(l, 2));
                } else {
                    stack.push(Sym.top(cst instanceof Double || cst instanceof Long ? 2 : 1));
                }
            }
            case Opcodes.ILOAD -> stack.push(localOr(locals, ((VarInsnNode) insn).var, 1));
            case Opcodes.LLOAD -> stack.push(localOr(locals, ((VarInsnNode) insn).var, 2));
            case Opcodes.ALOAD -> stack.push(localOr(locals, ((VarInsnNode) insn).var, 1));
            case Opcodes.ISTORE, Opcodes.ASTORE -> locals.put(((VarInsnNode) insn).var, pop(stack));
            case Opcodes.LSTORE -> locals.put(((VarInsnNode) insn).var, pop(stack));
            case Opcodes.IADD, Opcodes.LADD -> { Sym b = pop(stack); stack.push(pop(stack).add(b)); }
            case Opcodes.ISUB, Opcodes.LSUB -> { Sym b = pop(stack); stack.push(pop(stack).sub(b)); }
            case Opcodes.IMUL, Opcodes.LMUL -> { Sym b = pop(stack); stack.push(pop(stack).mul(b)); }
            case Opcodes.INEG, Opcodes.LNEG -> stack.push(pop(stack).neg());
            case Opcodes.IINC -> {
                IincInsnNode inc = (IincInsnNode) insn;
                locals.put(inc.var, localOr(locals, inc.var, 1).add(Sym.constant(inc.incr, 1)));
            }
            case Opcodes.I2L, Opcodes.L2I, Opcodes.I2S, Opcodes.I2B, Opcodes.I2C -> {
                Sym v = pop(stack);
                stack.push(v.withSize(op == Opcodes.I2L ? 2 : 1));
            }
            case Opcodes.DUP -> { Sym v = pop(stack); stack.push(v); stack.push(v); }
            case Opcodes.POP -> pop(stack);
            case Opcodes.POP2 -> { Sym v = pop(stack); if (v.size == 1) pop(stack); }
            case Opcodes.GOTO, Opcodes.NOP, Opcodes.RETURN -> { }
            case Opcodes.IRETURN, Opcodes.ARETURN, Opcodes.ATHROW -> stack.clear();
            case Opcodes.LRETURN, Opcodes.DRETURN, Opcodes.FRETURN -> stack.clear();
            case Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE ->
                    record(out, pop(stack));
            case Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE,
                 Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE -> {
                Sym b = pop(stack);
                record(out, pop(stack).sub(b));
            }
            case Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE -> { pop(stack); pop(stack); }
            case Opcodes.IFNULL, Opcodes.IFNONNULL, Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH -> pop(stack);
            case Opcodes.GETFIELD -> { pop(stack); stack.push(Sym.top(1)); }
            case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE, Opcodes.INVOKESTATIC,
                 Opcodes.INVOKESPECIAL -> invoke((MethodInsnNode) insn, stack, op);
            default -> applyGenericStack(insn, op, stack);
        }
    }

    private void invoke(MethodInsnNode mi, Deque<Sym> stack, int op) {
        boolean isStatic = op == Opcodes.INVOKESTATIC;
        Type[] args = Type.getArgumentTypes(mi.desc);
        // 언박싱 intValue()/longValue() 등 → 패스스루
        if (!isStatic && args.length == 0 && isUnbox(mi)) {
            Sym recv = pop(stack);
            stack.push(recv.withSize(mi.name.equals("longValue") ? 2 : 1));
            return;
        }
        // 박싱 Integer.valueOf(int)/Long.valueOf(long) → 패스스루
        if (isStatic && mi.name.equals("valueOf") && args.length == 1 && isBoxedNumeric(mi.owner.startsWith("java/lang") ? "L" + mi.owner + ";" : "")) {
            stack.push(pop(stack));
            return;
        }
        // 0-arg 접근자(객체 파라미터 수신자, 숫자/박싱숫자 반환) → field 변수
        Type ret = Type.getReturnType(mi.desc);
        if (!isStatic && args.length == 0 && isNumericReturn(ret)) {
            Sym recv = pop(stack);
            if (recv.objectParam) {
                stack.push(Sym.field(property(mi.name), ret.getSort() == Type.LONG ? 2 : 1));
                return;
            }
            pushReturn(stack, ret);
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
                 Opcodes.LAND, Opcodes.LOR, Opcodes.LXOR, Opcodes.LCMP -> {
                pop(stack); pop(stack); stack.push(Sym.top(op == Opcodes.LCMP ? 1 : 1));
            }
            case Opcodes.GETSTATIC -> stack.push(Sym.top(1));
            case Opcodes.NEW -> stack.push(Sym.top(1));
            case Opcodes.CHECKCAST, Opcodes.INSTANCEOF -> { Sym v = pop(stack); stack.push(Sym.top(1)); }
            default -> {
                // 알 수 없는 명령 — 스택 상태 신뢰 불가하므로 비움(이후 비교는 top→skip)
                stack.clear();
            }
        }
    }

    private void record(List<Comparison> out, Sym expr) {
        if (expr.isLinear() && expr.field != null && expr.coeff != 0) {
            out.add(new Comparison(expr.field, expr.coeff, expr.constant));
        }
    }

    private Optional<Long> solveBoundary(Context ctx, Comparison c) {
        // coeff*field + constant == 0 의 정수해 → 경계값
        Solver s = ctx.mkSolver();
        IntExpr field = ctx.mkIntConst("f");
        IntExpr expr = (IntExpr) ctx.mkAdd(ctx.mkMul(ctx.mkInt(c.coeff), field), ctx.mkInt(c.constant));
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
                && Set.of("intValue", "longValue", "shortValue", "byteValue").contains(mi.name);
    }

    private static boolean isBoxedNumeric(String desc) {
        return desc.equals("Ljava/lang/Integer;") || desc.equals("Ljava/lang/Long;")
                || desc.equals("Ljava/lang/Short;") || desc.equals("Ljava/lang/Byte;");
    }

    private static boolean isNumericReturn(Type ret) {
        return switch (ret.getSort()) {
            case Type.INT, Type.SHORT, Type.BYTE, Type.CHAR, Type.LONG -> true;
            case Type.OBJECT -> isBoxedNumeric(ret.getDescriptor());
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

    /** 심볼릭 값: 정수 선형식 coeff*field+constant. field==null이면 순수 상수. top/objectParam은 비선형. */
    private record Sym(boolean top, boolean objectParam, String field, long coeff, long constant, int size) {

        static Sym top(int size) {
            return new Sym(true, false, null, 0, 0, size);
        }

        static Sym objectParam(int size) {
            return new Sym(false, true, null, 0, 0, size);
        }

        static Sym constant(long v, int size) {
            return new Sym(false, false, null, 0, v, size);
        }

        static Sym field(String name, int size) {
            return new Sym(false, false, name, 1, 0, size);
        }

        boolean isLinear() {
            return !top && !objectParam;
        }

        Sym withSize(int newSize) {
            return new Sym(top, objectParam, field, coeff, constant, newSize);
        }

        Sym add(Sym o) {
            if (!isLinear() || !o.isLinear() || (field != null && o.field != null && !field.equals(o.field))) {
                return Sym.top(size);
            }
            String f = field != null ? field : o.field;
            return new Sym(false, false, f, coeff + o.coeff, constant + o.constant, size);
        }

        Sym sub(Sym o) {
            return add(o.neg());
        }

        Sym neg() {
            if (!isLinear()) {
                return Sym.top(size);
            }
            return new Sym(false, false, field, -coeff, -constant, size);
        }

        Sym mul(Sym o) {
            if (!isLinear() || !o.isLinear()) {
                return Sym.top(size);
            }
            if (field == null) {   // const * (a*x+b)
                return new Sym(false, false, o.field, constant * o.coeff, constant * o.constant, size);
            }
            if (o.field == null) { // (a*x+b) * const
                return new Sym(false, false, field, coeff * o.constant, constant * o.constant, size);
            }
            return Sym.top(size);  // x*y 비선형
        }
    }
}

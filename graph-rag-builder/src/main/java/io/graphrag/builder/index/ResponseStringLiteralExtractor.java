package io.graphrag.builder.index;

import spoon.reflect.CtModel;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.ModifierKind;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Logger;

/**
 * 응답 DTO String 필드를 소비 코드가 동치 비교하는 리터럴을 추출한다(REQ-007, REQ-003).
 *
 * <p>no-classpath라 receiver 타입은 신뢰 불가 → 접근자 simple-name을 키로 쓰되, callSite responseShape의
 * String 필드와 교차해 dtoFqn 버킷에 넣는다. equals-family(equals/equalsIgnoreCase/Objects.equals)만.
 *
 * <p>loud-log 키:
 * <ul>
 *   <li>{@code string-literal-nonequality-skipped} — String 필드 접근자가 startsWith 등 비동치 메서드의
 *       target으로 쓰일 때</li>
 *   <li>{@code string-literal-const-unresolvable} — static final 참조가 소스트리에서 해석 불가일 때</li>
 *   <li>{@code string-literal-accessor-ambiguous} — 동명 String 필드를 가진 응답 DTO가 2개 이상일 때</li>
 * </ul>
 */
public final class ResponseStringLiteralExtractor {

    private static final Logger LOG = Logger.getLogger(ResponseStringLiteralExtractor.class.getName());

    /** equals-family 메서드 이름 집합. */
    private static final Set<String> EQUALITY_METHODS = Set.of("equals", "equalsIgnoreCase");

    /**
     * 모델에서 equals-family 비교를 찾아 응답 DTO String 필드 → 리터럴 맵을 반환한다.
     *
     * @param model     Spoon으로 빌드한 SUT 소스 모델
     * @param callSites 외부 HTTP callSite 목록(responseShape 포함)
     * @return dtoFqn → (field → 정렬·중복제거된 리터럴 리스트)
     */
    public Map<String, Map<String, List<String>>> extract(CtModel model,
            List<ExternalCallSite> callSites) {

        // 1. responseShape의 String 필드 인덱스 구성.
        //    fieldName → {dtoFqn} (모호 판정용)
        //    dtoFqn   → {String fieldName}
        Map<String, Set<String>> fieldToDtos = new TreeMap<>();
        Map<String, Set<String>> dtoStringFields = new TreeMap<>();
        for (ExternalCallSite site : callSites) {
            if (site.responseShape().isEmpty()) {
                continue;
            }
            BodyShape shape = site.responseShape().get();
            for (BodyShape.BodyField f : shape.fields()) {
                if ("java.lang.String".equals(f.javaType())) {
                    fieldToDtos.computeIfAbsent(f.name(), k -> new TreeSet<>()).add(shape.javaType());
                    dtoStringFields.computeIfAbsent(shape.javaType(), k -> new TreeSet<>()).add(f.name());
                }
            }
        }

        // 응답 필드 simple-name 전체 집합(비동치 skip 판정용).
        Set<String> allResponseStringFields = fieldToDtos.keySet();

        // 2. 동일 소스트리 static final String 상수값 인덱스(simpleName → value).
        //    TreeMap: 동명 simple-name 키가 여러 클래스에 존재해도 순서가 결정적(no-classpath 환경에서 HashMap은 비결정적).
        Map<String, String> stringConstants = new TreeMap<>();
        for (CtField<?> field : model.getElements(new TypeFilter<>(CtField.class))) {
            if (field.getModifiers().contains(ModifierKind.FINAL)
                    && field.getModifiers().contains(ModifierKind.STATIC)
                    && field.getDefaultExpression() instanceof CtLiteral<?> lit
                    && lit.getValue() instanceof String s) {
                stringConstants.put(field.getSimpleName(), s);
            }
        }

        // 3. 모든 메서드 호출 순회.
        Map<String, Map<String, Set<String>>> collected = new TreeMap<>();
        for (CtInvocation<?> inv : model.getElements(new TypeFilter<>(CtInvocation.class))) {
            String simple = inv.getExecutable().getSimpleName();

            // 3a. equals/equalsIgnoreCase (인스턴스 호출, 양방향)
            if (EQUALITY_METHODS.contains(simple)
                    && inv.getArguments().size() == 1
                    && inv.getTarget() != null) {
                FieldLit fl = fieldLit(
                        inv.getTarget(), inv.getArguments().get(0), inv, stringConstants);
                if (fl != null) {
                    bucket(fl, fieldToDtos, collected, inv);
                }
                continue;
            }

            // 3b. Objects.equals(a, b) (정적 호출, 양방향)
            if ("equals".equals(simple)
                    && inv.getTarget() instanceof CtTypeAccess<?> ta
                    && "Objects".equals(ta.getAccessedType().getSimpleName())
                    && inv.getArguments().size() == 2) {
                FieldLit fl = fieldLit(
                        inv.getArguments().get(0), inv.getArguments().get(1), inv, stringConstants);
                if (fl == null) {
                    // 반전 시도
                    fl = fieldLit(
                            inv.getArguments().get(1), inv.getArguments().get(0), inv, stringConstants);
                }
                if (fl != null) {
                    bucket(fl, fieldToDtos, collected, inv);
                }
                continue;
            }

            // 3c. 비동치 호출이지만 target이 응답 String 필드 접근자 → loud skip
            if (inv.getTarget() != null && !EQUALITY_METHODS.contains(simple)) {
                String accessorField = fieldRefResolvingLocal(inv.getTarget(), inv);
                if (accessorField != null && allResponseStringFields.contains(accessorField)) {
                    LOG.warning("string-literal-nonequality-skipped: method=" + simple
                            + " field=" + accessorField
                            + " at line " + inv.getPosition().getLine());
                }
            }
        }

        // 4. Set → 정렬 List 변환.
        Map<String, Map<String, List<String>>> out = new TreeMap<>();
        collected.forEach((dto, fields) -> {
            Map<String, List<String>> m = new TreeMap<>();
            fields.forEach((f, lits) -> m.put(f, new ArrayList<>(lits)));
            out.put(dto, m);
        });
        return out;
    }

    // -------------------------------------------------------------------------
    // 내부 헬퍼
    // -------------------------------------------------------------------------

    private record FieldLit(String field, String literal) {}

    /**
     * (a, b) 쌍 중 한쪽이 필드 접근자(또는 로컬 바인딩), 다른쪽이 String 리터럴/상수면 FieldLit 반환.
     * 양쪽 다 리터럴이거나 둘 다 접근자인 경우 null.
     */
    private FieldLit fieldLit(CtExpression<?> a, CtExpression<?> b, CtInvocation<?> inv,
            Map<String, String> constants) {
        String litA = literalOrConst(a, constants, inv);
        String litB = literalOrConst(b, constants, inv);
        String refA = fieldRefResolvingLocal(a, inv);
        String refB = fieldRefResolvingLocal(b, inv);

        if (litB != null && refA != null) {
            return new FieldLit(refA, litB);
        }
        if (litA != null && refB != null) {
            return new FieldLit(refB, litA);
        }
        return null;
    }

    /**
     * 직접 String 리터럴이거나 소스트리 static final String 상수 참조면 값을 반환.
     * 외부 상수(소스트리 미해석)이면 loud 로그 후 null.
     */
    private String literalOrConst(CtExpression<?> e, Map<String, String> constants,
            CtInvocation<?> inv) {
        String lit = SpoonExpressionRefs.stringLiteral(e);
        if (lit != null) {
            return lit;
        }
        if (e instanceof CtFieldRead<?> fr) {
            CtFieldReference<?> ref = fr.getVariable();
            String name = ref.getSimpleName();
            if (constants.containsKey(name)) {
                return constants.get(name);
            }
            // 소스트리에 없는 외부 상수 참조 → loud skip
            LOG.warning("string-literal-const-unresolvable: ref=" + name
                    + " at line " + inv.getPosition().getLine());
        }
        return null;
    }

    /**
     * 표현식이 직접 필드/레코드 접근자 호출이면 field simple-name 반환.
     * 로컬 변수 {@code String r = resp.field()} 바인딩이면 그 접근자 이름 반환.
     * 그 외는 null.
     */
    private String fieldRefResolvingLocal(CtExpression<?> e, CtInvocation<?> inv) {
        // 직접 accessor 호출: resp.region() → "region"
        if (e instanceof CtInvocation<?>) {
            return SpoonExpressionRefs.fieldRef(e);
        }
        // 로컬 변수: String r = resp.region(); "X".equals(r)
        if (e instanceof CtVariableRead<?> vr) {
            var decl = vr.getVariable().getDeclaration();
            if (decl instanceof CtLocalVariable<?> lv
                    && lv.getDefaultExpression() instanceof CtInvocation<?> bound) {
                return SpoonExpressionRefs.fieldRef(bound);
            }
        }
        return null;
    }

    /**
     * FieldLit를 dtoFqn 버킷에 넣는다. 동명 String 필드를 가진 DTO가 2개 이상이면 loud skip.
     */
    private void bucket(FieldLit fl, Map<String, Set<String>> fieldToDtos,
            Map<String, Map<String, Set<String>>> collected, CtInvocation<?> inv) {
        Set<String> dtos = fieldToDtos.get(fl.field());
        if (dtos == null) {
            // 응답 DTO의 String 필드가 아님 — 무시(비로깅)
            return;
        }
        if (dtos.size() > 1) {
            LOG.warning("string-literal-accessor-ambiguous: field=" + fl.field()
                    + " dtos=" + dtos
                    + " at line " + inv.getPosition().getLine());
            return;
        }
        String dtoFqn = dtos.iterator().next();
        collected.computeIfAbsent(dtoFqn, k -> new TreeMap<>())
                .computeIfAbsent(fl.field(), k -> new TreeSet<>())
                .add(fl.literal());
    }
}

package io.graphrag.builder.provenance;

import io.graphrag.builder.provenance.ProvenanceReport.GuardFact;
import io.graphrag.builder.provenance.ProvenanceReport.Origin;
import io.graphrag.builder.provenance.ProvenanceReport.Reason;
import io.graphrag.builder.provenance.ProvenanceReport.Unresolved;
import io.graphrag.builder.provenance.ProvenanceReport.ValueRef;
import io.graphrag.model.Endpoint;
import spoon.reflect.CtModel;
import spoon.reflect.code.BinaryOperatorKind;
import spoon.reflect.code.CtBinaryOperator;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtIf;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLambda;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtThrow;
import spoon.reflect.code.CtUnaryOperator;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtRecord;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 재귀 슬라이서 코어: 핸들러 메서드에서 시작해 호출 그래프를 DFS로 순회하며 가드(guard)를
 * 수집하고, 가드 피연산자의 출처(origin)를 태깅한다.
 *
 * <p>이 클래스의 범위(REQ-002 + REQ-001의 INPUT 부분):
 * <ul>
 *   <li>호출 그래프 DFS — 방문 집합(순환 종료) + depth cap(무한 체인 방지, cap 초과는
 *       {@link Reason#DEPTH_CAP}로 unresolved에 기록).</li>
 *   <li>가드 인식 2형태 — ① {@code CtIf} 중 then/else 분기가 {@code throw} 또는
 *       {@code ResponseEntity.status(4xx|5xx)} 반환으로 이어지는 것, ② {@code Optional.orElseThrow}/
 *       {@code orElseGet} 패턴 중 람다 본문이 {@code ResponseStatusException}(또는 {@code throw})이면
 *       그 수신 표현식을 존재(EXISTS) 가드로 수집.</li>
 *   <li>피연산자 분해 — {@code CtBinaryOperator}/단항연산자/{@code equals} 계열을 재귀 분해해 리프
 *       피연산자를 얻는다.</li>
 *   <li>INPUT 태깅 — 리프 피연산자의 루트가 핸들러 자신의 파라미터이면(JavaBean getter 체인 또는
 *       record canonical accessor 경유 포함) {@link Origin#INPUT} + jsonPath(dot-path, 예:
 *       "amount", "user.id"). record는 {@code req.amount()}처럼 get/is 접두사가 없으므로,
 *       호출 메서드명이 수신 타입의 record component명과 정확히 일치하면 별도로 인식한다.</li>
 * </ul>
 *
 * <p>DB_READ/EXTERNAL_RESPONSE/DERIVED 태깅은 후속 task 범위 — 이 클래스는 그 경우 피연산자를
 * {@link Origin#UNKNOWN}으로 둔다. unguarded 필드 탐지도 후속 task 범위(항상 빈 리스트).
 *
 * <p>메서드 조회는 1-hop 선례({@code ConstraintExtractor.reachableMethods})와 동일하게
 * {@code CtModel} 전체를 타입명·메서드명으로 선형 탐색한다(재사용이 아니라 일반화 재구현 — Spoon
 * noClasspath 모드에서 {@code CtExecutableReference.getExecutableDeclaration()}이 크로스클래스
 * 호출에 대해 신뢰성 있게 해소되지 않기 때문).
 */
public class ProvenanceIndexer {

    private static final Map<BinaryOperatorKind, String> OP_SYMBOLS = Map.ofEntries(
            Map.entry(BinaryOperatorKind.AND, "&&"),
            Map.entry(BinaryOperatorKind.OR, "||"),
            Map.entry(BinaryOperatorKind.EQ, "=="),
            Map.entry(BinaryOperatorKind.NE, "!="),
            Map.entry(BinaryOperatorKind.GT, ">"),
            Map.entry(BinaryOperatorKind.GE, ">="),
            Map.entry(BinaryOperatorKind.LT, "<"),
            Map.entry(BinaryOperatorKind.LE, "<=")
    );

    private static final Set<String> SUCCESS_STATUS_NAMES = Set.of(
            "OK", "CREATED", "ACCEPTED", "NO_CONTENT", "FOUND",
            "MOVED_PERMANENTLY", "NOT_MODIFIED", "PARTIAL_CONTENT");

    /** DFS 프론티어 항목: 방문할 메서드와 핸들러로부터의 깊이. */
    private record Frame(CtMethod<?> method, int depth) {
    }

    public ProvenanceReport analyze(CtModel model, Endpoint endpoint, int maxDepth) {
        List<GuardFact> guards = new ArrayList<>();
        List<Unresolved> unresolved = new ArrayList<>();

        CtMethod<?> handler = resolveMethod(model, endpoint.handlerClass(), endpoint.handlerMethod());
        if (handler == null) {
            return new ProvenanceReport(endpoint.id(), guards, List.of(), unresolved);
        }

        Set<CtParameter<?>> handlerParams = new LinkedHashSet<>(handler.getParameters());

        Deque<Frame> stack = new ArrayDeque<>();
        Set<String> visited = new LinkedHashSet<>();
        stack.push(new Frame(handler, 0));
        visited.add(methodKey(handler));

        while (!stack.isEmpty()) {
            Frame frame = stack.pop();
            CtMethod<?> method = frame.method();
            int depth = frame.depth();

            collectGuards(model, method, handlerParams, guards);

            for (CtInvocation<?> inv : method.getElements(new TypeFilter<>(CtInvocation.class))) {
                CtExecutableReference<?> executable = inv.getExecutable();
                var declaringTypeRef = executable.getDeclaringType();
                if (declaringTypeRef == null) {
                    continue; // noClasspath 미해소 — 보수적 skip (NO_CLASSPATH 세부 분류는 후속 task 범위)
                }
                String typeFqn = declaringTypeRef.getQualifiedName();
                if (typeFqn == null || typeFqn.isEmpty()) {
                    continue;
                }
                CtMethod<?> callee = resolveMethod(model, typeFqn.replace('$', '.'),
                        executable.getSimpleName());
                if (callee == null) {
                    continue; // 소스 트리 밖(라이브러리 등) — 보수적 skip
                }
                String key = methodKey(callee);
                if (visited.contains(key)) {
                    continue; // 이미 방문 — 순환/다이아몬드 종료
                }
                int calleeDepth = depth + 1;
                visited.add(key);
                if (calleeDepth > maxDepth) {
                    unresolved.add(new Unresolved(locationOf(inv), Reason.DEPTH_CAP,
                            typeFqn.replace('$', '.') + "#" + executable.getSimpleName()));
                    continue;
                }
                stack.push(new Frame(callee, calleeDepth));
            }
        }

        return new ProvenanceReport(endpoint.id(), guards, List.of(), unresolved);
    }

    // ---- 메서드 조회 ----

    private static CtMethod<?> resolveMethod(CtModel model, String classFqn, String methodName) {
        for (CtType<?> type : model.getAllTypes()) {
            if (!type.getQualifiedName().replace('$', '.').equals(classFqn)) {
                continue;
            }
            for (CtMethod<?> method : type.getMethods()) {
                if (method.getSimpleName().equals(methodName)) {
                    return method;
                }
            }
        }
        return null;
    }

    private static String methodKey(CtMethod<?> method) {
        CtType<?> declaringType = method.getDeclaringType();
        String typeFqn = declaringType != null
                ? declaringType.getQualifiedName().replace('$', '.')
                : "?";
        return typeFqn + "#" + method.getSimpleName();
    }

    // ---- 가드 수집 ----

    private void collectGuards(CtModel model, CtMethod<?> method, Set<CtParameter<?>> handlerParams,
                               List<GuardFact> guards) {
        collectIfGuards(model, method, handlerParams, guards);
        collectExistsGuards(model, method, handlerParams, guards);
    }

    /** ① CtIf 가드: then/else 분기가 throw 또는 ResponseEntity.status(4xx|5xx) 반환으로 이어지는 경우만 채택. */
    private void collectIfGuards(CtModel model, CtMethod<?> method, Set<CtParameter<?>> handlerParams,
                                 List<GuardFact> guards) {
        for (CtIf ctIf : method.getElements(new TypeFilter<>(CtIf.class))) {
            if (!isErrorGuard(ctIf)) {
                continue;
            }
            CtExpression<?> condition = ctIf.getCondition();
            List<ValueRef> operands = new ArrayList<>();
            decomposeCondition(condition, handlerParams, model, operands);
            guards.add(new GuardFact(locationOf(condition), opSymbol(condition), operands));
        }
    }

    /**
     * ② EXISTS 가드: {@code Optional.orElseThrow(() -> ...)}/{@code orElseGet(() -> ...)} 중
     * 람다 본문이 throw 또는 ResponseStatusException 생성이면, 수신 표현식(예: findById(x))의
     * 인자를 피연산자로 삼아 EXISTS 가드로 수집한다.
     */
    private void collectExistsGuards(CtModel model, CtMethod<?> method, Set<CtParameter<?>> handlerParams,
                                     List<GuardFact> guards) {
        for (CtInvocation<?> inv : method.getElements(new TypeFilter<>(CtInvocation.class))) {
            String name = inv.getExecutable().getSimpleName();
            if (!"orElseThrow".equals(name) && !"orElseGet".equals(name)) {
                continue;
            }
            if (inv.getArguments().isEmpty()
                    || !(inv.getArguments().get(0) instanceof CtLambda<?> lambda)
                    || !isExistsGuardLambda(lambda)) {
                continue;
            }
            CtExpression<?> receiver = inv.getTarget();
            if (receiver == null) {
                continue;
            }
            List<ValueRef> operands = new ArrayList<>();
            if (receiver instanceof CtInvocation<?> receiverInvocation) {
                for (CtExpression<?> arg : receiverInvocation.getArguments()) {
                    decomposeCondition(arg, handlerParams, model, operands);
                }
            }
            if (operands.isEmpty()) {
                operands.add(new ValueRef(Origin.UNKNOWN, null, null, null, null, null,
                        null, receiverLabel(receiver), null));
            }
            guards.add(new GuardFact(locationOf(inv), "EXISTS", operands));
        }
    }

    private static String receiverLabel(CtExpression<?> receiver) {
        return receiver instanceof CtInvocation<?> inv ? inv.getExecutable().getSimpleName() : null;
    }

    private static boolean isExistsGuardLambda(CtLambda<?> lambda) {
        boolean hasThrow = !lambda.getElements(new TypeFilter<>(CtThrow.class)).isEmpty();
        boolean constructsResponseStatusException = lambda.getElements(new TypeFilter<>(CtConstructorCall.class))
                .stream()
                .anyMatch(cc -> cc.getType() != null
                        && "ResponseStatusException".equals(cc.getType().getSimpleName()));
        return hasThrow || constructsResponseStatusException;
    }

    private static boolean isErrorGuard(CtIf ctIf) {
        return branchIsError(ctIf.getThenStatement()) || branchIsError(ctIf.getElseStatement());
    }

    private static boolean branchIsError(CtStatement branch) {
        if (branch == null) {
            return false;
        }
        if (!branch.getElements(new TypeFilter<>(CtThrow.class)).isEmpty()) {
            return true;
        }
        for (CtReturn<?> ret : branch.getElements(new TypeFilter<CtReturn<?>>(CtReturn.class))) {
            if (isErrorResponseEntity(ret.getReturnedExpression())) {
                return true;
            }
        }
        return false;
    }

    /** {@code ResponseEntity.status(<4xx|5xx>)} 반환 여부(best-effort — 값 미해석 시 보수적으로 error 취급). */
    private static boolean isErrorResponseEntity(CtExpression<?> expr) {
        if (!(expr instanceof CtInvocation<?> inv) || !"status".equals(inv.getExecutable().getSimpleName())) {
            return false;
        }
        CtExpression<?> target = inv.getTarget();
        if (target == null || !target.toString().contains("ResponseEntity") || inv.getArguments().isEmpty()) {
            return false;
        }
        CtExpression<?> arg = inv.getArguments().get(0);
        if (arg instanceof CtLiteral<?> literal && literal.getValue() instanceof Integer code) {
            return code >= 400;
        }
        if (arg instanceof CtFieldRead<?> fieldRead) {
            return !SUCCESS_STATUS_NAMES.contains(fieldRead.getVariable().getSimpleName());
        }
        return true;
    }

    // ---- 피연산자 분해·분류 ----

    /**
     * 조건식을 리프 피연산자까지 재귀 분해한다: {@code CtBinaryOperator}(AND/OR 포함)는 좌우변으로,
     * 단항연산자는 피연산자로, {@code equals}/{@code equalsIgnoreCase} 호출은 수신자+첫 인자로 분해한다.
     */
    private void decomposeCondition(CtExpression<?> expr, Set<CtParameter<?>> handlerParams, CtModel model,
                                    List<ValueRef> out) {
        if (expr instanceof CtBinaryOperator<?> bin) {
            decomposeCondition(bin.getLeftHandOperand(), handlerParams, model, out);
            decomposeCondition(bin.getRightHandOperand(), handlerParams, model, out);
            return;
        }
        if (expr instanceof CtUnaryOperator<?> un) {
            decomposeCondition(un.getOperand(), handlerParams, model, out);
            return;
        }
        if (expr instanceof CtInvocation<?> inv && isEqualsLike(inv)
                && inv.getTarget() != null && !inv.getArguments().isEmpty()) {
            decomposeCondition(inv.getTarget(), handlerParams, model, out);
            decomposeCondition(inv.getArguments().get(0), handlerParams, model, out);
            return;
        }
        out.add(classifyOperand(expr, handlerParams, model));
    }

    private static boolean isEqualsLike(CtInvocation<?> inv) {
        String name = inv.getExecutable().getSimpleName();
        return "equals".equals(name) || "equalsIgnoreCase".equals(name);
    }

    private ValueRef classifyOperand(CtExpression<?> expr, Set<CtParameter<?>> handlerParams, CtModel model) {
        Optional<List<String>> segments = getterSegments(expr, handlerParams, model);
        if (segments.isPresent()) {
            List<String> segs = segments.get();
            String jsonPath = segs.isEmpty() ? bareParamName(expr) : String.join(".", segs);
            return new ValueRef(Origin.INPUT, jsonPath, null, null, null, null,
                    typeNameOf(expr), null, null);
        }
        if (expr instanceof CtLiteral<?> literal) {
            Object value = literal.getValue();
            return new ValueRef(Origin.UNKNOWN, null, null, null, null, null,
                    typeNameOf(expr), null, value == null ? null : String.valueOf(value));
        }
        return new ValueRef(Origin.UNKNOWN, null, null, null, null, null, typeNameOf(expr), null, null);
    }

    /**
     * 표현식의 루트가 핸들러 자신의 파라미터이면 그로부터의 getter 체인 세그먼트 목록을 반환한다
     * (빈 리스트 = 파라미터 자체가 직접 사용됨, 예: path variable). 루트가 파라미터가 아니거나
     * getter 관례를 따르지 않는 메서드를 경유하면 empty.
     */
    private Optional<List<String>> getterSegments(CtExpression<?> expr, Set<CtParameter<?>> handlerParams,
                                                   CtModel model) {
        if (expr instanceof CtVariableRead<?> vr
                && vr.getVariable().getDeclaration() instanceof CtParameter<?> param
                && handlerParams.contains(param)) {
            return Optional.of(new ArrayList<>());
        }
        if (expr instanceof CtInvocation<?> inv && inv.getTarget() != null) {
            String field = getterFieldName(inv.getExecutable().getSimpleName(), inv.getTarget(), model);
            if (field == null) {
                return Optional.empty();
            }
            return getterSegments(inv.getTarget(), handlerParams, model).map(segs -> {
                segs.add(field);
                return segs;
            });
        }
        return Optional.empty();
    }

    private static String bareParamName(CtExpression<?> expr) {
        return expr instanceof CtVariableRead<?> vr ? vr.getVariable().getSimpleName() : null;
    }

    /**
     * {@code getFoo}/{@code isFoo} → {@code foo}(JavaBean 관례), 또는 수신 타입이 record이고
     * 호출된 메서드명이 그 record component명과 정확히 일치하면 그 이름 그대로(record canonical
     * accessor, 예: {@code req.amount()} → {@code "amount"}). 어느 쪽도 아니면 null.
     */
    private static String getterFieldName(String methodName, CtExpression<?> target, CtModel model) {
        if (methodName.startsWith("get") && methodName.length() > 3) {
            return decapitalize(methodName.substring(3));
        }
        if (methodName.startsWith("is") && methodName.length() > 2
                && Character.isUpperCase(methodName.charAt(2))) {
            return decapitalize(methodName.substring(2));
        }
        if (isRecordCanonicalAccessor(methodName, target, model)) {
            return methodName;
        }
        return null;
    }

    private static String decapitalize(String s) {
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /** 수신 표현식의 정적 타입이 record이고, 그 record component명이 호출된 메서드명과 일치하는지. */
    private static boolean isRecordCanonicalAccessor(String methodName, CtExpression<?> target, CtModel model) {
        if (target == null || target.getType() == null) {
            return false;
        }
        CtType<?> declaredType = resolveType(model, target.getType().getQualifiedName());
        return declaredType instanceof CtRecord record
                && record.getRecordComponents().stream()
                        .anyMatch(component -> component.getSimpleName().equals(methodName));
    }

    /** 타입 FQN으로 모델에서 {@code CtType}을 찾는다(중첩 타입 포함, {@code BodyShapeExtractor.findNested}와 동일 관례). */
    private static CtType<?> resolveType(CtModel model, String typeFqn) {
        if (typeFqn == null || typeFqn.isEmpty()) {
            return null;
        }
        String normalized = typeFqn.replace('$', '.');
        for (CtType<?> type : model.getAllTypes()) {
            CtType<?> found = findNestedType(type, normalized);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static CtType<?> findNestedType(CtType<?> type, String qualifiedName) {
        if (type.getQualifiedName().replace('$', '.').equals(qualifiedName)) {
            return type;
        }
        for (CtType<?> nested : type.getNestedTypes()) {
            CtType<?> found = findNestedType(nested, qualifiedName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String typeNameOf(CtExpression<?> expr) {
        return expr.getType() != null ? expr.getType().getSimpleName() : null;
    }

    private static String opSymbol(CtExpression<?> condition) {
        if (condition instanceof CtBinaryOperator<?> bin) {
            return OP_SYMBOLS.getOrDefault(bin.getKind(), bin.getKind().name());
        }
        if (condition instanceof CtUnaryOperator<?>) {
            return "!";
        }
        return "guard";
    }

    private static String locationOf(CtElement element) {
        SourcePosition position = element.getPosition();
        if (position == null || !position.isValidPosition()) {
            return null;
        }
        String fileName = position.getFile() != null ? position.getFile().getName() : "?";
        return fileName + ":" + position.getLine();
    }
}

package io.graphrag.builder.provenance;

import io.graphrag.builder.provenance.ProvenanceReport.GuardFact;
import io.graphrag.builder.provenance.ProvenanceReport.Origin;
import io.graphrag.builder.provenance.ProvenanceReport.Reason;
import io.graphrag.builder.provenance.ProvenanceReport.UnguardedField;
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
import spoon.reflect.code.CtLocalVariable;
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
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 재귀 슬라이서 코어: 핸들러 메서드에서 시작해 호출 그래프를 DFS로 순회하며 가드(guard)를
 * 수집하고, 가드 피연산자의 출처(origin)를 태깅한다.
 *
 * <p>이 클래스의 범위(REQ-002 + REQ-001의 INPUT 부분 + REQ-004):
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
 *   <li>DB_READ 태깅 — 리프 피연산자가 repository({@code JpaRepository} 서브타입/{@code @Repository}/
 *       알려진 MyBatis mapper FQN) 반환값(직접 체이닝 또는 로컬 변수 1단 경유)에 대한 **단일 getter
 *       hop**이면 {@link Origin#DB_READ} + {@link JpaColumnResolver}로 해석한 table/column. 중첩
 *       엔티티 관계를 넘나드는 다단 getter 체인(예: {@code account.getOwner().getEmail()})은
 *       미지원 — 그 피연산자는 UNKNOWN으로 남는다(자세한 범위는 {@link #classifyDbRead}/
 *       {@link #repositoryEntityType} 참고).</li>
 * </ul>
 *
 * <p>이 클래스의 추가 범위(REQ-003 + REQ-032 + REQ-001의 EXTERNAL 부분):
 * <ul>
 *   <li>EXTERNAL_RESPONSE 태깅 — RestTemplate/WebClient 직접 호출, 또는 그것을 감싼 클라이언트
 *       클래스(필드에 {@code RestTemplate}/{@code WebClient}를 갖거나 {@code @FeignClient}가 붙은
 *       타입)의 메서드 반환값에 대한 accessor(JavaBean getter 또는 record canonical accessor) 호출이면
 *       {@link Origin#EXTERNAL_RESPONSE} + callSite(`"<HTTP메서드> <pathLiteral>"`, 클라이언트 메서드
 *       본문에서 추출 불가하면 `"<클라이언트FQN>#<메서드명>"` 폴백) + stubField(accessor가 읽는 필드명).</li>
 *   <li>DERIVED 태깅 — 비교/논리 연산자(AND/OR/EQ/NE/GT/GE/LT/LE)가 아닌 이항연산자(산술·문자열 파생,
 *       예: {@code score * 2})가 INPUT/DB_READ 피연산자를 하나 이상 감싸고 있으면 그 전체 식을 하나의
 *       리프로 분류해 {@link Origin#DERIVED} + javaType(원본 유지)로 태깅한다. concolic 채널로의 실제
 *       해 배치(합성 시 결정값 vs 갭 마커)는 이 클래스 범위 밖 — synthesize-triple(C2)이 담당한다.</li>
 *   <li>UNKNOWN + MULTI_IMPL — 피연산자가 되는 호출의 선언 타입이 인터페이스이고, 모델 내 그 인터페이스의
 *       구현체가 2개 이상이면 {@link Origin#UNKNOWN}으로 태깅하고 {@link Unresolved}(location,
 *       {@link Reason#MULTI_IMPL}, targetType=인터페이스 FQN)를 리포트의 unresolved 배열에 표면화한다.</li>
 *   <li>unguarded 필드 탐지(REQ-001) — {@code @RequestBody} 파라미터 타입을 재귀 전개(record
 *       canonical accessor 또는 JavaBean getFoo/isFoo, List는 대표원소로 계속 전개, Map은 동적 키라
 *       leaf로 처리 — INPUT 태깅과 동일한 dot-path 관례)해 얻은 전체 필드 dot-path 중, 가드
 *       피연산자(Origin.INPUT)의 jsonPath로 한 번도 참조되지 않은 필드를 {@link UnguardedField}로
 *       수집한다. semanticHint는 필드명(소문자) 기반 결정적 규칙 — {@link #semanticHint} 참고.</li>
 * </ul>
 *
 * <p>메서드 조회는 1-hop 선례({@code ConstraintExtractor.reachableMethods})와 동일하게
 * {@code CtModel} 전체를 타입명·메서드명으로 선형 탐색한다(재사용이 아니라 일반화 재구현 — Spoon
 * noClasspath 모드에서 {@code CtExecutableReference.getExecutableDeclaration()}이 크로스클래스
 * 호출에 대해 신뢰성 있게 해소되지 않기 때문). 같은 이유로 외부 클라이언트 callSite 추출 로직도
 * {@code ResponseDtoIndexer}의 URL 리터럴/HTTP 메서드 추출과 유사하지만 별도로 축소 재구현한다
 * (패키지 자기완결성 유지 — provenance 패키지가 index 패키지 내부 구현에 결합하지 않도록).
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

    /** 외부 HTTP 클라이언트 라이브러리 타입(직접 호출 또는 래핑 클래스 필드 판별용, REQ-001 EXTERNAL 부분). */
    private static final Set<String> CLIENT_LIB_TYPES = Set.of("RestTemplate", "WebClient");

    /**
     * List 계열 타입명(REQ-034 대표원소 규약 — {@code list.get(index)}는 dot-path 세그먼트를
     * 추가하지 않고 대표(첫) 원소 그대로 부모 경로를 이어간다).
     */
    private static final Set<String> LIST_LIKE_TYPES = Set.of(
            "List", "ArrayList", "LinkedList", "Collection", "Set", "HashSet", "LinkedHashSet",
            "Queue", "Deque");

    /**
     * Map 계열 타입명(REQ-034 — {@code map.get("key")}는 리터럴 키 이름을 그대로 dot-path
     * 세그먼트로 추가한다, 예: {@code "configs.region"}).
     */
    private static final Set<String> MAP_LIKE_TYPES = Set.of(
            "Map", "HashMap", "LinkedHashMap", "TreeMap", "SortedMap", "ConcurrentHashMap");

    /** {@code ResponseDtoIndexer.CLIENT_METHODS}와 동일 관례(축소 재구현, 클래스 상단 doc 참고). */
    private static final Set<String> CLIENT_LIB_METHODS = Set.of(
            "getForObject", "postForObject", "getForEntity", "postForEntity", "exchange");

    /** DFS 프론티어 항목: 방문할 메서드와 핸들러로부터의 깊이. */
    private record Frame(CtMethod<?> method, int depth) {
    }

    /** MyBatis mapper 인터페이스 FQN 집합(REQ-004 repository 인식용). 없으면 빈 집합. */
    private final Set<String> mapperInterfaceFqns;

    public ProvenanceIndexer() {
        this(Set.of());
    }

    /** mapperInterfaceFqns: 기존 {@code MapperXmlIndexer} 결과의 namespace(=mapper 인터페이스 FQN) 집합. */
    public ProvenanceIndexer(Set<String> mapperInterfaceFqns) {
        this.mapperInterfaceFqns = mapperInterfaceFqns == null ? Set.of() : mapperInterfaceFqns;
    }

    public ProvenanceReport analyze(CtModel model, Endpoint endpoint, int maxDepth) {
        List<GuardFact> guards = new ArrayList<>();
        List<Unresolved> unresolved = new ArrayList<>();
        // REQ-001 unguarded 오탐 방지: DERIVED로 감싸이는 리프의 jsonPath는 최종 ValueRef에 남지
        // 않으므로(derivesFromTrackedOrigin은 origin만 보고 jsonPath를 버림), classifyOperand가
        // INPUT을 인식하는 모든 지점(가드 최상위 피연산자든 DERIVED 판정용 내부 재귀 호출이든)에서
        // 별도로 적재하는 분석-스코프 참조 집합. ValueRef 스키마는 그대로 두고 내부적으로만 쓴다.
        Set<String> referencedInputPaths = new LinkedHashSet<>();

        CtMethod<?> handler = resolveMethod(model, endpoint.handlerClass(), endpoint.handlerMethod());
        if (handler == null) {
            return new ProvenanceReport(endpoint.id(), guards, List.of(), unresolved);
        }

        Set<CtParameter<?>> handlerParams = new LinkedHashSet<>(handler.getParameters());
        JpaColumnResolver columnResolver = new JpaColumnResolver(model);

        Deque<Frame> stack = new ArrayDeque<>();
        Set<String> visited = new LinkedHashSet<>();
        stack.push(new Frame(handler, 0));
        visited.add(methodKey(handler));

        while (!stack.isEmpty()) {
            Frame frame = stack.pop();
            CtMethod<?> method = frame.method();
            int depth = frame.depth();

            collectGuards(model, method, handlerParams, columnResolver, guards, unresolved, referencedInputPaths);

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

        List<UnguardedField> unguarded = collectUnguardedFields(handler, referencedInputPaths, model);
        return new ProvenanceReport(endpoint.id(), guards, unguarded, unresolved);
    }

    // ---- unguarded 필드 탐지(REQ-001) ----

    /**
     * {@code handler}의 {@code @RequestBody} 파라미터 타입을 재귀 전개해 얻은 전체 필드 dot-path 중,
     * {@code referencedInputPaths}(가드 수집 중 INPUT으로 인식된 모든 jsonPath — DERIVED로 감싸여
     * 최종 ValueRef에는 남지 않는 리프도 포함)에 없는 필드를 반환한다. {@code @RequestBody} 파라미터가
     * 없으면(예: body 없는 GET) 빈 리스트.
     */
    private List<UnguardedField> collectUnguardedFields(CtMethod<?> handler, Set<String> referencedInputPaths,
                                                         CtModel model) {
        CtParameter<?> bodyParam = findRequestBodyParam(handler);
        if (bodyParam == null) {
            return List.of();
        }
        List<UnguardedField> out = new ArrayList<>();
        collectFieldPaths(bodyParam.getType(), "", model, referencedInputPaths, out, new LinkedHashSet<>());
        return out;
    }

    /** {@code @RequestBody}가 붙은 핸들러 파라미터(없으면 null — 요청 바디가 없는 핸들러). */
    private static CtParameter<?> findRequestBodyParam(CtMethod<?> handler) {
        for (CtParameter<?> param : handler.getParameters()) {
            boolean isRequestBody = param.getAnnotations().stream()
                    .anyMatch(a -> "RequestBody".equals(a.getAnnotationType().getSimpleName()));
            if (isRequestBody) {
                return param;
            }
        }
        return null;
    }

    /**
     * {@code type}을 재귀 전개해 leaf 필드 dot-path를 모은다. record는 canonical accessor(record
     * component), 일반 클래스는 JavaBean getFoo/isFoo 관례를 따른다. List/Collection은 대표원소
     * 규약(REQ-034와 동일 — bracket 없이 부모 경로를 그대로 이어감)으로 원소 타입을 계속 전개하고,
     * Map은 키가 동적이라 정적으로 전개할 수 없으므로 그 필드 자체를 leaf로 처리한다. 라이브러리
     * 타입(String/long 등, 모델에서 소스를 찾을 수 없는 타입)도 leaf. {@code visitedTypeFqns}는
     * 자기참조 타입의 무한 재귀를 막는다(리프에 도달하면 제거해 형제 경로에서 같은 타입 재사용 허용).
     */
    private void collectFieldPaths(CtTypeReference<?> type, String path, CtModel model,
                                   Set<String> referencedInputPaths, List<UnguardedField> out,
                                   Set<String> visitedTypeFqns) {
        if (type == null) {
            return;
        }
        String simpleName = type.getSimpleName();
        if (LIST_LIKE_TYPES.contains(simpleName)) {
            if (!type.getActualTypeArguments().isEmpty()) {
                collectFieldPaths(type.getActualTypeArguments().get(0), path, model,
                        referencedInputPaths, out, visitedTypeFqns);
            }
            return;
        }
        if (MAP_LIKE_TYPES.contains(simpleName)) {
            addLeafIfUnguarded(path, type, referencedInputPaths, out);
            return;
        }
        CtType<?> declared = resolveType(model, type.getQualifiedName());
        if (declared == null) {
            addLeafIfUnguarded(path, type, referencedInputPaths, out);
            return;
        }
        String fqn = declared.getQualifiedName().replace('$', '.');
        if (!visitedTypeFqns.add(fqn)) {
            return; // 자기참조 타입 순환 종료
        }
        if (declared instanceof CtRecord record) {
            for (var component : record.getRecordComponents()) {
                String childPath = path.isEmpty() ? component.getSimpleName() : path + "." + component.getSimpleName();
                collectFieldPaths(component.getType(), childPath, model, referencedInputPaths, out, visitedTypeFqns);
            }
        } else {
            for (CtMethod<?> m : declared.getMethods()) {
                String field = fieldNameForGetter(m.getSimpleName());
                if (field == null || !m.getParameters().isEmpty()) {
                    continue;
                }
                String childPath = path.isEmpty() ? field : path + "." + field;
                collectFieldPaths(m.getType(), childPath, model, referencedInputPaths, out, visitedTypeFqns);
            }
        }
        visitedTypeFqns.remove(fqn);
    }

    /** {@code getFoo}/{@code isFoo} → {@code foo}(JavaBean 관례). 어느 쪽도 아니면 null. */
    private static String fieldNameForGetter(String methodName) {
        if (methodName.startsWith("get") && methodName.length() > 3) {
            return decapitalize(methodName.substring(3));
        }
        if (methodName.startsWith("is") && methodName.length() > 2 && Character.isUpperCase(methodName.charAt(2))) {
            return decapitalize(methodName.substring(2));
        }
        return null;
    }

    private static void addLeafIfUnguarded(String path, CtTypeReference<?> type,
                                           Set<String> referencedInputPaths, List<UnguardedField> out) {
        if (path.isEmpty() || referencedInputPaths.contains(path)) {
            return;
        }
        String javaType = type == null ? null : type.getSimpleName();
        String leafName = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
        out.add(new UnguardedField(path, javaType, semanticHint(leafName, javaType)));
    }

    /**
     * 필드명(대소문자 무시) 기반 결정적 semanticHint 규칙(REQ-001):
     * <ol>
     *   <li>"email" 포함 → "email"</li>
     *   <li>"phone" 또는 "tel" 포함 → "phone"</li>
     *   <li>"name"과 일치하거나 "name"으로 끝남 → "person-name"</li>
     *   <li>"note"/"memo"/"comment"/"description" 포함 → "free-text"</li>
     *   <li>그 외 String 타입 → "free-text"</li>
     *   <li>그 외(비-String) → "none"</li>
     * </ol>
     */
    private static String semanticHint(String fieldName, String javaType) {
        String lower = fieldName.toLowerCase(Locale.ROOT);
        if (lower.contains("email")) {
            return "email";
        }
        if (lower.contains("phone") || lower.contains("tel")) {
            return "phone";
        }
        if (lower.equals("name") || lower.endsWith("name")) {
            return "person-name";
        }
        if (lower.contains("note") || lower.contains("memo") || lower.contains("comment")
                || lower.contains("description")) {
            return "free-text";
        }
        if ("String".equals(javaType)) {
            return "free-text";
        }
        return "none";
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
                               JpaColumnResolver columnResolver, List<GuardFact> guards,
                               List<Unresolved> unresolved, Set<String> referencedInputPaths) {
        collectIfGuards(model, method, handlerParams, columnResolver, guards, unresolved, referencedInputPaths);
        collectExistsGuards(model, method, handlerParams, columnResolver, guards, unresolved, referencedInputPaths);
    }

    /** ① CtIf 가드: then/else 분기가 throw 또는 ResponseEntity.status(4xx|5xx) 반환으로 이어지는 경우만 채택. */
    private void collectIfGuards(CtModel model, CtMethod<?> method, Set<CtParameter<?>> handlerParams,
                                 JpaColumnResolver columnResolver, List<GuardFact> guards,
                                 List<Unresolved> unresolved, Set<String> referencedInputPaths) {
        for (CtIf ctIf : method.getElements(new TypeFilter<>(CtIf.class))) {
            if (!isErrorGuard(ctIf)) {
                continue;
            }
            CtExpression<?> condition = ctIf.getCondition();
            List<ValueRef> operands = new ArrayList<>();
            decomposeCondition(condition, handlerParams, model, columnResolver, operands, unresolved,
                    referencedInputPaths);
            guards.add(new GuardFact(locationOf(condition), opSymbol(condition), operands));
        }
    }

    /**
     * ② EXISTS 가드: {@code Optional.orElseThrow(() -> ...)}/{@code orElseGet(() -> ...)} 중
     * 람다 본문이 throw 또는 ResponseStatusException 생성이면, 수신 표현식(예: findById(x))의
     * 인자를 피연산자로 삼아 EXISTS 가드로 수집한다.
     */
    private void collectExistsGuards(CtModel model, CtMethod<?> method, Set<CtParameter<?>> handlerParams,
                                     JpaColumnResolver columnResolver, List<GuardFact> guards,
                                     List<Unresolved> unresolved, Set<String> referencedInputPaths) {
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
                    decomposeCondition(arg, handlerParams, model, columnResolver, operands, unresolved,
                            referencedInputPaths);
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
     * 조건식을 리프 피연산자까지 재귀 분해한다: 비교/논리 {@code CtBinaryOperator}(AND/OR/EQ/NE/GT/GE/
     * LT/LE — {@link #OP_SYMBOLS}에 등록된 종류)는 좌우변으로 계속 분해하지만, 그 외의 이항연산자
     * (산술·문자열 concat 등, 예: {@code score * 2})는 더 분해하지 않고 그 식 전체를 하나의 리프로
     * 남겨 {@link #classifyOperand}가 DERIVED 여부를 판정하게 한다(REQ-032 — 분해해버리면 "파생"이라는
     * 구조 자체가 사라진다). 단항연산자는 피연산자로, {@code equals}/{@code equalsIgnoreCase} 호출은
     * 수신자+첫 인자로 분해한다.
     */
    private void decomposeCondition(CtExpression<?> expr, Set<CtParameter<?>> handlerParams, CtModel model,
                                    JpaColumnResolver columnResolver, List<ValueRef> out,
                                    List<Unresolved> unresolved, Set<String> referencedInputPaths) {
        if (expr instanceof CtBinaryOperator<?> bin) {
            if (OP_SYMBOLS.containsKey(bin.getKind())) {
                decomposeCondition(bin.getLeftHandOperand(), handlerParams, model, columnResolver, out, unresolved,
                        referencedInputPaths);
                decomposeCondition(bin.getRightHandOperand(), handlerParams, model, columnResolver, out, unresolved,
                        referencedInputPaths);
                return;
            }
            out.add(classifyOperand(expr, handlerParams, model, columnResolver, unresolved, referencedInputPaths));
            return;
        }
        if (expr instanceof CtUnaryOperator<?> un) {
            decomposeCondition(un.getOperand(), handlerParams, model, columnResolver, out, unresolved,
                    referencedInputPaths);
            return;
        }
        if (expr instanceof CtInvocation<?> inv && isEqualsLike(inv)
                && inv.getTarget() != null && !inv.getArguments().isEmpty()) {
            decomposeCondition(inv.getTarget(), handlerParams, model, columnResolver, out, unresolved,
                    referencedInputPaths);
            decomposeCondition(inv.getArguments().get(0), handlerParams, model, columnResolver, out, unresolved,
                    referencedInputPaths);
            return;
        }
        out.add(classifyOperand(expr, handlerParams, model, columnResolver, unresolved, referencedInputPaths));
    }

    private static boolean isEqualsLike(CtInvocation<?> inv) {
        String name = inv.getExecutable().getSimpleName();
        return "equals".equals(name) || "equalsIgnoreCase".equals(name);
    }

    /**
     * {@code referencedInputPaths}: INPUT으로 인식된 리프의 jsonPath를 무조건 적재하는 분석-스코프
     * 누적기(unguarded 판정용, REQ-001) — 이 메서드가 반환하는 {@link ValueRef}가 그대로 가드
     * 피연산자로 쓰이든({@link #decomposeCondition}), DERIVED 판정용으로 origin만 보고 버려지든
     * ({@link #derivesFromTrackedOrigin}) 상관없이 "이 필드는 어떤 가드에 실제로 쓰였다"는 사실은
     * 동일하므로, ValueRef 스키마를 건드리지 않고 이 누적기 하나로 both call site를 커버한다.
     */
    private ValueRef classifyOperand(CtExpression<?> expr, Set<CtParameter<?>> handlerParams, CtModel model,
                                     JpaColumnResolver columnResolver, List<Unresolved> unresolved,
                                     Set<String> referencedInputPaths) {
        Optional<List<String>> segments = getterSegments(expr, handlerParams, model);
        if (segments.isPresent()) {
            List<String> segs = segments.get();
            String jsonPath = segs.isEmpty() ? bareParamName(expr) : String.join(".", segs);
            if (jsonPath != null) {
                referencedInputPaths.add(jsonPath);
            }
            return new ValueRef(Origin.INPUT, jsonPath, null, null, null, null,
                    typeNameOf(expr), null, null);
        }
        Optional<ValueRef> dbRead = classifyDbRead(expr, model, columnResolver);
        if (dbRead.isPresent()) {
            return dbRead.get();
        }
        Optional<ValueRef> external = classifyExternalResponse(expr, model);
        if (external.isPresent()) {
            return external.get();
        }
        if (expr instanceof CtBinaryOperator<?> bin
                && derivesFromTrackedOrigin(bin, handlerParams, model, columnResolver, unresolved, referencedInputPaths)) {
            return new ValueRef(Origin.DERIVED, null, null, null, null, null, typeNameOf(expr), null, null);
        }
        Optional<String> multiImplTarget = multiImplTargetType(expr, model);
        if (multiImplTarget.isPresent()) {
            unresolved.add(new Unresolved(locationOf(expr), Reason.MULTI_IMPL, multiImplTarget.get()));
            return new ValueRef(Origin.UNKNOWN, null, null, null, null, null, typeNameOf(expr), null, null);
        }
        if (expr instanceof CtLiteral<?> literal) {
            Object value = literal.getValue();
            return new ValueRef(Origin.UNKNOWN, null, null, null, null, null,
                    typeNameOf(expr), null, value == null ? null : String.valueOf(value));
        }
        return new ValueRef(Origin.UNKNOWN, null, null, null, null, null, typeNameOf(expr), null, null);
    }

    // ---- DERIVED 태깅(REQ-032, 태깅 절반 — concolic 해 합성 배치는 synthesize-triple(C2) 범위) ----

    /**
     * 이항연산자 트리(중첩 산술 포함, 예: {@code (a + b) * 2})를 리프까지 내려가며 각 리프를
     * {@link #classifyOperand}로 분류해 INPUT 또는 DB_READ 출처가 하나라도 있는지 확인한다. 리프
     * 분류 과정에서 발생하는 부수효과(예: MULTI_IMPL 미해결 기록, {@code referencedInputPaths} 적재)는
     * 그 리프가 실제로 미해결/참조된 것이므로 그대로 유지한다 — DERIVED 여부와 무관하게 유효한 기록이다.
     */
    private boolean derivesFromTrackedOrigin(CtExpression<?> expr, Set<CtParameter<?>> handlerParams, CtModel model,
                                             JpaColumnResolver columnResolver, List<Unresolved> unresolved,
                                             Set<String> referencedInputPaths) {
        if (expr instanceof CtBinaryOperator<?> bin) {
            return derivesFromTrackedOrigin(bin.getLeftHandOperand(), handlerParams, model, columnResolver,
                            unresolved, referencedInputPaths)
                    || derivesFromTrackedOrigin(bin.getRightHandOperand(), handlerParams, model, columnResolver,
                            unresolved, referencedInputPaths);
        }
        if (expr instanceof CtUnaryOperator<?> un) {
            return derivesFromTrackedOrigin(un.getOperand(), handlerParams, model, columnResolver, unresolved,
                    referencedInputPaths);
        }
        Origin origin = classifyOperand(expr, handlerParams, model, columnResolver, unresolved, referencedInputPaths)
                .origin();
        return origin == Origin.INPUT || origin == Origin.DB_READ;
    }

    // ---- UNKNOWN + MULTI_IMPL 태깅(REQ-003) ----

    /**
     * expr이 메서드 호출이고 그 선언 타입이 모델 내 인터페이스이며, 그 인터페이스를 구현하는 클래스가
     * 모델 내에 2개 이상이면 그 인터페이스의 FQN을 반환한다(다형 호출이라 정적으로 어느 구현체가
     * 실행될지 결정할 수 없음 — UNKNOWN 강등 + unresolved 표면화 대상).
     */
    private Optional<String> multiImplTargetType(CtExpression<?> expr, CtModel model) {
        if (!(expr instanceof CtInvocation<?> inv)) {
            return Optional.empty();
        }
        var declaringTypeRef = inv.getExecutable().getDeclaringType();
        if (declaringTypeRef == null) {
            return Optional.empty();
        }
        CtType<?> declaringType = resolveType(model, declaringTypeRef.getQualifiedName());
        if (declaringType == null || !declaringType.isInterface()) {
            return Optional.empty();
        }
        String fqn = declaringType.getQualifiedName().replace('$', '.');
        long implCount = model.getAllTypes().stream()
                .filter(t -> !t.isInterface())
                .filter(t -> t.getSuperInterfaces().stream()
                        .anyMatch(i -> fqn.equals(i.getQualifiedName().replace('$', '.'))))
                .count();
        return implCount >= 2 ? Optional.of(fqn) : Optional.empty();
    }

    // ---- EXTERNAL_RESPONSE 태깅(REQ-001 EXTERNAL 부분) ----

    /**
     * expr이 외부 HTTP 클라이언트 응답값에 대한 accessor 호출(JavaBean getter 또는 record canonical
     * accessor)이면 {@link Origin#EXTERNAL_RESPONSE}로 태깅하고 callSite/stubField를 채운다.
     * {@link #getterFieldName}을 그대로 재사용하므로 INPUT/DB_READ와 동일한 accessor 인식 규칙을 쓴다
     * (record면 get/is 접두사 없는 canonical accessor, 예: {@code fraud.status()}).
     */
    private Optional<ValueRef> classifyExternalResponse(CtExpression<?> expr, CtModel model) {
        if (!(expr instanceof CtInvocation<?> inv) || inv.getTarget() == null) {
            return Optional.empty();
        }
        String methodName = inv.getExecutable().getSimpleName();
        String field = getterFieldName(methodName, inv.getTarget(), model);
        if (field == null) {
            return Optional.empty();
        }
        return externalCallSite(inv.getTarget(), model).map(callSite ->
                new ValueRef(Origin.EXTERNAL_RESPONSE, null, null, null, callSite, field,
                        typeNameOf(expr), null, null));
    }

    /**
     * expr(의 로컬 변수 1단 간접 해제 결과)이 외부 클라이언트 호출인지 판별하고, 그렇다면 callSite
     * 문자열(`"<HTTP메서드> <pathLiteral>"`, 추출 불가 시 `"<클라이언트FQN>#<메서드명>"` 폴백)을
     * 반환한다. 직접 호출(RestTemplate/WebClient 메서드를 바로 호출)과 래핑 호출(그런 필드를 가진
     * 커스텀 클라이언트 클래스의 메서드 — 실제 SUT의 {@code FraudClient} 관례) 두 형태를 인식한다.
     */
    private Optional<String> externalCallSite(CtExpression<?> expr, CtModel model) {
        if (expr instanceof CtVariableRead<?> vr
                && vr.getVariable().getDeclaration() instanceof CtLocalVariable<?> localVar
                && localVar.getDefaultExpression() != null) {
            return externalCallSite(localVar.getDefaultExpression(), model);
        }
        if (!(expr instanceof CtInvocation<?> inv)) {
            return Optional.empty();
        }
        CtExecutableReference<?> executable = inv.getExecutable();
        var declaringTypeRef = executable.getDeclaringType();
        if (declaringTypeRef == null) {
            return Optional.empty();
        }
        if (CLIENT_LIB_TYPES.contains(declaringTypeRef.getSimpleName())
                && CLIENT_LIB_METHODS.contains(executable.getSimpleName())) {
            String fallback = declaringTypeRef.getQualifiedName().replace('$', '.')
                    + "#" + executable.getSimpleName();
            return Optional.of(clientCallSiteOrFallback(executable.getSimpleName(), inv.getArguments(), fallback));
        }
        CtType<?> declaringType = resolveType(model, declaringTypeRef.getQualifiedName());
        if (declaringType != null && isExternalClientType(declaringType)) {
            return Optional.of(wrappedClientCallSite(declaringType, executable.getSimpleName()));
        }
        return Optional.empty();
    }

    /** {@code @FeignClient} 어노테이션이 있거나, {@code RestTemplate}/{@code WebClient} 타입 필드를 가진 타입인지. */
    private static boolean isExternalClientType(CtType<?> type) {
        boolean feignClient = type.getAnnotations().stream()
                .anyMatch(a -> "FeignClient".equals(a.getAnnotationType().getSimpleName()));
        if (feignClient) {
            return true;
        }
        return type.getFields().stream()
                .anyMatch(f -> f.getType() != null && CLIENT_LIB_TYPES.contains(f.getType().getSimpleName()));
    }

    /**
     * 래핑 클라이언트 클래스({@code declaringType}) 안의 {@code methodName} 메서드 본문에서 첫
     * {@link #CLIENT_LIB_METHODS} 호출을 찾아 callSite를 추출한다. 본문이 없거나(예: {@code @FeignClient}
     * 인터페이스 메서드) 그런 호출을 찾지 못하거나, 찾았어도 그 호출의 URL 인자에서 path literal을
     * 추출할 수 없으면(예: 변수·{@code UriComponentsBuilder}로 구성된 URL) 클라이언트클래스#메서드로
     * 폴백한다(클래스 상단 doc에 명시된 대로 — 이 task 범위에서는 폴백까지만 보장).
     */
    private String wrappedClientCallSite(CtType<?> declaringType, String methodName) {
        String fallback = declaringType.getQualifiedName().replace('$', '.') + "#" + methodName;
        for (CtMethod<?> method : declaringType.getMethods()) {
            if (!method.getSimpleName().equals(methodName) || method.getBody() == null) {
                continue;
            }
            for (CtInvocation<?> inner : method.getElements(new TypeFilter<>(CtInvocation.class))) {
                if (CLIENT_LIB_METHODS.contains(inner.getExecutable().getSimpleName())) {
                    return clientCallSiteOrFallback(inner.getExecutable().getSimpleName(), inner.getArguments(), fallback);
                }
            }
        }
        return fallback;
    }

    /**
     * RestTemplate/WebClient 호출의 인자에서 {@code "<HTTP메서드> <pathLiteral>"}을 합성한다. URL
     * 인자가 정적 문자열 리터럴(concat 포함)이 아니어서 path를 추출할 수 없으면(예: 메서드 파라미터로
     * 받은 변수, {@code UriComponentsBuilder} 체인 등) bare 메서드명을 반환하지 않고 {@code fallback}
     * (클라이언트클래스#메서드)을 그대로 반환한다 — "추출 가능한 범위까지, 불가하면 클라이언트클래스#
     * 메서드 폴백"이라는 계약(클래스 상단 doc)을 지키기 위함. bare 메서드명은 fallback과 구분되지 않는
     * 문자열이라 추적성을 잃으므로 반환하지 않는다.
     */
    private static String clientCallSiteOrFallback(String methodName, List<CtExpression<?>> args, String fallback) {
        if (args.isEmpty()) {
            return fallback;
        }
        String pathLiteral = pathLiteralOf(args.get(0));
        if (pathLiteral == null) {
            return fallback;
        }
        String httpMethod = httpMethodOf(methodName, args);
        return httpMethod.isEmpty() ? pathLiteral : httpMethod + " " + pathLiteral;
    }

    /**
     * URL 인자의 정적 문자열 concat({@code CtBinaryOperator} PLUS 트리)에서 '/'로 시작하는 첫 리터럴
     * 토큰의 path(query 제외)를 반환한다({@code ResponseDtoIndexer.pathLiteralOf}와 동일 관례).
     */
    private static String pathLiteralOf(CtExpression<?> urlArg) {
        List<String> literals = new ArrayList<>();
        collectStringLiterals(urlArg, literals);
        for (String token : literals) {
            int slash = token.indexOf('/');
            if (slash < 0) {
                continue;
            }
            String fromSlash = token.substring(slash);
            int query = fromSlash.indexOf('?');
            return query < 0 ? fromSlash : fromSlash.substring(0, query);
        }
        return null;
    }

    private static void collectStringLiterals(CtExpression<?> expr, List<String> out) {
        if (expr instanceof CtBinaryOperator<?> binary) {
            collectStringLiterals(binary.getLeftHandOperand(), out);
            collectStringLiterals(binary.getRightHandOperand(), out);
        } else if (expr instanceof CtLiteral<?> literal && literal.getValue() instanceof String value) {
            out.add(value);
        }
    }

    /** get*→GET, post*→POST, exchange→HttpMethod enum 상수(변수/필드 참조면 빈 문자열). */
    private static String httpMethodOf(String methodName, List<CtExpression<?>> args) {
        if (methodName.startsWith("get")) {
            return "GET";
        }
        if (methodName.startsWith("post")) {
            return "POST";
        }
        if ("exchange".equals(methodName) && args.size() >= 2
                && args.get(1) instanceof CtFieldRead<?> fieldRead) {
            return fieldRead.getVariable().getSimpleName();
        }
        return "";
    }

    // ---- DB_READ 태깅(REQ-004) ----

    /**
     * expr이 repository(JpaRepository 서브타입/@Repository/MyBatis mapper) 반환값에 대한 **단일
     * getter 호출**(예: {@code account.getBalance()})이면 {@link Origin#DB_READ}로 태깅하고,
     * {@link JpaColumnResolver}로 table/column을 해석한다.
     *
     * <p><b>지원 범위(단일 hop만):</b> {@code expr}은 그 자체가 getter 호출이어야 하고, 그 호출의
     * 수신 표현식({@code inv.getTarget()})이 (pass-through 언랩·로컬 변수 1단 간접을 거쳐) repository
     * 호출로 직접 귀결되어야 한다. 즉 repository가 돌려준 엔티티 위에서 getter를 **한 번만** 호출하는
     * 형태만 인식한다. {@code account.getOwner().getEmail()}처럼 엔티티 관계를 넘나드는 다단 getter
     * 체인은 지원하지 않는다 — {@code getEmail()}의 수신 표현식은 {@code getOwner()} 호출(엔티티
     * getter)이지 repository 호출이 아니므로 {@link #repositoryEntityType}이 empty를 반환하고, 그
     * 피연산자는 (INPUT도 아니므로) {@link Origin#UNKNOWN}으로 남는다 — 조용한 강등이며 별도
     * unresolved 기록은 없다(REQ-004 수용기준은 단일 hop만 요구; 중첩 관계 탐색은 이 task 범위 밖).
     * 체인 루트가 repository 호출이 아니면 empty.
     */
    private Optional<ValueRef> classifyDbRead(CtExpression<?> expr, CtModel model, JpaColumnResolver columnResolver) {
        if (!(expr instanceof CtInvocation<?> inv) || inv.getTarget() == null) {
            return Optional.empty();
        }
        String methodName = inv.getExecutable().getSimpleName();
        if (getterFieldName(methodName, inv.getTarget(), model) == null) {
            return Optional.empty(); // getter 관례를 따르지 않는 호출은 DB_READ 후보가 아님
        }
        return repositoryEntityType(inv.getTarget(), model).map(entityType -> {
            JpaColumnResolver.TableColumn tc = columnResolver.resolve(entityType, methodName);
            return new ValueRef(Origin.DB_READ, null, tc.table(), tc.column(), null, null,
                    typeNameOf(expr), null, null);
        });
    }

    /**
     * expr이 repository 호출 그 자체(또는 그것을 Optional pass-through·로컬 변수 1단으로 감싼
     * 표현식)인지 판별한다 — {@code account.getOwner()}처럼 **엔티티의 getter 호출**은 여기서
     * 재귀하지 않으므로(그런 호출의 declaringType은 repository가 아니라 엔티티) 다단 getter 체인의
     * 중간 hop을 넘어 repository까지 거슬러 올라가지 않는다. 즉 이 메서드는 "체인 어딘가에
     * repository 호출이 있는가"가 아니라 "expr(의 pass-through/로컬 변수 해제 결과)가 정확히
     * repository 호출인가"를 확인한다.
     *
     * <p>{@code orElseThrow}/{@code orElseGet}/{@code orElse}/{@code get}(Optional 언랩)은
     * 통과(pass-through)하고, 로컬 변수 읽기는 그 선언식(초기화 표현식)으로 계속 추적한다(실제 SUT
     * 관례: {@code Account account = repo.findById(x).orElseThrow(...)} 뒤 {@code account.getX()}
     * — 이때 {@code getX()}를 호출하는 {@link #classifyDbRead}가 이 메서드에 넘기는 것은 {@code
     * account}이지 {@code getX()} 자신이 아니므로, 로컬 변수 1단 간접까지만 지원되고 그 이상의
     * getter 체인은 지원 범위 밖이다). repository 호출을 찾으면 그 반환 타입(Optional/List 등
     * 컨테이너 해제)을 엔티티 타입으로 반환한다.
     *
     * <p>판별은 호출의 {@code executable.getDeclaringType()}이 아니라 **수신 표현식의 정적 타입**
     * (예: {@code accountRepository} 필드의 선언 타입 {@code AccountRepository})으로 한다 — Spoon의
     * noClasspath 모드는 {@code findById}처럼 리포지토리 인터페이스가 재선언하지 않고 라이브러리
     * {@code JpaRepository}에서 그대로 상속받는 메서드에 대해 {@code getDeclaringType()}/{@code
     * getType()}(반환 타입) 모두 해소하지 못한다(실 SUT의 일반적 관례 — 커스텀 파인더를 재선언하지
     * 않는 순수 {@code interface AccountRepository extends JpaRepository<Account, String> {}}).
     * 반환 타입이 해소되면 그대로 컨테이너 해제해 쓰고, 해소되지 않으면 리시버 타입의
     * {@code JpaRepository<Entity, Id>} 제네릭 인자에서 엔티티 타입을 역산한다
     * ({@link #jpaRepositoryEntityTypeArg}).
     */
    private Optional<CtTypeReference<?>> repositoryEntityType(CtExpression<?> expr, CtModel model) {
        if (expr instanceof CtInvocation<?> inv) {
            String name = inv.getExecutable().getSimpleName();
            if (isOptionalPassThrough(name)) {
                return inv.getTarget() == null
                        ? Optional.empty()
                        : repositoryEntityType(inv.getTarget(), model);
            }
            CtExpression<?> target = inv.getTarget();
            CtTypeReference<?> targetType = target == null ? null : target.getType();
            if (targetType == null || !isRepositoryType(targetType, model)) {
                return Optional.empty();
            }
            CtTypeReference<?> returnType = inv.getExecutable().getType();
            if (returnType != null) {
                return Optional.of(unwrapContainerType(returnType));
            }
            return jpaRepositoryEntityTypeArg(targetType, model);
        }
        if (expr instanceof CtVariableRead<?> vr
                && vr.getVariable().getDeclaration() instanceof CtLocalVariable<?> localVar
                && localVar.getDefaultExpression() != null) {
            return repositoryEntityType(localVar.getDefaultExpression(), model);
        }
        return Optional.empty();
    }

    /**
     * {@code repoType}(예: {@code AccountRepository})의 {@code JpaRepository<Entity, Id>} 상위
     * 인터페이스 선언에서 첫 제네릭 인자(엔티티 타입)를 역산한다. {@code findById} 등 리포지토리가
     * 재선언하지 않고 상속만 하는 메서드는 noClasspath에서 반환 타입이 해소되지 않으므로
     * ({@link #repositoryEntityType} 참고), 리시버 타입 선언 자체(소스에 있으므로 항상 해소 가능)의
     * 상위 인터페이스 제네릭 인자로 대체한다.
     */
    private static Optional<CtTypeReference<?>> jpaRepositoryEntityTypeArg(CtTypeReference<?> repoType, CtModel model) {
        CtType<?> repoDecl = resolveType(model, repoType.getQualifiedName());
        if (repoDecl == null) {
            return Optional.empty();
        }
        for (CtTypeReference<?> superIntf : repoDecl.getSuperInterfaces()) {
            if ("JpaRepository".equals(superIntf.getSimpleName()) && !superIntf.getActualTypeArguments().isEmpty()) {
                return Optional.of(superIntf.getActualTypeArguments().get(0));
            }
        }
        return Optional.empty();
    }

    private static boolean isOptionalPassThrough(String methodName) {
        return "orElseThrow".equals(methodName) || "orElseGet".equals(methodName)
                || "orElse".equals(methodName) || "get".equals(methodName);
    }

    /** 선언 타입이 {@code JpaRepository} 상속, {@code @Repository} 어노테이션, 또는 알려진 MyBatis mapper FQN인지. */
    private boolean isRepositoryType(CtTypeReference<?> typeRef, CtModel model) {
        String fqn = typeRef.getQualifiedName() == null ? null : typeRef.getQualifiedName().replace('$', '.');
        if (fqn != null && mapperInterfaceFqns.contains(fqn)) {
            return true;
        }
        CtType<?> type = resolveType(model, fqn);
        if (type == null) {
            return false;
        }
        boolean annotatedRepository = type.getAnnotations().stream()
                .anyMatch(a -> "Repository".equals(a.getAnnotationType().getSimpleName()));
        if (annotatedRepository) {
            return true;
        }
        return type.getSuperInterfaces().stream()
                .anyMatch(i -> "JpaRepository".equals(i.getSimpleName()));
    }

    /** {@code Optional<T>}/{@code List<T>} 등 단일 타입 인자 컨테이너면 T를, 아니면 그대로 반환. */
    private static CtTypeReference<?> unwrapContainerType(CtTypeReference<?> type) {
        if (type == null) {
            return null;
        }
        Set<String> containers = Set.of("Optional", "List", "Collection", "Iterable", "Set", "Page", "Stream");
        if (containers.contains(type.getSimpleName()) && type.getActualTypeArguments().size() == 1) {
            return type.getActualTypeArguments().get(0);
        }
        return type;
    }

    /**
     * 표현식의 루트가 핸들러 자신의 파라미터이면 그로부터의 getter 체인 세그먼트 목록을 반환한다
     * (빈 리스트 = 파라미터 자체가 직접 사용됨, 예: path variable). 루트가 파라미터가 아니거나
     * getter 관례를 따르지 않는 메서드를 경유하면 empty.
     *
     * <p>REQ-034 — DTO 중첩 재귀 전개: 체인 중간에 {@code List.get(index)}/{@code Map.get(key)}
     * 호출이 끼어 있어도 계속 재귀한다. List는 인덱스 인자가 **리터럴 정수 0일 때만** 대표원소(첫
     * 원소) 규약으로 세그먼트를 추가하지 않고 수렴한다(bracket 없이 부모 경로 그대로 이어감, 예:
     * {@code items.get(0).qty()} → "items.qty"). Map은 키 인자가 문자열 리터럴이면 그대로 세그먼트로
     * 추가한다(예: {@code configs.get("region")} → "configs.region"). List 인덱스가 0이 아닌
     * 리터럴이거나 변수(예: {@code get(1)}, {@code get(i)})이면, 또는 Map 키가 리터럴이 아니면
     * — 어느 원소/키를 가리키는지 정적으로 대표원소 규약으로 수렴시킬 수 없으므로 — empty로
     * 강등한다(대칭적 처리: downstream {@code InputMutator.applyToBody}가 대표원소 {@code
     * arr.get(0)}만 변이하므로, 그 밖의 인덱스를 대표원소 경로로 태깅하면 provenance와 실제 변이
     * 대상이 어긋난다).
     */
    private Optional<List<String>> getterSegments(CtExpression<?> expr, Set<CtParameter<?>> handlerParams,
                                                   CtModel model) {
        if (expr instanceof CtVariableRead<?> vr
                && vr.getVariable().getDeclaration() instanceof CtParameter<?> param
                && handlerParams.contains(param)) {
            return Optional.of(new ArrayList<>());
        }
        if (expr instanceof CtInvocation<?> inv && inv.getTarget() != null) {
            Optional<Optional<String>> collectionSegment = collectionElementSegment(inv);
            if (collectionSegment.isPresent()) {
                Optional<String> mapKey = collectionSegment.get();
                return getterSegments(inv.getTarget(), handlerParams, model).map(segs -> {
                    mapKey.ifPresent(segs::add);
                    return segs;
                });
            }
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

    /**
     * {@code inv}가 List 대표원소(인덱스 리터럴 0) 또는 Map 리터럴 키 접근({@code .get(...)}, 단일
     * 인자)이면 채택 여부를 바깥쪽 {@code Optional}로, 추가할 세그먼트(Map 키만 존재, List는 empty —
     * 세그먼트 없이 부모 경로를 그대로 이어감)를 안쪽 {@code Optional}로 반환한다. {@code .get(...)}이
     * 아니거나 대상이 List/Map 어느 쪽도 아니면(예: 일반 사용자 정의 {@code get()} 메서드) empty(채택
     * 안 함). List인데 인덱스 인자가 리터럴 정수 0이 아니면(예: {@code get(1)}, {@code get(i)}) —
     * downstream {@code InputMutator.applyToBody}가 대표원소 {@code arr.get(0)}만 변이하므로, 그 밖의
     * 인덱스를 대표원소 경로로 태깅하면 provenance와 실제 변이 대상이 어긋난다 — empty(채택 안 함).
     * Map인데 키 인자가 문자열 리터럴이 아니면(동적 키라 정적으로 dot-path를 알 수 없음) 마찬가지로
     * empty(채택 안 함 — 호출부에서 {@link #getterFieldName} 경로로 폴백해 결국 UNKNOWN으로 강등된다).
     */
    private static Optional<Optional<String>> collectionElementSegment(CtInvocation<?> inv) {
        if (!"get".equals(inv.getExecutable().getSimpleName()) || inv.getArguments().size() != 1) {
            return Optional.empty();
        }
        CtTypeReference<?> targetType = inv.getTarget().getType();
        if (targetType == null) {
            return Optional.empty();
        }
        CtExpression<?> indexOrKey = inv.getArguments().get(0);
        String simpleName = targetType.getSimpleName();
        if (LIST_LIKE_TYPES.contains(simpleName)) {
            return isLiteralZero(indexOrKey) ? Optional.of(Optional.empty()) : Optional.empty();
        }
        if (MAP_LIKE_TYPES.contains(simpleName)) {
            String key = literalStringArg(indexOrKey);
            return key == null ? Optional.empty() : Optional.of(Optional.of(key));
        }
        return Optional.empty();
    }

    private static boolean isLiteralZero(CtExpression<?> arg) {
        return arg instanceof CtLiteral<?> literal && literal.getValue() instanceof Integer i && i == 0;
    }

    private static String literalStringArg(CtExpression<?> arg) {
        return arg instanceof CtLiteral<?> literal && literal.getValue() instanceof String s ? s : null;
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

package io.graphrag.builder.index;

import io.graphrag.model.Endpoint;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLambda;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.visitor.filter.TypeFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

/**
 * Spring Cloud Gateway Java DSL(RouteLocatorBuilder) 라우트 인덱싱.
 *
 * <p>{@code b.routes().route("id", r -> r.path(...).filters(...).uri(...)).build()} 패턴을
 * 정적으로 파싱하여 {@link Endpoint} 목록을 반환한다.
 *
 * <p>{@link io.graphrag.model.Endpoint#path()}는 게이트웨이 predicate 경로(요청 식별자)를
 * 그대로 저장한다 — 필터 변환(stripPrefix 등)을 적용하지 않는다.
 *
 * <p>지원 필터(프록시 기본 동작을 방해하지 않는 것): {@code stripPrefix(n)},
 * {@code rewritePath(...)}, {@code setPath(...)}, {@code addRequestHeader(...)},
 * {@code addResponseHeader(...)}. 미지원 필터가 감지되면 해당 라우트를 결과에서 제외하고
 * 경고 로그를 남긴다.
 */
public class GatewayRouteIndexer {

    private static final Logger log = LoggerFactory.getLogger(GatewayRouteIndexer.class);

    /**
     * 프록시 smoke test를 방해하지 않는 필터 메서드명.
     * 이 목록에 속하면 지원(라우트 인덱싱), 속하지 않으면 미지원(라우트 제외).
     */
    private static final Set<String> SUPPORTED_FILTERS = Set.of(
            "stripPrefix", "rewritePath", "setPath",
            "addRequestHeader", "addResponseHeader");

    public IndexResult index(Path sutSrcDir) {
        return index(SharedSpoonModel.build(sutSrcDir));
    }

    public IndexResult index(CtModel model) {
        List<Endpoint> endpoints = new ArrayList<>();

        for (CtType<?> type : model.getAllTypes()) {
            for (CtMethod<?> method : type.getMethods()) {
                // 메서드 바디에서 직접(최상위) .route(...) 호출만 수집 — 람다 내부 중첩 route는 제외
                List<CtInvocation<?>> routeCalls = collectDirectRouteCalls(method);
                for (CtInvocation<?> routeCall : routeCalls) {
                    endpoints.addAll(parseRouteCall(routeCall, type, method));
                }
            }
        }

        endpoints.sort(Comparator.comparing(Endpoint::id));
        return new IndexResult(endpoints, new HashMap<>(), Set.of());
    }

    /**
     * 메서드 바디에서 최상위 .route(...) 호출 목록을 반환한다.
     * 람다 내부에 위치한 .route(...) 호출은 포함하지 않는다 (중첩 제외).
     */
    private static List<CtInvocation<?>> collectDirectRouteCalls(CtMethod<?> method) {
        List<CtInvocation<?>> result = new ArrayList<>();
        if (method.getBody() == null) {
            return result;
        }
        // 메서드 바디에 속한 모든 CtInvocation을 가져온 뒤,
        // 조상 중 CtLambda가 있는 것을 제외한다 (람다 내부 = 중첩).
        for (CtInvocation<?> inv : method.getBody().getElements(new TypeFilter<>(CtInvocation.class))) {
            if (!"route".equals(inv.getExecutable().getSimpleName())) {
                continue;
            }
            if (isInsideLambda(inv)) {
                continue;
            }
            result.add(inv);
        }
        return result;
    }

    /** invocation의 조상 중 CtLambda가 있으면 람다 내부로 간주한다. */
    private static boolean isInsideLambda(CtInvocation<?> inv) {
        var parent = inv.getParent();
        while (parent != null) {
            if (parent instanceof CtLambda<?>) {
                return true;
            }
            if (parent instanceof CtMethod<?>) {
                break;
            }
            parent = parent.getParent();
        }
        return false;
    }

    /**
     * .route(...) 호출 하나를 파싱하여 Endpoint 목록을 반환한다.
     * 미지원 필터 감지 시 빈 목록 반환 (해당 라우트 제외).
     * path() varargs 지원: 여러 경로 패턴이 있으면 각각 별도 Endpoint로 반환한다.
     *
     * <p>호출 형태:
     * <ul>
     *   <li>1-arg: {@code .route(r -> r.path(...).uri(...))}
     *   <li>2-arg: {@code .route("id", r -> r.path(...).uri(...))}
     * </ul>
     * 마지막 인자가 항상 Predicate 람다다.
     */
    private static List<Endpoint> parseRouteCall(CtInvocation<?> routeCall,
            CtType<?> declaringType, CtMethod<?> declaringMethod) {
        List<?> args = routeCall.getArguments();
        if (args.isEmpty()) {
            return List.of();
        }
        // 마지막 인자가 람다여야 한다
        Object lastArg = args.get(args.size() - 1);
        if (!(lastArg instanceof CtLambda<?> routeLambda)) {
            return List.of();
        }

        // 라우트 람다 직속(nearest-enclosing lambda == routeLambda) CtInvocation만 수집.
        // getElements()는 중첩 람다까지 포함하는 DFS 전체 탐색이므로, 그 결과를
        // 부모 람다가 routeLambda인 것만 남기도록 필터링한다.
        // Spoon의 getElements()는 raw List<CtInvocation>을 반환하므로 명시적으로 캐스팅한다.
        @SuppressWarnings("unchecked")
        List<CtInvocation<?>> lambdaInvocations =
                (List<CtInvocation<?>>) (List<?>) routeLambda
                        .getElements(new TypeFilter<>(CtInvocation.class))
                        .stream()
                        .filter(inv -> ((CtInvocation<?>) inv).getParent(CtLambda.class) == routeLambda)
                        .toList();

        List<String> paths = extractPaths(lambdaInvocations);
        String targetUri = extractUri(lambdaInvocations);
        if (paths.isEmpty() || targetUri == null) {
            log.warn("GatewayRouteIndexer: path 또는 uri를 찾을 수 없어 라우트를 건너뜁니다.");
            return List.of();
        }

        // filters(...) 호출 처리
        CtInvocation<?> filtersCall = lambdaInvocations.stream()
                .filter(inv -> "filters".equals(inv.getExecutable().getSimpleName()))
                .findFirst()
                .orElse(null);

        if (filtersCall != null) {
            // filters()의 인자도 람다 — 그 안에서 직속 필터 메서드 호출 목록을 수집.
            // 마찬가지로 nearest-enclosing lambda == filterLambda인 것만 남긴다.
            // 이렇게 하면 circuitBreaker(c -> c.setName("x")) 같은 중첩 config 람다 안의
            // setName 등이 미지원 필터로 오인되는 것을 방지한다.
            List<?> filterArgs = filtersCall.getArguments();
            if (!filterArgs.isEmpty() && filterArgs.get(0) instanceof CtLambda<?> filterLambda) {
                @SuppressWarnings("unchecked")
                List<CtInvocation<?>> filterInvocations =
                        (List<CtInvocation<?>>) (List<?>) filterLambda
                                .getElements(new TypeFilter<>(CtInvocation.class))
                                .stream()
                                .filter(inv -> ((CtInvocation<?>) inv).getParent(CtLambda.class) == filterLambda)
                                .toList();
                // 미지원 필터 감지 — 지원 판단만 하고 path는 변환하지 않는다
                for (CtInvocation<?> filterInv : filterInvocations) {
                    String filterName = filterInv.getExecutable().getSimpleName();
                    if (!SUPPORTED_FILTERS.contains(filterName)) {
                        log.warn("GatewayRouteIndexer: 미지원 필터 '{}' 감지 — 라우트 '{}' 제외",
                                filterName, paths.get(0));
                        return List.of();
                    }
                }
                // path는 predicate 경로(요청 식별자)를 그대로 유지 — 필터 변환 미적용
            } else if (!filterArgs.isEmpty()) {
                // 비람다 filters() 인자 — 정적으로 분석 불가, 보수적으로 라우트 제외
                log.warn("GatewayRouteIndexer: filters() 인자가 람다가 아님 (분석 불가) — 라우트 '{}' 제외",
                        paths.get(0));
                return List.of();
            }
        }

        String handlerClass = declaringType.getQualifiedName().replace('$', '.');
        String handlerMethod = declaringMethod.getSimpleName();
        List<Endpoint> result = new ArrayList<>();
        for (String path : paths) {
            result.add(new Endpoint(
                    EndpointIds.of("GET", path),
                    "GET",
                    path,
                    handlerClass,
                    handlerMethod,
                    List.of(),
                    false,
                    targetUri));
        }
        return result;
    }

    /** 람다 내 invocation 목록에서 path(...) 의 모든 문자열 리터럴 인자를 추출한다 (varargs 지원). */
    private static List<String> extractPaths(List<CtInvocation<?>> invocations) {
        List<String> paths = new ArrayList<>();
        for (CtInvocation<?> inv : invocations) {
            if ("path".equals(inv.getExecutable().getSimpleName())) {
                for (Object arg : inv.getArguments()) {
                    if (arg instanceof CtLiteral<?> lit && lit.getValue() instanceof String value) {
                        paths.add(value);
                    }
                }
                break;  // path() 호출은 첫 번째 것만 처리 (중복 path() 호출은 비정상)
            }
        }
        return paths;
    }

    /** 람다 내 invocation 목록에서 uri(...) 문자열 리터럴을 추출한다. */
    private static String extractUri(List<CtInvocation<?>> invocations) {
        for (CtInvocation<?> inv : invocations) {
            if ("uri".equals(inv.getExecutable().getSimpleName())
                    && !inv.getArguments().isEmpty()
                    && inv.getArguments().get(0) instanceof CtLiteral<?> lit
                    && lit.getValue() instanceof String value) {
                return value;
            }
        }
        return null;
    }

}

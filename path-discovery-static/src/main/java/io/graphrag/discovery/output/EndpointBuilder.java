package io.graphrag.discovery.output;

import io.graphrag.discovery.DiscoveredHandler;
import io.graphrag.model.Endpoint;

import java.util.List;

/**
 * Lifts a {@link DiscoveredHandler} into a shared-model {@link Endpoint}.
 *
 * <p>The deliberately uninteresting bit: we always set {@code authRequired = false}
 * and an empty {@code requiredRoles}. Spring Security analysis would need an extra
 * AST pass over {@code WebSecurityConfigurer} chains, and even then it would miss
 * runtime-decided authorization — handled instead by the downstream
 * {@code scout-launcher} which can carry an auth header via {@code SampleInput.headers}.
 */
public final class EndpointBuilder {

    private EndpointBuilder() {}

    public static Endpoint build(DiscoveredHandler handler, String project) {
        return new Endpoint(
                handler.method().name() + ":" + handler.path(),
                handler.method(),
                handler.path(),
                project,
                handler.handlerClass(),
                handler.handlerMethod(),
                false,
                List.of());
    }
}

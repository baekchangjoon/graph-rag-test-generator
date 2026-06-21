package io.graphrag.sample.gateway;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Gateway route declarations — parsed statically by GatewayRouteIndexer (Spoon).
 *
 * <p>This bean is only active under the "indexer" Spring profile and is NEVER active
 * at normal runtime. The static source is parsed by the builder's Spoon-based indexer
 * to extract the route predicate path ("/api/v1/orders/**") and target URI literal
 * ("http://orders-service") without running the application.
 *
 * <p>At runtime, the route is configured via application.yml
 * (spring.cloud.gateway.server.webflux.routes) with ORDERS_URI env var injected by
 * the builder to point at the WireMock downstream stub.
 */
@Profile("indexer")
@Configuration
public class GatewayConfig {

    @Bean
    public RouteLocator routes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("orders", r -> r.path("/api/v1/orders/**").uri("http://orders-service"))
                .build();
    }
}

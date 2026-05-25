package io.graphrag.model;

import java.util.List;
import java.util.Objects;

/**
 * SUT가 제공하는 REST endpoint.
 *
 * <p>id 컨벤션: "{METHOD}:{path}" (예: "POST:/api/orders")
 *
 * @param requiredRoles 인증 시 필요한 역할 목록. 인증 불필요 endpoint면 빈 리스트.
 */
public record Endpoint(
        String id,
        HttpMethod method,
        String path,
        String project,
        String handlerClass,
        String handlerMethod,
        boolean authRequired,
        List<String> requiredRoles) {

    public Endpoint {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(handlerClass, "handlerClass");
        Objects.requireNonNull(handlerMethod, "handlerMethod");
        requiredRoles = List.copyOf(Objects.requireNonNullElse(requiredRoles, List.of()));
    }
}

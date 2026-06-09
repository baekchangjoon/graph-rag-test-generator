package io.graphrag.model;

import java.util.List;

/** HTTP endpoint 사실. 구조 인덱싱(L1)의 산출물. */
public record Endpoint(
        String id,
        String httpMethod,
        String path,
        String handlerClass,
        String handlerMethod,
        List<EndpointParam> params,
        boolean authRequired) {
}

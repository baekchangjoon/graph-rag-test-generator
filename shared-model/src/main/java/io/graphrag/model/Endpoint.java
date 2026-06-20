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
        boolean authRequired,
        String targetUri) {

    /** 하위 호환 생성자: 기존 7-arg 호출 사이트를 그대로 유지하고 targetUri를 null로 기본 설정한다. */
    public Endpoint(String id, String httpMethod, String path, String handlerClass,
                    String handlerMethod, List<EndpointParam> params, boolean authRequired) {
        this(id, httpMethod, path, handlerClass, handlerMethod, params, authRequired, null);
    }
}

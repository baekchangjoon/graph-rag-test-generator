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
        String targetUri,
        List<String> errorMessageLiterals) {

    /** 구버전 그래프(errorMessageLiterals 부재)와의 후방 호환: 누락 필드를 빈 값으로 정규화. */
    public Endpoint {
        errorMessageLiterals = errorMessageLiterals == null ? List.of() : errorMessageLiterals;
    }

    /** 하위 호환 생성자(구 canonical, 8-arg): errorMessageLiterals를 빈 목록으로 기본 설정한다. */
    public Endpoint(String id, String httpMethod, String path, String handlerClass,
                    String handlerMethod, List<EndpointParam> params, boolean authRequired,
                    String targetUri) {
        this(id, httpMethod, path, handlerClass, handlerMethod, params, authRequired, targetUri, List.of());
    }

    /** 하위 호환 생성자: 기존 7-arg 호출 사이트를 그대로 유지하고 targetUri를 null로 기본 설정한다. */
    public Endpoint(String id, String httpMethod, String path, String handlerClass,
                    String handlerMethod, List<EndpointParam> params, boolean authRequired) {
        this(id, httpMethod, path, handlerClass, handlerMethod, params, authRequired, null, List.of());
    }
}

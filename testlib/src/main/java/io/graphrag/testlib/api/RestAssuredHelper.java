package io.graphrag.testlib.api;

import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;

/** baseUri + baggage 헤더(test-id 격리)가 미리 구성된 RestAssured 진입점. */
public final class RestAssuredHelper {

    private final String baseUri;
    private final String testId;
    private final AuthClient auth;
    private final String headerName;
    private final String scheme;
    private final String username;
    private final String password;
    private final io.graphrag.model.RequestHeaders extraHeaders;

    RestAssuredHelper(String baseUri, String testId, AuthClient auth,
                      String headerName, String scheme, String username, String password,
                      io.graphrag.model.RequestHeaders extraHeaders) {
        this.baseUri = baseUri;
        this.testId = testId;
        this.auth = auth;
        this.headerName = headerName;
        this.scheme = scheme;
        this.username = username;
        this.password = password;
        this.extraHeaders = extraHeaders;
    }

    public java.util.Map<String, String> customHeaders(java.time.Instant now) {
        return extraHeaders == null ? java.util.Map.of() : extraHeaders.resolved(now);
    }

    public RequestSpecification given() {
        RequestSpecification spec = RestAssured.given()
                .baseUri(baseUri)
                .header("baggage", baggageHeaderValue());
        customHeaders(java.time.Instant.now()).forEach(spec::header);
        return spec;
    }

    public RequestSpecification authenticated() {
        return given().header(headerName, scheme + " " + auth.login(username, password));
    }

    public String baggageHeaderValue() {
        return "test-id=" + testId;
    }
}

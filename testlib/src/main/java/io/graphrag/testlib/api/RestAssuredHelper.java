package io.graphrag.testlib.api;

import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;

/** baseUri + baggage 헤더(test-id 격리)가 미리 구성된 RestAssured 진입점. */
public final class RestAssuredHelper {

    private final String baseUri;
    private final String testId;

    RestAssuredHelper(String baseUri, String testId) {
        this.baseUri = baseUri;
        this.testId = testId;
    }

    public RequestSpecification given() {
        return RestAssured.given()
                .baseUri(baseUri)
                .header("baggage", baggageHeaderValue());
    }

    public String baggageHeaderValue() {
        return "test-id=" + testId;
    }
}

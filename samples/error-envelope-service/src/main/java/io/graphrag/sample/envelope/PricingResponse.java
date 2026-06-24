package io.graphrag.sample.envelope;

/** 외부 가격 서비스 응답 DTO. errorCode/errorDetail 이 있으면 오류 응답. */
public record PricingResponse(String errorCode, String errorDetail, Integer amount) {
}

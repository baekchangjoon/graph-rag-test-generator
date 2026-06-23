package io.graphrag.sample.orders;

/** 외부 재고 시스템이 반환하는 이행 모드. 응답 enum 분기 검증용. */
public enum FulfillmentMode {
    STANDARD,
    EXPRESS_ONLY,
    BACKORDER
}

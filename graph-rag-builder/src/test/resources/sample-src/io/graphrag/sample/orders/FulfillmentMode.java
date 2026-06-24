package io.graphrag.sample.orders;

/** 외부 재고 시스템이 반환하는 이행 모드 (Spoon 단위 테스트 픽스처용). */
public enum FulfillmentMode {
    STANDARD,
    EXPRESS_ONLY,
    BACKORDER
}

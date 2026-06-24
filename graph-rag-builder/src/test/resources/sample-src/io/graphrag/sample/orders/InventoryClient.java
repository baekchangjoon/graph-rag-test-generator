package io.graphrag.sample.orders;

/** 외부 재고 시스템 클라이언트 (Spoon 단위 테스트 픽스처용). */
public class InventoryClient {

    public record InventoryResponse(int available, FulfillmentMode mode, String region) {
    }

    public InventoryResponse check(String type) {
        return null; // 픽스처 전용 — 런타임 미사용
    }
}

package io.graphrag.sample.orders;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** 외부 재고 시스템 클라이언트. URL은 env(EXTERNAL_INVENTORY_URL)로 주입. */
@Component
public class InventoryClient {

    public record InventoryResponse(Integer available, FulfillmentMode mode, String region) {
    }

    private final RestTemplate rest = new RestTemplate();
    private final String baseUrl;

    public InventoryClient(@Value("${external.inventory.url:}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public InventoryResponse check(String type) {
        return rest.getForObject(baseUrl + "/inventory/stock?type=" + type,
                InventoryResponse.class);
    }
}

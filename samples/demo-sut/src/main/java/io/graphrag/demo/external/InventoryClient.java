package io.graphrag.demo.external;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 외부 재고 시스템 클라이언트. Phase 2 E2E 검증용.
 *
 * <p>실 운영에서는 inventory 서비스의 stock API를 호출. 분석/테스트 환경에서는
 * WireMock으로 redirect됨.
 */
@Component
public class InventoryClient {

    private final RestClient client;

    public InventoryClient(@Value("${external.inventory.url:http://localhost:9091}") String baseUrl) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** 특정 type의 가용 재고 수량 조회. 호출 실패 시 0 (보수적). */
    public int availableForType(String type) {
        try {
            StockResponse resp = client.get()
                    .uri(uriBuilder -> uriBuilder.path("/inventory/stock").queryParam("type", type).build())
                    .retrieve()
                    .body(StockResponse.class);
            return resp == null ? 0 : resp.available();
        } catch (Exception ex) {
            return 0;
        }
    }

    public record StockResponse(int available) {}
}

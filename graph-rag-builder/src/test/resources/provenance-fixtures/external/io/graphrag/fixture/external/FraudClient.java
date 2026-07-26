package io.graphrag.fixture.external;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * ProvenanceIndexerIT 픽스처 — 실제 SUT(samples/order-service의 FraudClient) 관례를 그대로
 * 미러링한다: RestTemplate로 감싼 외부 클라이언트 + record 응답 DTO(canonical accessor로 필드
 * 접근, get/is 접두사 없음).
 */
@Component
public class FraudClient {

    public record FraudResult(String status) {
    }

    private final RestTemplate rest = new RestTemplate();

    public FraudResult check(String accountId, long amount) {
        return rest.postForObject("/fraud/check",
                Map.of("accountId", accountId, "amount", amount), FraudResult.class);
    }
}

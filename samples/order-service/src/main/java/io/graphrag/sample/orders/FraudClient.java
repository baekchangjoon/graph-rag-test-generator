package io.graphrag.sample.orders;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/** 외부 이상거래탐지(fraud) 시스템 클라이언트. URL은 env(EXTERNAL_FRAUD_URL)로 주입. */
@Component
public class FraudClient {

    public record FraudResult(String status) {
    }

    private final RestTemplate rest = new RestTemplate();
    private final String baseUrl;

    public FraudClient(@Value("${external.fraud.url:}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public FraudResult check(String accountId, long amount) {
        return rest.postForObject(baseUrl + "/fraud/check",
                Map.of("accountId", accountId, "amount", amount), FraudResult.class);
    }
}

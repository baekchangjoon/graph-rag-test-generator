package io.graphrag.sample.envelope;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** 외부 가격 시스템 클라이언트. URL 은 env(EXTERNAL_PRICING_URL)로 주입. */
@Component
public class PricingClient {

    private final RestTemplate rest = new RestTemplate();
    private final String baseUrl;

    public PricingClient(@Value("${external.pricing.url:}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public PricingResponse quote(Long itemId) {
        return rest.getForObject(baseUrl + "/pricing/quote?itemId=" + itemId, PricingResponse.class);
    }
}

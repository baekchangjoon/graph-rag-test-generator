package io.graphrag.fixture.multiimpl;

import org.springframework.stereotype.Component;

/** ProvenanceIndexerIT 픽스처 — PaymentGateway의 구현체 1/2(REQ-003 MULTI_IMPL 대상). */
@Component
public class StripeGateway implements PaymentGateway {

    @Override
    public String charge(long amount) {
        return "OK";
    }
}

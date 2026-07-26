package io.graphrag.fixture.multiimpl;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * ProvenanceIndexerIT 픽스처 — 가드에서 호출하는 {@code gateway.charge(...)}의 선언 타입
 * (PaymentGateway)이 인터페이스이고 모델 내 구현체가 2개(StripeGateway/PaypalGateway)이므로,
 * 그 피연산자는 {@code Origin.UNKNOWN}으로 남고 리포트의 unresolved 배열에
 * {@code reason=MULTI_IMPL, targetType=PaymentGateway} 항목이 표면화되어야 한다(REQ-003).
 */
@RestController
@RequestMapping("/api/multiimpl")
public class MultiImplController {

    public record ChargeRequest(long amount) {
    }

    private final PaymentGateway gateway;

    public MultiImplController(PaymentGateway gateway) {
        this.gateway = gateway;
    }

    @PostMapping
    public String create(@RequestBody ChargeRequest req) {
        if (!"OK".equals(gateway.charge(req.amount()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "charge failed");
        }
        return "OK";
    }
}

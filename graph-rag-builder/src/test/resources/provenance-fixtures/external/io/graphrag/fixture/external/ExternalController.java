package io.graphrag.fixture.external;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * ProvenanceIndexerIT 픽스처 — RestTemplate 기반 외부 클라이언트(FraudClient) 응답의 accessor
 * 체인이 {@code Origin.EXTERNAL_RESPONSE} + callSite("POST /fraud/check") + stubField("status")로
 * 태깅되는지 검증한다(REQ-001 EXTERNAL 부분). 실제 SUT(TransferController)와 동일하게 로컬 변수
 * (fraud)로 응답을 받아 record accessor({@code fraud.status()})를 가드 조건에서 비교한다.
 */
@RestController
@RequestMapping("/api/external")
public class ExternalController {

    public record CheckRequest(String accountId, long amount) {
    }

    private final FraudClient fraudClient;

    public ExternalController(FraudClient fraudClient) {
        this.fraudClient = fraudClient;
    }

    @PostMapping
    public String create(@RequestBody CheckRequest req) {
        FraudClient.FraudResult fraud = fraudClient.check(req.accountId(), req.amount());
        if (!"CLEAR".equals(fraud.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "fraud check failed");
        }
        return "OK";
    }
}

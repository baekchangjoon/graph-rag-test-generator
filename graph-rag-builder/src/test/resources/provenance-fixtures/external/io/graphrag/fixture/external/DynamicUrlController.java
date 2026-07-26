package io.graphrag.fixture.external;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * ProvenanceIndexerIT 픽스처 — {@code DynamicUrlClient.check}의 URL 인자가 정적 리터럴이 아니므로
 * (요청 body의 path 필드를 그대로 전달), callSite는 path literal을 추출하지 못하고
 * {@code "io.graphrag.fixture.external.DynamicUrlClient#check"}로 폴백해야 한다(REQ-003).
 */
@RestController
@RequestMapping("/api/external-dynamic")
public class DynamicUrlController {

    public record CheckRequest(String path, String accountId) {
    }

    private final DynamicUrlClient client;

    public DynamicUrlController(DynamicUrlClient client) {
        this.client = client;
    }

    @PostMapping
    public String create(@RequestBody CheckRequest req) {
        DynamicUrlClient.PriceResult result = client.check(req.path(), req.accountId());
        if (!"CLEAR".equals(result.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "check failed");
        }
        return "OK";
    }
}

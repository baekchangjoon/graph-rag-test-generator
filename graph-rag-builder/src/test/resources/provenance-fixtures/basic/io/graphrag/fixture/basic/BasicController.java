package io.graphrag.fixture.basic;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * ProvenanceIndexerIT 픽스처 — INPUT 태깅 기본 케이스.
 * {@code req.getAmount() < 1} 가드의 좌변 피연산자는 핸들러 파라미터(req)의 getter 체인이므로
 * Origin.INPUT + jsonPath="amount"로 태깅되어야 한다.
 */
@RestController
@RequestMapping("/api/basic")
public class BasicController {

    public static class CreateRequest {
        private final String userId;
        private final Integer amount;

        public CreateRequest(String userId, Integer amount) {
            this.userId = userId;
            this.amount = amount;
        }

        public String getUserId() {
            return userId;
        }

        public Integer getAmount() {
            return amount;
        }
    }

    private final BasicService service;

    public BasicController(BasicService service) {
        this.service = service;
    }

    @PostMapping
    public String create(@RequestBody CreateRequest req) {
        if (req.getAmount() < 1) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid amount");
        }
        return service.process(req.getAmount());
    }
}

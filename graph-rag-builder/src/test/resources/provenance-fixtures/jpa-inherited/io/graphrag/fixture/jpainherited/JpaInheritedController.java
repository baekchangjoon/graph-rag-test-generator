package io.graphrag.fixture.jpainherited;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * ProvenanceIndexerIT 픽스처 — 실 SUT(TransferController) 관례를 그대로 미러링: 리포지토리가
 * {@code findById}를 재선언하지 않아도 DB_READ로 태깅되어야 한다(REQ-004 확장 — noClasspath 상속
 * 메서드 회귀 방지).
 */
@RestController
@RequestMapping("/api/jpa-inherited")
public class JpaInheritedController {

    public record CheckBalanceRequest(String accountId, long amount) {
    }

    private final AccountRepository accountRepository;

    public JpaInheritedController(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @PostMapping
    public String create(@RequestBody CheckBalanceRequest req) {
        Account account = accountRepository.findById(req.accountId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "account not found"));
        if (account.getBalance() < req.amount()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "insufficient balance");
        }
        return "OK:" + account.getId();
    }
}

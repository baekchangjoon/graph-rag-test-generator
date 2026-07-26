package io.graphrag.fixture.jpaoverride;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * ProvenanceIndexerIT 픽스처 — repository에서 조회한 엔티티의 getter 체인이 DB_READ로 태깅되고,
 * {@code @Table}/{@code @Column} 오버라이드(fund_accounts/balance_amount)가 ValueRef.table/column에
 * 반영되는지 검증한다(REQ-004). 실제 SUT(TransferController)와 동일한 관례로, findById().orElseThrow()
 * 로 얻은 로컬 변수의 getter를 가드 조건에서 사용한다.
 */
@RestController
@RequestMapping("/api/jpa-override")
public class JpaOverrideController {

    public record CheckBalanceRequest(Long accountId, long amount) {
    }

    private final AccountRepository accountRepository;

    public JpaOverrideController(AccountRepository accountRepository) {
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

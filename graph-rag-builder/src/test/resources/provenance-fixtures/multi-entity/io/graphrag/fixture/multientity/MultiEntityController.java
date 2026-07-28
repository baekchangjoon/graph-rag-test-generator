package io.graphrag.fixture.multientity;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * ProvenanceIndexerIT/TripleSynthesizerIT 픽스처 — <b>서로 다른 엔티티를 두 번 조회</b>하는
 * 엔드포인트. 리포트 전체의 DB_READ 테이블이 2개가 되므로, 합성기가 "리포트 전역에 테이블이
 * 정확히 하나"라는 폴백에만 의존하면 두 존재 가드의 seed 배치가 통째로 skip된다.
 */
@RestController
@RequestMapping("/api/multi-entity")
public class MultiEntityController {

    public record TransferRequest(String userId, String accountId, long amount) {
    }

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;

    public MultiEntityController(UserRepository userRepository, AccountRepository accountRepository) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
    }

    @PostMapping
    public String create(@RequestBody TransferRequest req) {
        User user = userRepository.findById(req.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
        Account account = accountRepository.findById(req.accountId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "account not found"));
        if (account.getBalance() < req.amount()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "insufficient balance");
        }
        return "OK:" + user.getId() + ":" + account.getId();
    }
}

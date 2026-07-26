package io.graphrag.fixture.exists;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * ProvenanceIndexerIT 픽스처 — EXISTS 가드(Optional.orElseThrow) + record 기반 DTO.
 * 실제 SUT(TransferController/OrderController)와 동일하게 요청 DTO를 record로 선언한다:
 * record accessor(req.fromAccountId())는 get/is 접두사가 없으므로, getterFieldName의
 * record canonical accessor 인식(호출 메서드명 == record component명)이 검증 대상이다.
 * {@code accountRepository.findById(req.fromAccountId())} 뒤의 {@code .orElseThrow(...)}는
 * EXISTS 가드로 수집되어야 하고, 그 피연산자는 INPUT + jsonPath="fromAccountId"여야 한다.
 */
@RestController
@RequestMapping("/api/exists")
public class ExistsController {

    public record CreateTransferRequest(String fromAccountId, long amount) {
    }

    private final AccountRepository accountRepository;

    public ExistsController(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @PostMapping
    public String create(@RequestBody CreateTransferRequest req) {
        Account account = accountRepository.findById(req.fromAccountId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "account not found"));
        return "OK:" + account.getId();
    }
}

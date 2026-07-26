package io.graphrag.sample.orders;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class TransferController {

    public record CreateTransferRequest(String fromAccountId, long amount, String note, List<TransferItem> items) {}
    public record TransferItem(String sku, int qty) {}

    private final AccountRepository accountRepository;
    private final FraudClient fraudClient;

    public TransferController(AccountRepository accountRepository, FraudClient fraudClient) {
        this.accountRepository = accountRepository;
        this.fraudClient = fraudClient;
    }

    @PostMapping("/transfers")
    public ResponseEntity<?> create(@RequestBody CreateTransferRequest req) {
        Account account = accountRepository.findById(req.fromAccountId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "account not found"));
        if (account.getBalance() < req.amount()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "insufficient balance");
        }
        if (req.items() == null || req.items().isEmpty() || req.items().get(0).qty() <= 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid items");
        }
        FraudClient.FraudResult fraud = fraudClient.check(req.fromAccountId(), req.amount());
        if (!"CLEAR".equals(fraud.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "fraud check failed");
        }
        // 결정적 id (Random 금지)
        return ResponseEntity.status(201).body(Map.of("id", "TRF-" + req.fromAccountId(), "note", req.note()));
    }
}

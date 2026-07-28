package io.graphrag.fixture.existscustom;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ProvenanceIndexerIT 픽스처 — {@code ResponseStatusException}을 쓰지 않는 EXISTS 가드.
 *
 * <p>tainted-spring 벤치마크 조사에서 드러난 미인식 패턴 두 가지를 재현한다:
 * <ul>
 *   <li>{@code createWithCustomException} — 람다가 커스텀 도메인 예외를 <em>생성만</em> 한다
 *       (표현식 람다이므로 {@code CtThrow}가 없고, 타입도 ResponseStatusException이 아니다).</li>
 *   <li>{@code createWithNoArgOrElseThrow} — 인자 없는 {@code orElseThrow()}
 *       (JDK가 NoSuchElementException을 던진다).</li>
 * </ul>
 * 두 경우 모두 EXISTS 가드로 수집되고 피연산자가 INPUT + jsonPath로 태깅되어야 한다.
 */
@RestController
@RequestMapping("/api/exists-custom")
public class CustomExistsController {

    public record CreateTransferRequest(String fromAccountId, String toAccountId, long amount) {
    }

    private final AccountRepository accountRepository;

    public CustomExistsController(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @PostMapping("/custom")
    public String createWithCustomException(@RequestBody CreateTransferRequest req) {
        Account account = accountRepository.findById(req.fromAccountId())
                .orElseThrow(() -> new AccountNotFoundException(req.fromAccountId()));
        return "OK:" + account.getId();
    }

    @PostMapping("/noarg")
    public String createWithNoArgOrElseThrow(@RequestBody CreateTransferRequest req) {
        Account account = accountRepository.findById(req.toAccountId()).orElseThrow();
        return "OK:" + account.getId();
    }

    /**
     * 음성 케이스 — {@code orElseGet}은 기본값 폴백이라 실행 경로를 막지 않는다.
     * 값이 없어도 200으로 진행하므로 EXISTS 가드로 수집되면 안 된다.
     */
    @PostMapping("/fallback")
    public String createWithFallback(@RequestBody CreateTransferRequest req) {
        Account account = accountRepository.findById(req.fromAccountId())
                .orElseGet(() -> new Account("anonymous"));
        return "OK:" + account.getId();
    }
}

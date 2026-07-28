package io.graphrag.fixture.paramprop;

/** ProvenanceIndexerIT 픽스처 — 가드가 실제로 사는 서비스 계층. */
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /** 파라미터명이 핸들러와 다르다(accountId ≠ id/ownerId) — 이름 일치에 기대면 안 된다. */
    public String findOrThrow(String accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        return account.getId();
    }

    /** 파라미터를 그대로 비교하는 CtIf 가드 — 전파된 origin이 조건 분해에도 반영돼야 한다. */
    public String checkLimit(String accountId, long amount) {
        if (amount > 1000L) {
            throw new AccountNotFoundException(accountId);
        }
        return "OK";
    }
}

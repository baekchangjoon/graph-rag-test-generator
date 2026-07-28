package io.graphrag.fixture.paramprop;

/**
 * 커스텀 도메인 예외 — Spring의 {@code ResponseStatusException}을 쓰지 않고
 * 프로젝트 고유 예외를 던지는 실제 서비스(tainted-spring diary/community/mindgraph 등)의 관례.
 */
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(String accountId) {
        super("account not found: " + accountId);
    }
}

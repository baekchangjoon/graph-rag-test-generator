package io.graphrag.fixture.impldispatch;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ProvenanceIndexerIT 픽스처 — 가드가 <em>인터페이스 뒤</em>에 있는 경우.
 *
 * <p>기존 {@code multiimpl} 픽스처는 다중구현 호출이 가드 <em>조건 안</em>에 있어(피연산자)
 * MULTI_IMPL이 표면화되지만, 실서비스에서 흔한 형태는 핸들러가 인터페이스 메서드를 부르고
 * <em>가드가 그 구현체 안</em>에 있는 것이다. 이때 DFS는 본문 없는 인터페이스 메서드에 도달해
 * 아무 가드도 못 찾고, unresolved도 남기지 않아 리포트가 "깨끗한 엔드포인트"로 오인된다.
 */
@RestController
@RequestMapping("/api/dispatch")
public class DispatchController {

    public record LoginRequest(String provider, String providerToken) {
    }

    private final SocialVerifier socialVerifier;
    private final AuditService auditService;

    public DispatchController(SocialVerifier socialVerifier, AuditService auditService) {
        this.socialVerifier = socialVerifier;
        this.auditService = auditService;
    }

    /** 구현체 2개 — 어느 쪽이 호출될지 정적으로 정할 수 없다. 최소한 unresolved로 남아야 한다. */
    @PostMapping("/multi")
    public String loginViaMultiImpl(@RequestBody LoginRequest req) {
        return socialVerifier.verify(req.provider(), req.providerToken());
    }

    /** 구현체 1개 — 디스패치가 모호하지 않으므로 그 구현으로 내려가 가드를 봐야 한다. */
    @PostMapping("/single")
    public String loginViaSingleImpl(@RequestBody LoginRequest req) {
        return auditService.record(req.provider());
    }
}

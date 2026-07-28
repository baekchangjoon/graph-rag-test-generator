package io.graphrag.fixture.paramprop;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ProvenanceIndexerIT 픽스처 — 컨트롤러가 얇고 가드는 서비스 계층에 있는, 실제 서비스의
 * 지배적 구조(tainted-spring diary/community/mindgraph 전부 이 형태).
 *
 * <p>핸들러 파라미터가 서비스 메서드 인자로 넘어가면 피호출 메서드의 파라미터는 더 이상
 * 핸들러 파라미터가 아니므로, 인자↔파라미터 바인딩이 없으면 가드 피연산자가 UNKNOWN으로
 * 떨어진다. 그러면 가드를 인식해도 시드/입력 채널로 라우팅할 수 없다.
 */
@RestController
@RequestMapping("/api/prop")
public class PropagationController {

    public record CreateRequest(String ownerId, long amount) {
    }

    private final AccountService accountService;

    public PropagationController(AccountService accountService) {
        this.accountService = accountService;
    }

    /** @PathVariable → 서비스 파라미터 → EXISTS 가드. */
    @GetMapping("/{id}")
    public String get(@PathVariable String id) {
        return accountService.findOrThrow(id);
    }

    /** @RequestBody 필드 접근 → 서비스 파라미터 → EXISTS 가드. */
    @PostMapping
    public String create(@RequestBody CreateRequest req) {
        return accountService.findOrThrow(req.ownerId());
    }

    /** 서비스 계층의 CtIf 가드로 전파되는 경우. */
    @GetMapping("/{id}/limit")
    public String limit(@PathVariable String id, @RequestParam long amount) {
        return accountService.checkLimit(id, amount);
    }
}

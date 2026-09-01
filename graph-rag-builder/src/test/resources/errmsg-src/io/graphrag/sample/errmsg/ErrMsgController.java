package io.graphrag.sample.errmsg;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** ErrorMessageLiteralExtractor 테스트 픽스처 — REQ-D 메시지 리터럴 추출 패턴 모음. */
@RestController
@RequestMapping("/api/errmsg")
public class ErrMsgController {

    public record Req(Integer nights, String tier) {
    }

    @PostMapping
    public String create(@RequestBody Req req) {
        // 1. 순수 리터럴 reason
        if (req.nights() == null || req.nights() < 1 || req.nights() > 30) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "nights must be between 1 and 30");
        }
        // 2. 동일 클래스 1단계 헬퍼 안의 throw
        requireTier(req.tier());
        // 3. 다른 예외 타입은 추출 대상 아님
        if ("boom".equals(req.tier())) {
            throw new IllegalStateException("not a response status message");
        }
        return "ok";
    }

    @GetMapping("/{id}")
    public String get(@PathVariable Long id) {
        // 4. 연결식 reason — 리터럴 조각(" not found", 8자 이상)만 추출, 짧은 조각("bk ")은 제외
        if (id > 100) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "bk " + id + " not found");
        }
        return "ok";
    }

    private void requireTier(String tier) {
        if (tier == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "tier is required");
        }
    }
}

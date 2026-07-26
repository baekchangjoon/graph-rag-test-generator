package io.graphrag.fixture.derived;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * ProvenanceIndexerIT 픽스처 — INPUT 피연산자를 감싼 산술 파생식({@code req.getScore() * 2}) 전체가
 * 분해되지 않고 하나의 리프로 {@code Origin.DERIVED}(javaType 유지)로 태깅되는지 검증한다(REQ-032,
 * 태깅 절반 — concolic 해의 실제 배치는 synthesize-triple(C2) 범위).
 */
@RestController
@RequestMapping("/api/derived")
public class DerivedController {

    public static class ScoreRequest {
        private final Integer score;

        public ScoreRequest(Integer score) {
            this.score = score;
        }

        public Integer getScore() {
            return score;
        }
    }

    @PostMapping
    public String create(@RequestBody ScoreRequest req) {
        if (req.getScore() * 2 == 84) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "score threshold breached");
        }
        return "OK";
    }
}

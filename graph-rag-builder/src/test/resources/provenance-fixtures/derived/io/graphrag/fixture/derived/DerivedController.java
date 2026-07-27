package io.graphrag.fixture.derived;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * ProvenanceIndexerIT 픽스처 — INPUT 피연산자를 감싼 산술 파생식 전체가 분해되지 않고 하나의 리프로
 * {@code Origin.DERIVED}(javaType 유지 + {@code derivedFrom}=파생 루트 INPUT 필드 목록)로 태깅되는지
 * 검증한다(REQ-032).
 *
 * <ul>
 *   <li>{@code create} — 선형 단일필드 파생({@code req.getScore() * 2 == 84}). concolic이 풀 수 있어
 *       합성(C2)에서 body.score에 결정값이 배치되는 경로.</li>
 *   <li>{@code createNonlinear} — 비선형 다변수 파생({@code req.getScore() * req.getFactor() == 84}).
 *       concolic이 못 푸는 파생이므로 합성에서 두 루트 필드 모두 갭 마커가 되는 경로.</li>
 * </ul>
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

    public static class PairRequest {
        private final Integer score;
        private final Integer factor;

        public PairRequest(Integer score, Integer factor) {
            this.score = score;
            this.factor = factor;
        }

        public Integer getScore() {
            return score;
        }

        public Integer getFactor() {
            return factor;
        }
    }

    @PostMapping
    public String create(@RequestBody ScoreRequest req) {
        if (req.getScore() * 2 == 84) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "score threshold breached");
        }
        return "OK";
    }

    @PostMapping("/nonlinear")
    public String createNonlinear(@RequestBody PairRequest req) {
        if (req.getScore() * req.getFactor() == 84) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "product threshold breached");
        }
        return "OK";
    }
}

package io.graphrag.sample.bounds;

public class BoundsController {

    public record Req(Integer amount, Integer score) {
    }

    public String handle(Req req, String label) {
        if ("VIP".equals(label)) {          // 컨트롤러 계층 문자열 동치 (리터럴 좌변)
            return "vip";
        }
        if (req.amount() > 100) {
            return "big";
        }
        if (50 >= req.score()) {            // 리터럴 좌변 → flip되어 score <= 50
            return "low";
        }
        if (req.getAmount() == 7) {         // getter 형태 → amount로 정규화
            return "lucky";
        }
        if (req.amount() > req.score()) {   // 리터럴 없음 → 무시
            return "rel";
        }
        return "ok";
    }
}

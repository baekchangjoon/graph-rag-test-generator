package io.graphrag.sample.bounds;

public class BoundsService {

    // 서비스 계층 분기 조건 — 컨트롤러가 아닌 곳에서도 추출되어야 한다.
    public String classify(int quantity) {
        if (quantity >= 5) {
            return "bulk";
        }
        return "single";
    }
}

package io.graphrag.sample.orders;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 제약 지향 입력 생성 실증용 엔드포인트. 분기 조건이 입력 필드의 등치(==/equals)라서
 * generic boundary 변이(0/-1/large/empty/null)로는 절대 못 닿고, 정적분석으로 추출한
 * 리터럴(7, "gold")을 입력값으로 환류해야만 도달한다. (컨트롤러 계층)
 */
@RestController
@RequestMapping("/api/promo")
public class PromoController {

    public record PromoRequest(Integer score, String tier) {
    }

    public record PromoResponse(String result) {
    }

    private final PromoService promo;

    public PromoController(PromoService promo) {
        this.promo = promo;
    }

    @PostMapping
    public PromoResponse evaluate(@RequestBody PromoRequest request) {
        String prefix = "base";
        if (request.score() != null && request.score() == 7) {   // 컨트롤러 숫자 동치
            prefix = "lucky";
        }
        if ("gold".equals(request.tier())) {                     // 컨트롤러 문자열 동치
            prefix = prefix + "-gold";
        }
        return new PromoResponse(promo.classify(request.score(), request.tier(), prefix));
    }
}

package io.graphrag.sample.orders;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Pattern 게이트 + 도메인 접두 분기 — LLM 값 오라클이 도메인 그럴듯한 값을 만들어야만 도달하는
 * 깊은 분기(REQ-012). happy 합성("sample-couponCode")은 정규식 불충족 400, concolic은 정규식 미해결.
 */
@RestController
@RequestMapping("/api/coupons")
public class CouponController {

    public record RedeemRequest(
            @Pattern(regexp = "[A-Z]{4}-\\d{4}") String couponCode,
            @Min(1) int quantity) {
    }

    @PostMapping
    public String redeem(@Valid @RequestBody RedeemRequest req) {
        if (req.couponCode().startsWith("GOLD")) {
            return "gold-tier:" + req.quantity();   // 깊은 분기 — 유효 도메인 값에서만 도달
        }
        return "standard";
    }
}

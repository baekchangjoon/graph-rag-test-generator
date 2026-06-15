package io.graphrag.sample.orders;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * @Controller 폼 핸들러 회귀 가드(Stage: @Controller 폼 인덱싱). REST(@RestController)가 아닌 MVC 폼 제출 —
 * application/x-www-form-urlencoded 커맨드 객체 바인딩 + 명령형 가드 + redirect(템플릿 불요).
 * SecurityConfig는 CSRF disabled, /web/orders는 authRequired(탐색이 valid 토큰 주입 → 도달).
 */
@Controller
@RequestMapping("/web/orders")
public class OrderWebController {

    /** 폼 커맨드 객체(JavaBean — form-urlencoded 바인딩). */
    public static class OrderForm {
        private String customer;
        private Integer quantity;

        public String getCustomer() {
            return customer;
        }

        public void setCustomer(String customer) {
            this.customer = customer;
        }

        public Integer getQuantity() {
            return quantity;
        }

        public void setQuantity(Integer quantity) {
            this.quantity = quantity;
        }
    }

    @PostMapping
    public String submit(OrderForm form) {
        // 명령형 가드 — 양 arm 모두 redirect(302), 템플릿 불요. 빌더가 form-urlencoded로 탐색 시 양 arm 커버.
        if (form.getQuantity() == null || form.getQuantity() < 1 || form.getQuantity() > 100) {
            return "redirect:/web/orders/error";
        }
        return "redirect:/web/orders/ok";
    }
}

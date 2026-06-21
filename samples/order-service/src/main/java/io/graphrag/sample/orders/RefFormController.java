package io.graphrag.sample.orders;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 참조-name 폼(spec §5-2): 커맨드의 Color 필드는 ColorFormatter(name 조회)로 바인딩. 빌더가 colors 행의
 * name을 토큰으로 합성(color=<name>)해야 color != null. quantity[1,100] 경계 가드가 error arm을 함께 열어
 * 양 arm(branchesTaken≥2)을 보장 — color 미바인딩이면 항상 error arm(1개).
 */
@Controller
@RequestMapping("/web/ref")
public class RefFormController {

    public static class RefForm {
        private Color color;
        private Integer quantity;

        public Color getColor() {
            return color;
        }

        public void setColor(Color color) {
            this.color = color;
        }

        public Integer getQuantity() {
            return quantity;
        }

        public void setQuantity(Integer quantity) {
            this.quantity = quantity;
        }
    }

    @PostMapping
    public String submit(RefForm form) {
        if (form.getColor() == null
                || form.getQuantity() == null || form.getQuantity() < 1 || form.getQuantity() > 100) {
            return "redirect:/web/ref/error";
        }
        return "redirect:/web/ref/ok";
    }
}

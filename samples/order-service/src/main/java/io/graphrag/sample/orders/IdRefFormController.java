package io.graphrag.sample.orders;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 참조-id 폼(spec §5-3): 커맨드의 Brand 필드는 BrandConverter(PK 조회)로 바인딩. name 토큰(label)은 실패하고
 * PK 토큰(id)만 성공 → 러너의 PK backtrack 후보로 brand != null. quantity[1,100] 경계가 양 arm 보장.
 */
@Controller
@RequestMapping("/web/idref")
public class IdRefFormController {

    public static class IdRefForm {
        private Brand brand;
        private Integer quantity;

        public Brand getBrand() {
            return brand;
        }

        public void setBrand(Brand brand) {
            this.brand = brand;
        }

        public Integer getQuantity() {
            return quantity;
        }

        public void setQuantity(Integer quantity) {
            this.quantity = quantity;
        }
    }

    @PostMapping
    public String submit(IdRefForm form) {
        if (form.getBrand() == null
                || form.getQuantity() == null || form.getQuantity() < 1 || form.getQuantity() > 100) {
            return "redirect:/web/idref/error";
        }
        return "redirect:/web/idref/ok";
    }
}

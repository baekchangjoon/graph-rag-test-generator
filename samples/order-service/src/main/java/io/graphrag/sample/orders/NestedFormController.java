package io.graphrag.sample.orders;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 중첩 폼(spec §5-4): 커맨드의 Address 필드는 컨버터 없는 POJO → address.city/address.street 점-경로 바인딩.
 * 빌더가 평면 점-경로 스칼라로 합성해야 address.getCity() != null. quantity[1,100] 경계가 양 arm 보장 —
 * 중첩 ObjectNode를 두면 formEncode가 드롭 → address null → 항상 error arm.
 */
@Controller
@RequestMapping("/web/nested")
public class NestedFormController {

    public static class NestedForm {
        private Address address;
        private Integer quantity;

        public Address getAddress() {
            return address;
        }

        public void setAddress(Address address) {
            this.address = address;
        }

        public Integer getQuantity() {
            return quantity;
        }

        public void setQuantity(Integer quantity) {
            this.quantity = quantity;
        }
    }

    @PostMapping
    public String submit(NestedForm form) {
        if (form.getAddress() == null || form.getAddress().getCity() == null
                || form.getQuantity() == null || form.getQuantity() < 1 || form.getQuantity() > 100) {
            return "redirect:/web/nested/error";
        }
        return "redirect:/web/nested/ok";
    }
}

package io.graphrag.sample.orders;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * @Controller 폼 회귀 가드(작업 a: 클래스-레벨 path 변수 @ModelAttribute 역추출). petclinic
 * PetController 패턴 재현 — 클래스-레벨 {userId}가 핸들러 파라미터가 아니라 @ModelAttribute 헬퍼
 * findUser(@PathVariable userId)에서만 해석된다. 빌더가 {userId}를 PATH로 역추출해 users 행을 시드해야
 * findUser가 성공하고 폼 핸들러에 진입한다(미역추출 시 센티널 userId → orElseThrow 5xx → 폼 미진입).
 */
@Controller
@RequestMapping("/web/users/{userId}")
public class UserOrderWebController {

    private final UserRepository users;

    public UserOrderWebController(UserRepository users) {
        this.users = users;
    }

    /** 클래스-레벨 {userId}는 여기서만 @PathVariable로 해석된다(핸들러 파라미터엔 없음). */
    @ModelAttribute("user")
    public User findUser(@PathVariable("userId") String userId) {
        return users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
    }

    /** 폼 커맨드 객체(JavaBean — form-urlencoded 바인딩). */
    public static class OrderForm {
        private Integer amount;

        public Integer getAmount() {
            return amount;
        }

        public void setAmount(Integer amount) {
            this.amount = amount;
        }
    }

    @PostMapping("/submit")
    public String submit(OrderForm form) {
        // findUser(@ModelAttribute)가 매 요청 전 호출되므로(핸들러가 user를 인자로 받지 않아도) userId
        // 미시드 시 여기 진입 전 orElseThrow 5xx. 시드되면 진입해 amount 가드 양 arm(redirect 302).
        if (form.getAmount() == null || form.getAmount() < 1 || form.getAmount() > 1000) {
            return "redirect:/web/users/error";
        }
        return "redirect:/web/users/ok";
    }
}

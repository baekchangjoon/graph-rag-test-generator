package io.graphrag.sample.orders;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 다중-커맨드 폼 회귀 가드(spec §5-1): 핸들러가 두 커맨드 객체 (HelperForm, @Valid CmdForm)를 받는다.
 * 빌더는 첫 후보(HelperForm)가 아니라 @Valid 붙은 CmdForm을 폼 커맨드로 선택해 그 필드(amount)를 합성해야
 * happy arm(redirect:/ok)에 도달한다. CmdForm을 미선택해 amount 미바인딩이면 @Min(1) 위반 → error arm만.
 */
@Controller
@RequestMapping("/web/multi")
public class MultiCommandWebController {

    /** 첫 후보(비-검증) — 빌더가 오선택하면 안 되는 helper 객체. */
    public static class HelperForm {
        private String note;

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }
    }

    /** 실제 커맨드(@Valid 대상). amount가 [1,∞)일 때만 ok arm. */
    public static class CmdForm {
        @Min(1)
        private Integer amount;

        public Integer getAmount() {
            return amount;
        }

        public void setAmount(Integer amount) {
            this.amount = amount;
        }
    }

    @PostMapping
    public String submit(@ModelAttribute("helper") HelperForm helper,
                         @Valid @ModelAttribute("cmd") CmdForm cmd,
                         BindingResult bindingResult) {
        // CmdForm.amount가 폼 바인딩됐을 때만(빌더가 CmdForm을 커맨드로 선택) ok arm. 양 arm 모두 302.
        if (bindingResult.hasErrors() || cmd.getAmount() == null || cmd.getAmount() < 1) {
            return "redirect:/web/multi/error";
        }
        return "redirect:/web/multi/ok";
    }
}

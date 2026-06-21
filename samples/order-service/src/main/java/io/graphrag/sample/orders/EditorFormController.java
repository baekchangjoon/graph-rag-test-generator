package io.graphrag.sample.orders;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.beans.PropertyEditorSupport;

/**
 * PropertyEditor 폼(spec §5-5): @InitBinder registerCustomEditor(Sku.class,…)로 컨트롤러-local 변환 등록.
 * 단일 토큰(sku=<code>)을 SkuEditor가 sku 테이블 조회 후 Sku로 변환(REFERENCE). 빌더가 sku 행의 code를
 * 토큰으로 합성해야 sku != null. quantity[1,100] 경계가 양 arm 보장. editor는 컨트롤러-local(다른 컨트롤러
 * Sku 필드는 NESTED).
 */
@Controller
@RequestMapping("/web/editor")
public class EditorFormController {

    private final SkuRecordRepository skus;

    public EditorFormController(SkuRecordRepository skus) {
        this.skus = skus;
    }

    @InitBinder
    public void initBinder(WebDataBinder binder) {
        binder.registerCustomEditor(Sku.class, new SkuEditor(skus));
    }

    public static class EditorForm {
        private Sku sku;
        private Integer quantity;

        public Sku getSku() {
            return sku;
        }

        public void setSku(Sku sku) {
            this.sku = sku;
        }

        public Integer getQuantity() {
            return quantity;
        }

        public void setQuantity(Integer quantity) {
            this.quantity = quantity;
        }
    }

    @PostMapping
    public String submit(EditorForm form) {
        if (form.getSku() == null
                || form.getQuantity() == null || form.getQuantity() < 1 || form.getQuantity() > 100) {
            return "redirect:/web/editor/error";
        }
        return "redirect:/web/editor/ok";
    }

    /** code 토큰 → sku 테이블 조회 → Sku. 미존재 토큰은 바인딩 실패(IllegalArgumentException). */
    private static final class SkuEditor extends PropertyEditorSupport {
        private final SkuRecordRepository skus;

        private SkuEditor(SkuRecordRepository skus) {
            this.skus = skus;
        }

        @Override
        public void setAsText(String text) {
            SkuRecord record = skus.findById(text)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown sku: " + text));
            setValue(new Sku(record.getCode()));
        }
    }
}

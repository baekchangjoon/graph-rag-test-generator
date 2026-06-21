package io.graphrag.sample.orders;

/**
 * PropertyEditor 픽스처(spec §5-5): 비-@Entity POJO. EditorFormController는 @InitBinder로 SkuEditor를 등록해
 * 단일 토큰(code)을 Sku로 변환(REFERENCE). editor 미등록 컨트롤러에선 동일 Sku 필드가 NESTED(sku.code)로
 * 처리돼야 한다(컨트롤러-local 스코프 가드). @Entity가 아니어야 §3.1 @Entity-best-effort 규칙에 안 걸린다.
 */
public class Sku {
    private String code;

    public Sku() {
    }

    public Sku(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}

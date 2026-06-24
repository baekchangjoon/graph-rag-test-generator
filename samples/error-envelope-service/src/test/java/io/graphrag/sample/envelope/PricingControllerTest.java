package io.graphrag.sample.envelope;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** ItemController#getItemPrice 단위 테스트. Postgres/Testcontainers 불필요. */
class PricingControllerTest {

    private final ItemRepository itemRepository = mock(ItemRepository.class);
    private final PricingClient pricingClient = mock(PricingClient.class);
    private final ItemController controller = new ItemController(itemRepository, pricingClient);

    @Test
    void errorCode_notNull_throwsBizException() {
        when(pricingClient.quote(42L))
                .thenReturn(new PricingResponse("PRICING_ERROR", "upstream error", null));

        assertThatThrownBy(() -> controller.getItemPrice(42L))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> {
                    BizException biz = (BizException) ex;
                    assertThat(biz.getErrorCode()).isEqualTo("PRICING_ERROR");
                    assertThat(biz.getMessage()).isEqualTo("upstream error");
                });
    }

    @Test
    void errorCode_null_returnsAmount() {
        when(pricingClient.quote(7L))
                .thenReturn(new PricingResponse(null, null, 1500));

        Integer result = controller.getItemPrice(7L);

        assertThat(result).isEqualTo(1500);
    }

    @Test
    void errorCode_null_errorDetailNull_returnsAmount() {
        when(pricingClient.quote(3L))
                .thenReturn(new PricingResponse(null, null, 999));

        assertThat(controller.getItemPrice(3L)).isEqualTo(999);
    }

    @Test
    void errorCode_notNull_errorDetailNull_usesEmptyString() {
        when(pricingClient.quote(5L))
                .thenReturn(new PricingResponse("ERR_NOTFOUND", null, null));

        assertThatThrownBy(() -> controller.getItemPrice(5L))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> {
                    BizException biz = (BizException) ex;
                    assertThat(biz.getErrorCode()).isEqualTo("ERR_NOTFOUND");
                    assertThat(biz.getMessage()).isEqualTo("");
                });
    }
}

package io.graphrag.sample.envelope;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/items")
public class ItemController {

    private final ItemRepository items;
    private final PricingClient pricing;

    public ItemController(ItemRepository items, PricingClient pricing) {
        this.items = items;
        this.pricing = pricing;
    }

    @GetMapping("/{id}")
    public Item getItem(@PathVariable Long id) {
        return items.findById(id)
                .orElseThrow(() -> new BizException("404", "Item not found: " + id));
    }

    @GetMapping("/{id}/price")
    public Integer getItemPrice(@PathVariable Long id) {
        PricingResponse p = pricing.quote(id);
        if (p.errorCode() != null) {
            throw new BizException(p.errorCode(), p.errorDetail() == null ? "" : p.errorDetail());
        }
        return p.amount();
    }
}

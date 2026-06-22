package io.graphrag.sample.envelope;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/items")
public class ItemController {

    private final ItemRepository items;

    public ItemController(ItemRepository items) {
        this.items = items;
    }

    @GetMapping("/{id}")
    public Item getItem(@PathVariable Long id) {
        return items.findById(id)
                .orElseThrow(() -> new BizException("404", "Item not found: " + id));
    }
}

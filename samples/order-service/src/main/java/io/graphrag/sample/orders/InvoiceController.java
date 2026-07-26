package io.graphrag.sample.orders;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

@RestController
@RequestMapping("/api")
public class InvoiceController {

    public record LineItem(String sku, int amount) {}
    public record InvoiceRequest(int total, List<LineItem> lineItems) {}

    @PostMapping("/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    public int create(@RequestBody InvoiceRequest req) {
        if (req.lineItems() == null || req.lineItems().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "lineItems must not be empty");
        }
        int sum = 0;
        for (LineItem li : req.lineItems()) {
            if (li.amount() <= 0) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "each amount must be > 0");
            }
            sum += li.amount();
        }
        if (sum != req.total()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "sum(lineItems.amount)=" + sum + " != total=" + req.total());
        }
        return req.lineItems().size();
    }
}

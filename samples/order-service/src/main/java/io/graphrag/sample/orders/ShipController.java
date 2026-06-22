package io.graphrag.sample.orders;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 중첩 JSON @RequestBody fixture (REQ-003/004): address.city 존재/blank 가드. */
@RestController
@RequestMapping("/api/orders")
public class ShipController {

    public record ShipRequest(String userId, Address address) {
    }

    @PostMapping("/ship")
    public String ship(@RequestBody ShipRequest req) {
        if (req.address() == null || req.address().getCity() == null
                || req.address().getCity().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "city required");
        }
        return "shipped:" + req.address().getCity();
    }
}

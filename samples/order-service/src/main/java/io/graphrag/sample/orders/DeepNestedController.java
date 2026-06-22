package io.graphrag.sample.orders;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 3-depth 중첩 @RequestBody fixture (REQ-001/002): l1.l2.value + l1.l2.count 가드. */
@RestController
@RequestMapping("/api")
public class DeepNestedController {

    public record Level2(String value, int count) {}
    public record Level1(Level2 l2) {}
    public record Root(Level1 l1) {}

    @PostMapping("/deep")
    public String deep(@RequestBody Root req) {
        if (req.l1() == null || req.l1().l2() == null
                || req.l1().l2().value() == null || req.l1().l2().value().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "value required");
        }
        if (req.l1().l2().count() < 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "count must be >= 0");
        }
        return "ok:" + req.l1().l2().value();
    }
}

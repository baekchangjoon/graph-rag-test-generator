package io.graphrag.sample.orders;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class QuotaController {

    private static final Set<String> ALLOWED = Set.of("KR", "US", "JP");

    @PostMapping("/quotas")
    public int quotas(@RequestBody Map<String, Integer> quotas) {
        if (quotas == null || quotas.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "quotas must not be empty");
        }
        for (Map.Entry<String, Integer> e : quotas.entrySet()) {
            if (!ALLOWED.contains(e.getKey())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "unknown region key '" + e.getKey() + "'; allowed: KR,US,JP");
            }
            if (e.getValue() == null || e.getValue() < 0) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "value must be >= 0");
            }
        }
        return quotas.size();
    }
}

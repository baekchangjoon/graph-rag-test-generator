package io.graphrag.sample.orders;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/orders/search")
public class OrderSearchController {

    public record SearchRequest(String userId, String type, Integer minAmount) {
    }

    public record SearchResponse(int count, List<Map<String, Object>> results) {
    }

    private final OrderSearchMapper mapper;

    public OrderSearchController(OrderSearchMapper mapper) {
        this.mapper = mapper;
    }

    @PostMapping
    public SearchResponse search(@RequestBody SearchRequest request) {
        if (request.userId() == null && request.type() == null && request.minAmount() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "at least one filter required");
        }
        List<Map<String, Object>> results =
                mapper.search(request.userId(), request.type(), request.minAmount());
        return new SearchResponse(results.size(), results);
    }
}

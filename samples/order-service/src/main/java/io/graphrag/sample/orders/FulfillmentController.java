package io.graphrag.sample.orders;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class FulfillmentController {

    public record CarrierPolicy(String allowedPrefix, int maxWeight) {}
    public record FulfillmentRequest(String carrierCode, int parcelWeight) {}

    private final RestTemplate rest = new RestTemplate();
    private final String baseUrl;

    public FulfillmentController(@Value("${external.inventory.url:}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @PostMapping("/fulfillment")
    public int fulfill(@RequestBody FulfillmentRequest req) {
        CarrierPolicy policy = rest.getForObject(
                baseUrl + "/carriers/policy?code=" + req.carrierCode(), CarrierPolicy.class);
        if (policy == null || policy.allowedPrefix() == null
                || !req.carrierCode().startsWith(policy.allowedPrefix())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "carrierCode must start with '" + (policy == null ? "?" : policy.allowedPrefix()) + "'");
        }
        if (req.parcelWeight() > policy.maxWeight()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "weight <= " + policy.maxWeight());
        }
        return req.parcelWeight();
    }
}

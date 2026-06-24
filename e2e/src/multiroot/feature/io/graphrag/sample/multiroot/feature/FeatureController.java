package io.graphrag.sample.multiroot.feature;
import org.springframework.web.bind.annotation.*;
@RestController public class FeatureController {
    @PostMapping("/api/feature") public String create() { return "f"; }
}

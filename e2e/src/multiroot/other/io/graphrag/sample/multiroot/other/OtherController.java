package io.graphrag.sample.multiroot.other;
import org.springframework.web.bind.annotation.*;
@RestController public class OtherController {
    @GetMapping("/api/other") public String get() { return "o"; }
}

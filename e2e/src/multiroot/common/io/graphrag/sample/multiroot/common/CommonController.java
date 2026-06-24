package io.graphrag.sample.multiroot.common;
import org.springframework.web.bind.annotation.*;
@RestController public class CommonController {
    @GetMapping("/api/common") public String get() { return "c"; }
}

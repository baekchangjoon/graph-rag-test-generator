package io.graphrag.sample.orders;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/** Map/List scalar @RequestBody fixture (REQ-003/004): prefs + tags + profiles-map 엔드포인트. */
@RestController
@RequestMapping("/api")
public class CollectionsController {

    public record ProfileDto(String name, int age) {}

    @PostMapping("/prefs")
    public int prefs(@RequestBody Map<String, String> prefs) {
        if (prefs == null || prefs.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "prefs required");
        }
        return prefs.size();
    }

    @PostMapping("/tags")
    public int tags(@RequestBody List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tags required");
        }
        for (String t : tags) {
            if (t == null || t.isBlank()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "blank tag not allowed");
            }
        }
        return tags.size();
    }
    @PostMapping("/profiles-map")
    public org.springframework.http.ResponseEntity<Void> profilesMap(
            @RequestBody Map<String, ProfileDto> m) {
        if (m == null || m.isEmpty()) {
            return org.springframework.http.ResponseEntity.badRequest().build();
        }
        ProfileDto p = m.values().iterator().next();
        if (p.name() == null || p.name().isBlank()) {
            return org.springframework.http.ResponseEntity.unprocessableEntity().build();
        }
        return org.springframework.http.ResponseEntity.ok().build();
    }
}

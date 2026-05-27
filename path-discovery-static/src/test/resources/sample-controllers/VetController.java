package com.example.petclinic;

import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
public class VetController {

    // No class-level base path → method paths are absolute.
    @GetMapping("/api/vets")
    public List<Vet> list() { return List.of(); }

    @PatchMapping(path = "/api/vets/{id}")
    public Vet patch(@PathVariable Long id, @RequestBody Vet patch) { return patch; }
}

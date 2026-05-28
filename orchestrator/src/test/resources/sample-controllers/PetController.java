package com.example.test;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/pets")
public class PetController {
    @GetMapping public Object list() { return null; }
    @GetMapping("/{id}") public Object find(@PathVariable Integer id) { return null; }
    @PostMapping public Object create(@RequestBody Object body) { return null; }
}

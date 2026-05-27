package com.example.petclinic;

import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/owners")
public class OwnerController {

    @GetMapping
    public List<Owner> list() { return List.of(); }

    @GetMapping("/{ownerId}")
    public Owner find(@PathVariable Integer ownerId) { return null; }

    @PostMapping
    public Owner create(@RequestBody Owner owner) { return owner; }

    @RequestMapping(value = "/{ownerId}", method = RequestMethod.PUT)
    public void update(@PathVariable Integer ownerId, @RequestBody Owner body) {}

    @DeleteMapping("/{ownerId}")
    public void delete(@PathVariable Integer ownerId) {}

    @GetMapping("/search")
    public List<Owner> search(@RequestParam String name,
                              @RequestParam(value = "max-results") Integer limit) {
        return List.of();
    }
}

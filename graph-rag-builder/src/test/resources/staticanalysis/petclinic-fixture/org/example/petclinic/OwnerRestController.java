package org.example.petclinic;

import java.util.List;

@RestController
@RequestMapping("/owners")
public class OwnerRestController {

    private final OwnerService service;

    public OwnerRestController(OwnerService service) { this.service = service; }

    @GetMapping
    public List<Owner> listOwners() { return List.of(); }

    @GetMapping("/{id}")
    public Owner getOwner(@PathVariable Integer id) {
        if (id == null) { return null; }
        return service.find(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Owner createOwner(@RequestBody Owner body) {
        switch (body.getFirstName()) {
            case "": throw new IllegalArgumentException("blank");
            case "ADMIN": throw new IllegalArgumentException("reserved");
            default: return body;
        }
    }

    @PutMapping("/{id}")
    @Secured({"ROLE_ADMIN", "ROLE_USER"})
    public Owner updateOwner(@PathVariable Integer id, @RequestBody Owner body) {
        return body;
    }

    @DeleteMapping("/{id}")
    @RolesAllowed({"ADMIN"})
    public void deleteOwner(@PathVariable Integer id) {}
}

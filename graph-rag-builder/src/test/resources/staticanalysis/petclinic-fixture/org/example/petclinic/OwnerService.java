package org.example.petclinic;

@Service
public class OwnerService {

    private final OwnerRepository repo;

    public OwnerService(OwnerRepository repo) { this.repo = repo; }

    public Owner find(Integer id) {
        if (id == null) { throw new IllegalArgumentException("id"); }
        if (id < 0)     { throw new IllegalArgumentException("negative id"); }
        return repo.findById(id).orElseThrow();
    }
}

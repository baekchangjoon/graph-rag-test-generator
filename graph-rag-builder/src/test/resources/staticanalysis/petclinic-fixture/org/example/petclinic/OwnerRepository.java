package org.example.petclinic;

public interface OwnerRepository extends JpaRepository<Owner, Integer> {
    Owner findByLastName(String lastName);
}

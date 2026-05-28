package org.example.petclinic;

@Entity
public class Owner {
    private Integer id;
    private String firstName;
    private String lastName;

    public Integer getId() { return id; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
}

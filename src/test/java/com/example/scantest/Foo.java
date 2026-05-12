package com.example.scantest;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/**
 * Stand-in for a host application's own JPA entity. Lives outside the
 * starter's package so we can prove the starter doesn't replace the host's
 * default entity scan.
 */
@Entity
public class Foo {

    @Id
    private Long id;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }
}

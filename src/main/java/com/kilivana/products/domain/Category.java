package com.kilivana.products.domain;

import com.kilivana.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "categories")
public class Category extends BaseEntity {

    @Column(nullable = false, length = 150)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Sector sector;

    @Column(nullable = false)
    private boolean active;

    protected Category() {
    }

    public Category(String name, Sector sector, boolean active) {
        this.name = name;
        this.sector = sector;
        this.active = active;
    }

    public String getName() {
        return name;
    }

    public Sector getSector() {
        return sector;
    }

    public boolean isActive() {
        return active;
    }
}
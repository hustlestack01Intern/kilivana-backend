package com.kilivana.inspection.domain;

import com.kilivana.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "inspection_checklists")
public class InspectionChecklist extends BaseEntity {

    @Column(nullable = false, length = 150)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private InspectionTargetType targetType;

    @Column(nullable = false)
    private boolean active;

    protected InspectionChecklist() {
    }

    public InspectionChecklist(String name, InspectionTargetType targetType, boolean active) {
        this.name = name;
        this.targetType = targetType;
        this.active = active;
    }

    public String getName() {
        return name;
    }

    public InspectionTargetType getTargetType() {
        return targetType;
    }

    public boolean isActive() {
        return active;
    }
}
package com.kilivana.users.domain;

import com.kilivana.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "inspector_profiles")
public class InspectorProfile extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(nullable = false, length = 100)
    private String employeeCode;

    protected InspectorProfile() {
    }

    public InspectorProfile(User user, String employeeCode) {
        this.user = user;
        this.employeeCode = employeeCode;
    }

    public User getUser() {
        return user;
    }

    public String getEmployeeCode() {
        return employeeCode;
    }
}

package com.kilivana.inspection.domain;

import com.kilivana.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "inspection_checklist_questions")
public class InspectionChecklistQuestion extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "checklist_id", nullable = false)
    private InspectionChecklist checklist;

    @Column(nullable = false, length = 500)
    private String question;

    @Column(nullable = false)
    private boolean required;

    @Column(nullable = false)
    private int sortOrder;

    protected InspectionChecklistQuestion() {
    }

    public InspectionChecklistQuestion(InspectionChecklist checklist, String question, boolean required, int sortOrder) {
        this.checklist = checklist;
        this.question = question;
        this.required = required;
        this.sortOrder = sortOrder;
    }

    public InspectionChecklist getChecklist() {
        return checklist;
    }

    public String getQuestion() {
        return question;
    }

    public boolean isRequired() {
        return required;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
package com.kilivana.inspection.domain;

import com.kilivana.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "visit_photos")
public class VisitPhoto extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inspection_id", nullable = false)
    private Inspection inspection;

    @Column(nullable = false, unique = true, length = 255)
    private String storageKey;

    @Column(nullable = false, length = 255)
    private String fileName;

    @Column(nullable = false, length = 100)
    private String contentType;

    @Column(nullable = false)
    private long sizeBytes;

    @Column(nullable = false)
    private OffsetDateTime uploadedAt;

    protected VisitPhoto() {
    }

    public VisitPhoto(Inspection inspection, String storageKey, String fileName, String contentType, long sizeBytes) {
        this.inspection = inspection;
        this.storageKey = storageKey;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.uploadedAt = OffsetDateTime.now();
    }

    public Inspection getInspection() {
        return inspection;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getFileName() {
        return fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public OffsetDateTime getUploadedAt() {
        return uploadedAt;
    }
}
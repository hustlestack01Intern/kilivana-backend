package com.kilivana.logistics.domain;

import com.kilivana.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "tracking_events")
public class TrackingEvent extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private LogisticsJob job;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private LogisticsJobStatus status;

    @Column(nullable = true)
    private Double latitude;

    @Column(nullable = true)
    private Double longitude;

    @Column(nullable = true, length = 200)
    private String locationName;

    @Column(nullable = true, length = 500)
    private String note;

    @Column(nullable = false)
    private OffsetDateTime loggedAt;

    protected TrackingEvent() {
    }

    public TrackingEvent(LogisticsJob job, LogisticsJobStatus status, Double latitude,
                         Double longitude, String locationName, String note) {
        this.job = job;
        this.status = status;
        this.latitude = latitude;
        this.longitude = longitude;
        this.locationName = locationName;
        this.note = note;
        this.loggedAt = OffsetDateTime.now();
    }

    public LogisticsJob getJob() {
        return job;
    }

    public LogisticsJobStatus getStatus() {
        return status;
    }

    public Double getLatitude() {
        return latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public String getLocationName() {
        return locationName;
    }

    public String getNote() {
        return note;
    }

    public OffsetDateTime getLoggedAt() {
        return loggedAt;
    }
}
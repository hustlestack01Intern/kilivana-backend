package com.kilivana.logistics.api;

import com.kilivana.logistics.service.LogisticsService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/logistics")
public class LogisticsController {

    private final LogisticsService logisticsService;

    public LogisticsController(LogisticsService logisticsService) {
        this.logisticsService = logisticsService;
    }

    @PostMapping("/jobs")
    public ResponseEntity<LogisticsJobResponse> create(@Valid @RequestBody CreateLogisticsJobRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(logisticsService.create(request));
    }

    @GetMapping("/jobs/mine")
    public ResponseEntity<List<LogisticsJobResponse>> myJobs() {
        return ResponseEntity.ok(logisticsService.myJobs());
    }

    @GetMapping("/jobs/available")
    public ResponseEntity<List<LogisticsJobResponse>> availableJobs() {
        return ResponseEntity.ok(logisticsService.availableJobs());
    }

    @GetMapping("/jobs/order/{orderId}")
    public ResponseEntity<LogisticsJobResponse> getByOrderId(@PathVariable UUID orderId) {
        return ResponseEntity.ok(logisticsService.getByOrderId(orderId));
    }

    @PatchMapping("/jobs/{jobId}/status")
    public ResponseEntity<LogisticsJobResponse> updateStatus(
            @PathVariable UUID jobId,
            @Valid @RequestBody UpdateLogisticsJobStatusRequest request) {
        return ResponseEntity.ok(logisticsService.updateStatus(jobId, request));
    }

    @PostMapping("/jobs/{jobId}/assign")
    public ResponseEntity<LogisticsJobResponse> assignDriver(
            @PathVariable UUID jobId,
            @Valid @RequestBody AssignDriverRequest request) {
        return ResponseEntity.ok(logisticsService.assignDriver(jobId, request));
    }

    @PostMapping("/jobs/{jobId}/decline")
    public ResponseEntity<LogisticsJobResponse> decline(@PathVariable UUID jobId) {
        return ResponseEntity.ok(logisticsService.decline(jobId));
    }

    @PostMapping("/jobs/{jobId}/tracking")
    public ResponseEntity<TrackingEventResponse> track(
            @PathVariable UUID jobId,
            @Valid @RequestBody TrackingEventRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(logisticsService.track(jobId, request));
    }

    @GetMapping("/jobs/{jobId}/tracking")
    public ResponseEntity<List<TrackingEventResponse>> trackingEvents(@PathVariable UUID jobId) {
        return ResponseEntity.ok(logisticsService.trackingEvents(jobId));
    }

    @PostMapping("/jobs/{jobId}/proof-of-delivery")
    public ResponseEntity<LogisticsJobResponse> submitProofOfDelivery(
            @PathVariable UUID jobId,
            @Valid @RequestBody SubmitProofOfDeliveryRequest request) {
        return ResponseEntity.status(HttpStatus.OK).body(logisticsService.submitProofOfDelivery(jobId, request));
    }
}
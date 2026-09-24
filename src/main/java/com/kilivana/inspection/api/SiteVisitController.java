package com.kilivana.inspection.api;

import com.kilivana.inspection.service.SiteVisitService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/site-visits")
public class SiteVisitController {

    private final SiteVisitService siteVisitService;

    public SiteVisitController(SiteVisitService siteVisitService) {
        this.siteVisitService = siteVisitService;
    }

    @PostMapping
    public ResponseEntity<SiteVisitResponse> create(@Valid @RequestBody CreateSiteVisitRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(siteVisitService.create(request));
    }

    @GetMapping
    public ResponseEntity<List<SiteVisitResponse>> myVisits() {
        return ResponseEntity.ok(siteVisitService.myVisits());
    }

    @GetMapping("/{visitId}")
    public ResponseEntity<SiteVisitResponse> getById(@PathVariable UUID visitId) {
        return ResponseEntity.ok(siteVisitService.getById(visitId));
    }

    @PostMapping("/{visitId}/schedule")
    public ResponseEntity<SiteVisitResponse> schedule(
            @PathVariable UUID visitId,
            @Valid @RequestBody ScheduleSiteVisitRequest request) {
        return ResponseEntity.ok(siteVisitService.schedule(visitId, request));
    }

    @PostMapping("/{visitId}/complete")
    public ResponseEntity<SiteVisitResponse> complete(@PathVariable UUID visitId) {
        return ResponseEntity.ok(siteVisitService.complete(visitId));
    }

    @PostMapping("/{visitId}/reject")
    public ResponseEntity<SiteVisitResponse> reject(@PathVariable UUID visitId) {
        return ResponseEntity.ok(siteVisitService.reject(visitId));
    }
}
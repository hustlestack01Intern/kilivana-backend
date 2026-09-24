package com.kilivana.inspection.api;

import com.kilivana.inspection.domain.InspectionChecklist;
import com.kilivana.inspection.service.InspectionService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inspections")
public class InspectionController {

    private final InspectionService inspectionService;

    public InspectionController(InspectionService inspectionService) {
        this.inspectionService = inspectionService;
    }

    @PostMapping
    public ResponseEntity<InspectionResponse> create(@Valid @RequestBody CreateInspectionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inspectionService.create(request));
    }

    @GetMapping
    public ResponseEntity<List<InspectionResponse>> list(
            @RequestParam(required = false) UUID productId) {
        return ResponseEntity.ok(inspectionService.list(productId));
    }

    @GetMapping("/{inspectionId}")
    public ResponseEntity<InspectionResponse> getById(@PathVariable UUID inspectionId) {
        return ResponseEntity.ok(inspectionService.getById(inspectionId));
    }

    @PatchMapping("/{inspectionId}/status")
    public ResponseEntity<InspectionResponse> updateStatus(
            @PathVariable UUID inspectionId,
            @Valid @RequestBody UpdateInspectionStatusRequest request) {
        return ResponseEntity.ok(inspectionService.updateStatus(inspectionId, request));
    }

    @PostMapping("/{inspectionId}/result")
    public ResponseEntity<InspectionResponse> submitResult(
            @PathVariable UUID inspectionId,
            @Valid @RequestBody SubmitInspectionResultRequest request) {
        return ResponseEntity.ok(inspectionService.submitResult(inspectionId, request));
    }

    @GetMapping("/checklists")
    public ResponseEntity<List<InspectionChecklist>> checklists() {
        return ResponseEntity.ok(inspectionService.checklists());
    }
}
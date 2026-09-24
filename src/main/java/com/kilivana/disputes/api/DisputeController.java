package com.kilivana.disputes.api;

import com.kilivana.disputes.service.DisputeService;
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
@RequestMapping("/api/v1/disputes")
public class DisputeController {

    private final DisputeService disputeService;

    public DisputeController(DisputeService disputeService) {
        this.disputeService = disputeService;
    }

    @PostMapping
    public ResponseEntity<DisputeResponse> create(@Valid @RequestBody CreateDisputeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(disputeService.create(request));
    }

    @GetMapping("/mine")
    public ResponseEntity<List<DisputeResponse>> myDisputes() {
        return ResponseEntity.ok(disputeService.myDisputes());
    }

    @GetMapping
    public ResponseEntity<List<DisputeResponse>> allDisputes() {
        return ResponseEntity.ok(disputeService.allDisputes());
    }

    @GetMapping("/{disputeId}")
    public ResponseEntity<DisputeResponse> getById(@PathVariable UUID disputeId) {
        return ResponseEntity.ok(disputeService.getById(disputeId));
    }

    @PostMapping("/{disputeId}/resolve")
    public ResponseEntity<DisputeResponse> resolve(
            @PathVariable UUID disputeId,
            @Valid @RequestBody ResolveDisputeRequest request) {
        return ResponseEntity.ok(disputeService.resolve(disputeId, request));
    }

    @PostMapping("/{disputeId}/escalate")
    public ResponseEntity<DisputeResponse> escalate(@PathVariable UUID disputeId) {
        return ResponseEntity.ok(disputeService.escalate(disputeId));
    }
}
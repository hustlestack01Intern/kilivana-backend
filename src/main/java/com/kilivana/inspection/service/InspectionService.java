package com.kilivana.inspection.service;

import com.kilivana.badges.service.BadgeService;
import com.kilivana.common.event.InspectionCompletedEvent;
import com.kilivana.common.exception.BusinessConflictException;
import com.kilivana.common.exception.ResourceNotFoundException;
import com.kilivana.common.exception.UnauthorizedOperationException;
import com.kilivana.inspection.api.CreateInspectionRequest;
import com.kilivana.inspection.api.InspectionResponse;
import com.kilivana.inspection.api.SubmitInspectionResultRequest;
import com.kilivana.inspection.api.UpdateInspectionStatusRequest;
import com.kilivana.inspection.domain.Inspection;
import com.kilivana.inspection.domain.InspectionChecklist;
import com.kilivana.inspection.domain.InspectionResult;
import com.kilivana.inspection.domain.InspectionStatus;
import com.kilivana.inspection.repository.InspectionChecklistRepository;
import com.kilivana.inspection.repository.InspectionRepository;
import com.kilivana.products.domain.Product;
import com.kilivana.products.repository.ProductRepository;
import com.kilivana.security.AuthenticatedUser;
import com.kilivana.security.CurrentUser;
import com.kilivana.users.domain.User;
import com.kilivana.users.domain.UserRole;
import com.kilivana.users.repository.UserRepository;
import jakarta.transaction.Transactional;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class InspectionService {

    private final InspectionRepository inspectionRepository;
    private final InspectionChecklistRepository checklistRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final BadgeService badgeService;
    private final CurrentUser currentUser;
    private final ApplicationEventPublisher eventPublisher;

    public InspectionService(
            InspectionRepository inspectionRepository,
            InspectionChecklistRepository checklistRepository,
            ProductRepository productRepository,
            UserRepository userRepository,
            BadgeService badgeService,
            CurrentUser currentUser,
            ApplicationEventPublisher eventPublisher) {
        this.inspectionRepository = inspectionRepository;
        this.checklistRepository = checklistRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.badgeService = badgeService;
        this.currentUser = currentUser;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public InspectionResponse create(CreateInspectionRequest request) {
        AuthenticatedUser actor = currentUser.required();
        if (actor.getRole() != UserRole.INSPECTOR) {
            throw new UnauthorizedOperationException("Only inspectors can schedule inspections");
        }

        Product product = productRepository.findWithOwnerById(request.productId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
        User inspector = userRepository.findById(actor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        InspectionChecklist checklist = request.checklistId() == null
                ? null
                : checklistRepository.findById(request.checklistId())
                        .orElseThrow(() -> new ResourceNotFoundException("Checklist not found"));

        if (request.scheduledAt().isBefore(OffsetDateTime.now())) {
            throw new BusinessConflictException("Inspection must be scheduled in the future");
        }

        Inspection inspection = new Inspection(inspector, product, checklist, request.scheduledAt());
        inspectionRepository.save(inspection);
        return toResponse(inspection);
    }

    public List<InspectionResponse> list(UUID productId) {
        AuthenticatedUser actor = currentUser.required();

        if (productId != null) {
            Product product = productRepository.findWithOwnerById(productId)
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
            if (product.getOwner().getId().equals(actor.getId()) || actor.getRole() == UserRole.INSPECTOR) {
                return inspectionRepository.findByProductId(productId).stream().map(this::toResponse).toList();
            }
            throw new UnauthorizedOperationException("You cannot view inspections for this product");
        }

        if (actor.getRole() == UserRole.INSPECTOR) {
            return inspectionRepository.findByInspectorId(actor.getId()).stream().map(this::toResponse).toList();
        }
        if (actor.getRole() == UserRole.SUPPLIER || actor.getRole() == UserRole.FARMER) {
            return inspectionRepository.findByProductOwnerId(actor.getId()).stream().map(this::toResponse).toList();
        }
        throw new UnauthorizedOperationException("Only inspectors and sellers can view inspections");
    }

    public InspectionResponse getById(UUID inspectionId) {
        AuthenticatedUser actor = currentUser.required();
        Inspection inspection = inspectionRepository.findWithActorsById(inspectionId)
                .orElseThrow(() -> new ResourceNotFoundException("Inspection not found"));
        if (actor.getRole() != UserRole.INSPECTOR
                && !inspection.getProduct().getOwner().getId().equals(actor.getId())) {
            throw new UnauthorizedOperationException("You cannot view this inspection");
        }
        return toResponse(inspection);
    }

    @Transactional
    public InspectionResponse updateStatus(UUID inspectionId, UpdateInspectionStatusRequest request) {
        AuthenticatedUser actor = currentUser.required();
        if (actor.getRole() != UserRole.INSPECTOR) {
            throw new UnauthorizedOperationException("Only inspectors can record inspection results");
        }
        Inspection inspection = requireOwned(inspectionId, actor);

        InspectionStatus next = request.status();
        if (next == InspectionStatus.IN_PROGRESS) {
            inspection.markInProgress();
        } else {
            throw new BusinessConflictException("Status update not allowed; submit a result for concluded inspections");
        }
        return toResponse(inspection);
    }

    @Transactional
    public InspectionResponse submitResult(UUID inspectionId, SubmitInspectionResultRequest request) {
        AuthenticatedUser actor = currentUser.required();
        if (actor.getRole() != UserRole.INSPECTOR) {
            throw new UnauthorizedOperationException("Only inspectors can record inspection results");
        }
        Inspection inspection = requireOwned(inspectionId, actor);
        if (request.findings() == null || request.findings().isBlank()) {
            throw new BusinessConflictException("Findings are required when concluding an inspection");
        }

        InspectionResult result = request.result();
        if (request.rating() == null) {
            throw new BusinessConflictException("Rating is required when concluding an inspection");
        }
        if (result == InspectionResult.CHANGES_REQUIRED && request.nextInspectionDate() == null) {
            throw new BusinessConflictException("A next inspection date is required when requesting changes");
        }
        inspection.conclude(result, request.rating(), request.findings().trim(), request.nextInspectionDate());

        eventPublisher.publishEvent(new InspectionCompletedEvent(
                inspection.getId(),
                inspection.getProduct().getId(),
                inspection.isPassed(),
                OffsetDateTime.now()));
        if (inspection.isPassed()) {
            badgeService.autoAwardByCode(
                    inspection.getProduct().getOwner(),
                    inspection.getInspector(),
                    "QUALITY_HARVEST");
        }
        return toResponse(inspection);
    }

    public List<InspectionChecklist> checklists() {
        return checklistRepository.findByActiveTrueOrderByNameAsc();
    }

    private Inspection requireOwned(UUID inspectionId, AuthenticatedUser actor) {
        Inspection inspection = inspectionRepository.findWithActorsById(inspectionId)
                .orElseThrow(() -> new ResourceNotFoundException("Inspection not found"));
        if (!inspection.getInspector().getId().equals(actor.getId())) {
            throw new UnauthorizedOperationException("You can only update your own inspections");
        }
        return inspection;
    }

    private InspectionResponse toResponse(Inspection inspection) {
        return new InspectionResponse(
                inspection.getId(),
                inspection.getInspector().getId(),
                inspection.getProduct().getId(),
                inspection.getProduct().getTitle(),
                inspection.getStatus(),
                inspection.getResult(),
                inspection.getChecklist() == null ? null : inspection.getChecklist().getId(),
                inspection.getRating(),
                inspection.getFindings(),
                inspection.getScheduledAt(),
                inspection.getPerformedAt(),
                inspection.getNextInspectionDate());
    }
}
package com.kilivana.inspection.service;

import com.kilivana.common.exception.BusinessConflictException;
import com.kilivana.common.exception.ResourceNotFoundException;
import com.kilivana.common.exception.UnauthorizedOperationException;
import com.kilivana.inspection.api.CreateSiteVisitRequest;
import com.kilivana.inspection.api.ScheduleSiteVisitRequest;
import com.kilivana.inspection.api.SiteVisitResponse;
import com.kilivana.inspection.domain.SiteVisit;
import com.kilivana.inspection.repository.SiteVisitRepository;
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
import org.springframework.stereotype.Service;

@Service
public class SiteVisitService {

    private final SiteVisitRepository siteVisitRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final CurrentUser currentUser;

    public SiteVisitService(
            SiteVisitRepository siteVisitRepository,
            ProductRepository productRepository,
            UserRepository userRepository,
            CurrentUser currentUser) {
        this.siteVisitRepository = siteVisitRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.currentUser = currentUser;
    }

    @Transactional
    public SiteVisitResponse create(CreateSiteVisitRequest request) {
        AuthenticatedUser actor = currentUser.required();
        if (actor.getRole() != UserRole.FARMER) {
            throw new UnauthorizedOperationException("Only farmers can request site visits");
        }
        Product product = productRepository.findWithOwnerById(request.productId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
        if (!product.getOwner().getId().equals(actor.getId())) {
            throw new BusinessConflictException("You can only request a site visit for your own product");
        }
        User farmer = userRepository.findById(actor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        SiteVisit visit = new SiteVisit(farmer, product);
        siteVisitRepository.save(visit);
        return toResponse(visit);
    }

    @Transactional
    public SiteVisitResponse schedule(UUID visitId, ScheduleSiteVisitRequest request) {
        AuthenticatedUser actor = currentUser.required();
        if (actor.getRole() != UserRole.INSPECTOR) {
            throw new UnauthorizedOperationException("Only inspectors can schedule site visits");
        }
        SiteVisit visit = requireVisit(visitId);
        visit.schedule(request.scheduledAt());
        return toResponse(visit);
    }

    @Transactional
    public SiteVisitResponse complete(UUID visitId) {
        AuthenticatedUser actor = currentUser.required();
        if (actor.getRole() != UserRole.INSPECTOR) {
            throw new UnauthorizedOperationException("Only inspectors can complete site visits");
        }
        SiteVisit visit = requireVisit(visitId);
        visit.complete();
        return toResponse(visit);
    }

    @Transactional
    public SiteVisitResponse reject(UUID visitId) {
        AuthenticatedUser actor = currentUser.required();
        if (actor.getRole() != UserRole.INSPECTOR && actor.getRole() != UserRole.FARMER) {
            throw new UnauthorizedOperationException("Only inspectors or the requesting farmer can reject a site visit");
        }
        SiteVisit visit = requireVisit(visitId);
        if (actor.getRole() == UserRole.FARMER && !visit.getFarmer().getId().equals(actor.getId())) {
            throw new UnauthorizedOperationException("You can only reject your own site visit requests");
        }
        visit.reject();
        return toResponse(visit);
    }

    public List<SiteVisitResponse> myVisits() {
        AuthenticatedUser actor = currentUser.required();
        if (actor.getRole() == UserRole.FARMER) {
            return siteVisitRepository.findByFarmerId(actor.getId()).stream().map(this::toResponse).toList();
        }
        if (actor.getRole() == UserRole.INSPECTOR) {
            return siteVisitRepository.findAllWithDetails().stream().map(this::toResponse).toList();
        }
        throw new UnauthorizedOperationException("Only farmers and inspectors can view site visits");
    }

    public SiteVisitResponse getById(UUID visitId) {
        AuthenticatedUser actor = currentUser.required();
        SiteVisit visit = requireVisit(visitId);
        if (actor.getRole() != UserRole.INSPECTOR && !visit.getFarmer().getId().equals(actor.getId())) {
            throw new UnauthorizedOperationException("You cannot view this site visit");
        }
        return toResponse(visit);
    }

    private SiteVisit requireVisit(UUID visitId) {
        return siteVisitRepository.findWithDetailsById(visitId)
                .orElseThrow(() -> new ResourceNotFoundException("Site visit not found"));
    }

    private SiteVisitResponse toResponse(SiteVisit visit) {
        return new SiteVisitResponse(
                visit.getId(),
                visit.getFarmer().getId(),
                visit.getProduct().getId(),
                visit.getProduct().getTitle(),
                visit.getRequestedAt(),
                visit.getScheduledAt(),
                visit.getStatus());
    }
}
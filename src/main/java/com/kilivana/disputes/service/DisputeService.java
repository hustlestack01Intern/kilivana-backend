package com.kilivana.disputes.service;

import com.kilivana.common.exception.BusinessConflictException;
import com.kilivana.common.exception.ResourceNotFoundException;
import com.kilivana.common.exception.UnauthorizedOperationException;
import com.kilivana.disputes.api.CreateDisputeRequest;
import com.kilivana.disputes.api.DisputeResponse;
import com.kilivana.disputes.api.ResolveDisputeRequest;
import com.kilivana.disputes.domain.Dispute;
import com.kilivana.disputes.domain.DisputeStatus;
import com.kilivana.disputes.repository.DisputeRepository;
import com.kilivana.orders.domain.Order;
import com.kilivana.orders.repository.OrderRepository;
import com.kilivana.security.AuthenticatedUser;
import com.kilivana.security.CurrentUser;
import com.kilivana.users.domain.User;
import com.kilivana.users.domain.UserRole;
import com.kilivana.users.repository.UserRepository;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class DisputeService {

    private final DisputeRepository disputeRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final CurrentUser currentUser;

    public DisputeService(
            DisputeRepository disputeRepository,
            OrderRepository orderRepository,
            UserRepository userRepository,
            CurrentUser currentUser) {
        this.disputeRepository = disputeRepository;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.currentUser = currentUser;
    }

    @Transactional
    public DisputeResponse create(CreateDisputeRequest request) {
        AuthenticatedUser actor = currentUser.required();
        if (actor.getRole() == UserRole.ADMIN) {
            throw new UnauthorizedOperationException("Only order participants can raise a dispute");
        }
        Order order = orderRepository.findWithActorsById(request.orderId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (!order.getBuyer().getId().equals(actor.getId()) && !order.getSeller().getId().equals(actor.getId())) {
            throw new UnauthorizedOperationException("Only order participants can raise a dispute");
        }
        disputeRepository.findByOrderIdAndStatusNot(order.getId(), DisputeStatus.RESOLVED)
                .ifPresent(existing -> {
                    throw new BusinessConflictException("An active dispute already exists for this order");
                });
        User raisedBy = userRepository.findById(actor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Dispute dispute = new Dispute(order, raisedBy, request.subject().trim(), request.description().trim());
        disputeRepository.save(dispute);
        return toResponse(dispute);
    }

    public List<DisputeResponse> myDisputes() {
        AuthenticatedUser actor = currentUser.required();
        return disputeRepository
                .findByOrderSellerIdOrOrderBuyerIdOrRaisedById(actor.getId(), actor.getId(), actor.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public List<DisputeResponse> allDisputes() {
        requireAdmin();
        return disputeRepository.findAll().stream().map(this::toResponse).toList();
    }

    public DisputeResponse getById(UUID disputeId) {
        AuthenticatedUser actor = currentUser.required();
        Dispute dispute = requireDispute(disputeId);
        if (actor.getRole() != UserRole.ADMIN && !isParticipant(dispute, actor)) {
            throw new UnauthorizedOperationException("Only order participants or admins can view a dispute");
        }
        return toResponse(dispute);
    }

    @Transactional
    public DisputeResponse resolve(UUID disputeId, ResolveDisputeRequest request) {
        requireAdmin();
        Dispute dispute = requireDispute(disputeId);
        User resolver = userRepository.findById(currentUser.required().getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        dispute.resolve(resolver, request.resolutionNote().trim());
        return toResponse(dispute);
    }

    @Transactional
    public DisputeResponse escalate(UUID disputeId) {
        requireAdmin();
        Dispute dispute = requireDispute(disputeId);
        dispute.escalate();
        return toResponse(dispute);
    }

    private void requireAdmin() {
        AuthenticatedUser actor = currentUser.required();
        if (actor.getRole() != UserRole.ADMIN) {
            throw new UnauthorizedOperationException("Only administrators can manage disputes");
        }
    }

    private Dispute requireDispute(UUID disputeId) {
        return disputeRepository.findWithActorsById(disputeId)
                .orElseThrow(() -> new ResourceNotFoundException("Dispute not found"));
    }

    private boolean isParticipant(Dispute dispute, AuthenticatedUser actor) {
        return dispute.getOrder().getBuyer().getId().equals(actor.getId())
                || dispute.getOrder().getSeller().getId().equals(actor.getId())
                || dispute.getRaisedBy().getId().equals(actor.getId());
    }

    private DisputeResponse toResponse(Dispute dispute) {
        return new DisputeResponse(
                dispute.getId(),
                dispute.getOrder().getId(),
                dispute.getRaisedBy().getId(),
                dispute.getStatus(),
                dispute.getSubject(),
                dispute.getDescription(),
                dispute.getResolutionNote(),
                dispute.getResolvedBy() == null ? null : dispute.getResolvedBy().getId(),
                dispute.getResolvedAt(),
                dispute.getCreatedAt());
    }
}
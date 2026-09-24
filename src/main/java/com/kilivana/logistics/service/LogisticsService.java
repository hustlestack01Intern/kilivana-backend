package com.kilivana.logistics.service;

import com.kilivana.addresses.domain.Address;
import com.kilivana.addresses.repository.AddressRepository;
import com.kilivana.common.exception.BusinessConflictException;
import com.kilivana.common.exception.ResourceNotFoundException;
import com.kilivana.common.exception.UnauthorizedOperationException;
import com.kilivana.common.event.LogisticsJobStatusChangedEvent;
import com.kilivana.logistics.api.AssignDriverRequest;
import com.kilivana.logistics.api.CreateLogisticsJobRequest;
import com.kilivana.logistics.api.LogisticsJobResponse;
import com.kilivana.logistics.api.SubmitProofOfDeliveryRequest;
import com.kilivana.logistics.api.TrackingEventRequest;
import com.kilivana.logistics.api.TrackingEventResponse;
import com.kilivana.logistics.api.UpdateLogisticsJobStatusRequest;
import com.kilivana.logistics.domain.LogisticsJob;
import com.kilivana.logistics.domain.LogisticsJobStatus;
import com.kilivana.logistics.domain.TrackingEvent;
import com.kilivana.logistics.repository.LogisticsJobRepository;
import com.kilivana.logistics.repository.TrackingEventRepository;
import com.kilivana.orders.domain.Order;
import com.kilivana.orders.domain.OrderStatus;
import com.kilivana.orders.repository.OrderRepository;
import com.kilivana.security.AuthenticatedUser;
import com.kilivana.security.CurrentUser;
import com.kilivana.users.domain.DriverAvailability;
import com.kilivana.users.domain.DriverProfile;
import com.kilivana.users.domain.User;
import com.kilivana.users.domain.UserRole;
import com.kilivana.users.repository.DriverProfileRepository;
import com.kilivana.users.repository.UserRepository;
import jakarta.transaction.Transactional;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class LogisticsService {

    private static final Set<OrderStatus> DELIVERABLE_ORDER_STATUSES = Set.of(
            OrderStatus.CONFIRMED,
            OrderStatus.IN_PROGRESS);

    private static final Set<LogisticsJobStatus> TERMINAL = Set.of(
            LogisticsJobStatus.DELIVERED,
            LogisticsJobStatus.FAILED,
            LogisticsJobStatus.CANCELLED);

    private final LogisticsJobRepository jobRepository;
    private final TrackingEventRepository trackingEventRepository;
    private final OrderRepository orderRepository;
    private final AddressRepository addressRepository;
    private final DriverProfileRepository driverProfileRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final CurrentUser currentUser;

    public LogisticsService(
            LogisticsJobRepository jobRepository,
            TrackingEventRepository trackingEventRepository,
            OrderRepository orderRepository,
            AddressRepository addressRepository,
            DriverProfileRepository driverProfileRepository,
            UserRepository userRepository,
            ApplicationEventPublisher eventPublisher,
            CurrentUser currentUser) {
        this.jobRepository = jobRepository;
        this.trackingEventRepository = trackingEventRepository;
        this.orderRepository = orderRepository;
        this.addressRepository = addressRepository;
        this.driverProfileRepository = driverProfileRepository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
        this.currentUser = currentUser;
    }

    @Transactional
    public LogisticsJobResponse create(CreateLogisticsJobRequest request) {
        AuthenticatedUser actor = currentUser.required();
        Order order = orderRepository.findWithActorsById(request.orderId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (!order.getSeller().getId().equals(actor.getId())) {
            throw new UnauthorizedOperationException("Only the seller can arrange logistics");
        }
        if (!DELIVERABLE_ORDER_STATUSES.contains(order.getStatus())) {
            throw new BusinessConflictException("Logistics can only be arranged for confirmed orders");
        }
        if (jobRepository.findByOrderId(order.getId()).isPresent()) {
            throw new BusinessConflictException("Logistics are already arranged for this order");
        }
        Address pickup = requireAddress(request.pickupAddressId(), order.getSeller().getId(), "pickup");
        Address delivery = requireAddress(request.deliveryAddressId(), order.getBuyer().getId(), "delivery");
        if (request.estimatedArrival().isBefore(OffsetDateTime.now())) {
            throw new BusinessConflictException("Estimated arrival must be in the future");
        }

        LogisticsJob job = new LogisticsJob(
                order,
                request.notes() == null ? null : request.notes().trim(),
                request.estimatedArrival(),
                pickup.getId(),
                delivery.getId());
        jobRepository.save(job);
        return toResponse(job);
    }

    public List<LogisticsJobResponse> myJobs() {
        AuthenticatedUser actor = currentUser.required();
        if (actor.getRole() == UserRole.DRIVER) {
            return jobRepository.findByDriverId(actor.getId()).stream().map(this::toResponse).toList();
        }
        return jobRepository.findByOrderSellerIdOrOrderBuyerId(actor.getId(), actor.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public List<LogisticsJobResponse> availableJobs() {
        AuthenticatedUser actor = currentUser.required();
        if (actor.getRole() != UserRole.DRIVER) {
            throw new UnauthorizedOperationException("Only drivers can view available logistics jobs");
        }
        return jobRepository.findByStatusAndDriverIsNullOrDriverId(
                        LogisticsJobStatus.PENDING_ACCEPTANCE, actor.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public LogisticsJobResponse assignDriver(UUID jobId, AssignDriverRequest request) {
        AuthenticatedUser actor = currentUser.required();
        if (actor.getRole() != UserRole.ADMIN) {
            throw new UnauthorizedOperationException("Only administrators can assign drivers to logistics jobs");
        }
        LogisticsJob job = requireJob(jobId);
        User driver = userRepository.findById(request.driverId())
                .orElseThrow(() -> new ResourceNotFoundException("Driver not found"));
        if (driver.getRole() != UserRole.DRIVER) {
            throw new BusinessConflictException("The assigned user is not a driver");
        }
        DriverProfile profile = driverProfileRepository.findByUserId(driver.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Driver profile not found"));
        if (profile.getAvailability() != DriverAvailability.AVAILABLE
                && (job.getDriver() == null || !job.getDriver().getId().equals(driver.getId()))) {
            throw new BusinessConflictException("The selected driver is currently unavailable");
        }
        job.assignDriver(driver);
        trackingEventRepository.save(new TrackingEvent(
                job,
                job.getStatus(),
                null,
                null,
                null,
                "Driver " + driver.getFullName() + " assigned by " + actor.getId()));
        return toResponse(job);
    }

    public List<TrackingEventResponse> trackingEvents(UUID jobId) {
        AuthenticatedUser actor = currentUser.required();
        LogisticsJob job = requireJob(jobId);
        requireParticipantOrDriver(job, actor);
        return trackingEventRepository.findByJobIdOrderByLoggedAtAsc(jobId)
                .stream()
                .map(this::toTrackingResponse)
                .toList();
    }

    @Transactional
    public LogisticsJobResponse updateStatus(UUID jobId, UpdateLogisticsJobStatusRequest request) {
        AuthenticatedUser actor = currentUser.required();
        User actorUser = requireUser(actor.getId());
        LogisticsJob job = requireJob(jobId);
        if (job.getOrder().getBuyer().getId().equals(actor.getId())) {
            throw new UnauthorizedOperationException("Buyers cannot update logistics job status");
        }
        LogisticsJobStatus previous = job.getStatus();
        job.updateStatus(request.status(), actorUser);
        applyDriverAvailability(job, actorUser);
        publishJobStatusChanged(job, previous);
        return toResponse(job);
    }

    @Transactional
    public LogisticsJobResponse decline(UUID jobId) {
        AuthenticatedUser actor = currentUser.required();
        if (actor.getRole() != UserRole.DRIVER) {
            throw new UnauthorizedOperationException("Only drivers can decline logistics jobs");
        }
        LogisticsJob job = requireJob(jobId);
        if (job.getStatus() != LogisticsJobStatus.PENDING_ACCEPTANCE) {
            throw new BusinessConflictException("Only pending acceptance jobs can be declined");
        }
        trackingEventRepository.save(new TrackingEvent(
                job,
                job.getStatus(),
                null,
                null,
                null,
                "Driver " + actor.getId() + " declined this job"));
        if (job.getDriver() != null && job.getDriver().getId().equals(actor.getId())) {
            job.unassignDriver();
        }
        return toResponse(job);
    }

    @Transactional
    public TrackingEventResponse track(UUID jobId, TrackingEventRequest request) {
        AuthenticatedUser actor = currentUser.required();
        User actorUser = requireUser(actor.getId());
        LogisticsJob job = requireJob(jobId);
        requireParticipantOrDriver(job, actor);
        if (request.status() == LogisticsJobStatus.ACCEPTED) {
            throw new BusinessConflictException("Acceptance is recorded through the job status endpoint");
        }
        if (request.status() != job.getStatus()) {
            LogisticsJobStatus previous = job.getStatus();
            job.updateStatus(request.status(), actorUser);
            applyDriverAvailability(job, actorUser);
            publishJobStatusChanged(job, previous);
        }

        TrackingEvent event = new TrackingEvent(
                job,
                request.status(),
                request.latitude(),
                request.longitude(),
                request.locationName() == null ? null : request.locationName().trim(),
                request.note() == null ? null : request.note().trim());
        trackingEventRepository.save(event);
        if (event.getStatus() == LogisticsJobStatus.DELIVERED && job.getStatus() == LogisticsJobStatus.DELIVERED
                && job.getOrder().getStatus() != OrderStatus.DELIVERED) {
            job.getOrder().transitionTo(OrderStatus.DELIVERED);
        }
        return toTrackingResponse(event);
    }

    @Transactional
    public LogisticsJobResponse submitProofOfDelivery(UUID jobId, SubmitProofOfDeliveryRequest request) {
        AuthenticatedUser actor = currentUser.required();
        LogisticsJob job = requireJob(jobId);
        if (!job.getOrder().getSeller().getId().equals(actor.getId())
                && (job.getDriver() == null || !job.getDriver().getId().equals(actor.getId()))) {
            throw new UnauthorizedOperationException("Only the seller or assigned driver can submit proof of delivery");
        }
        String storageKey = "pods/" + job.getId() + "/" + UUID.randomUUID();
        job.submitProofOfDelivery(
                storageKey,
                request.fileName().trim(),
                request.contentType().trim(),
                request.deliveredTo().trim());
        return toResponse(job);
    }

    public LogisticsJobResponse getByOrderId(UUID orderId) {
        AuthenticatedUser actor = currentUser.required();
        Order order = orderRepository.findWithActorsById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (!order.getBuyer().getId().equals(actor.getId()) && !order.getSeller().getId().equals(actor.getId())) {
            throw new UnauthorizedOperationException("Only order participants can view logistics details");
        }
        LogisticsJob job = jobRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("No logistics arranged for this order"));
        return toResponse(job);
    }

    private Address requireAddress(UUID addressId, UUID ownerId, String role) {
        Address address = addressRepository.findById(addressId)
                .orElseThrow(() -> new ResourceNotFoundException(role + " address not found"));
        if (!address.getUser().getId().equals(ownerId)) {
            throw new BusinessConflictException(role + " address must belong to the "
                    + (role.equals("pickup") ? "seller" : "buyer"));
        }
        return address;
    }

    private void requireParticipantOrDriver(LogisticsJob job, AuthenticatedUser actor) {
        Order order = job.getOrder();
        if (!order.getBuyer().getId().equals(actor.getId())
                && !order.getSeller().getId().equals(actor.getId())
                && (job.getDriver() == null || !job.getDriver().getId().equals(actor.getId()))) {
            throw new UnauthorizedOperationException("You are not part of this logistics job");
        }
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private void publishJobStatusChanged(LogisticsJob job, LogisticsJobStatus previous) {
        if (previous == job.getStatus()) {
            return;
        }
        eventPublisher.publishEvent(new LogisticsJobStatusChangedEvent(
                job.getId(),
                job.getOrder().getId(),
                previous,
                job.getStatus(),
                OffsetDateTime.now()));
    }

    private void applyDriverAvailability(LogisticsJob job, User actor) {
        if (actor.getRole() != UserRole.DRIVER) {
            return;
        }
        DriverProfile profile = driverProfileRepository.findByUserId(actor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Driver profile not found"));
        if (job.getStatus() == LogisticsJobStatus.ACCEPTED) {
            profile.setAvailability(DriverAvailability.ON_JOB);
        } else if (TERMINAL.contains(job.getStatus())) {
            profile.setAvailability(DriverAvailability.AVAILABLE);
        }
    }

    private LogisticsJob requireJob(UUID jobId) {
        return jobRepository.findWithActorsById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Logistics job not found"));
    }

    private LogisticsJobResponse toResponse(LogisticsJob job) {
        User driver = job.getDriver();
        return new LogisticsJobResponse(
                job.getId(),
                job.getOrder().getId(),
                job.getStatus(),
                driver == null ? null : driver.getId(),
                driver == null ? null : driver.getFullName(),
                job.getNotes(),
                job.getEstimatedArrival(),
                job.getPickupAddressId(),
                job.getDeliveryAddressId(),
                job.getPodSubmittedAt(),
                job.getDeliveredTo(),
                job.getCreatedAt());
    }

    private TrackingEventResponse toTrackingResponse(TrackingEvent event) {
        return new TrackingEventResponse(
                event.getId(),
                event.getJob().getId(),
                event.getStatus(),
                event.getLatitude(),
                event.getLongitude(),
                event.getLocationName(),
                event.getNote(),
                event.getLoggedAt());
    }
}
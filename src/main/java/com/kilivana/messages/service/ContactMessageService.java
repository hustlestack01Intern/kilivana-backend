package com.kilivana.messages.service;

import com.kilivana.common.event.ContactMessageReceivedEvent;
import com.kilivana.common.exception.ResourceNotFoundException;
import com.kilivana.common.exception.UnauthorizedOperationException;
import com.kilivana.messages.api.ContactMessageRequest;
import com.kilivana.messages.api.ContactMessageResponse;
import com.kilivana.messages.domain.ContactMessage;
import com.kilivana.messages.domain.ContactMessageStatus;
import com.kilivana.messages.repository.ContactMessageRepository;
import com.kilivana.security.AuthenticatedUser;
import com.kilivana.security.CurrentUser;
import com.kilivana.users.domain.UserRole;
import jakarta.transaction.Transactional;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class ContactMessageService {

    private final ContactMessageRepository contactMessageRepository;
    private final CurrentUser currentUser;
    private final ApplicationEventPublisher eventPublisher;

    public ContactMessageService(
            ContactMessageRepository contactMessageRepository,
            CurrentUser currentUser,
            ApplicationEventPublisher eventPublisher) {
        this.contactMessageRepository = contactMessageRepository;
        this.currentUser = currentUser;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public ContactMessageResponse submit(ContactMessageRequest request) {
        ContactMessage message = new ContactMessage(
                request.name().trim(),
                request.email().trim().toLowerCase(),
                request.subject().trim(),
                request.body().trim());
        contactMessageRepository.save(message);
        eventPublisher.publishEvent(new ContactMessageReceivedEvent(
                message.getId(),
                message.getEmail(),
                message.getSubject(),
                OffsetDateTime.now()));
        return toResponse(message);
    }

    public List<ContactMessageResponse> list(ContactMessageStatus status) {
        requireAdmin();
        List<ContactMessage> messages = status == null
                ? contactMessageRepository.findAll()
                : contactMessageRepository.findByStatusOrderByCreatedAtDesc(status);
        return messages.stream().map(this::toResponse).toList();
    }

    public ContactMessageResponse getById(UUID messageId) {
        requireAdmin();
        return toResponse(contactMessageRepository.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("Contact message not found")));
    }

    @Transactional
    public ContactMessageResponse resolve(UUID messageId) {
        requireAdmin();
        ContactMessage message = contactMessageRepository.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("Contact message not found"));
        message.resolve();
        return toResponse(message);
    }

    private void requireAdmin() {
        AuthenticatedUser actor = currentUser.required();
        if (actor.getRole() != UserRole.ADMIN) {
            throw new UnauthorizedOperationException("Only administrators can manage contact messages");
        }
    }

    private ContactMessageResponse toResponse(ContactMessage message) {
        return new ContactMessageResponse(
                message.getId(),
                message.getName(),
                message.getEmail(),
                message.getSubject(),
                message.getBody(),
                message.getStatus(),
                message.getCreatedAt());
    }
}
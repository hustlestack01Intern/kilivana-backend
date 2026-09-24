package com.kilivana.notifications.service;

import com.kilivana.common.exception.ResourceNotFoundException;
import com.kilivana.common.exception.UnauthorizedOperationException;
import com.kilivana.notifications.api.NotificationResponse;
import com.kilivana.notifications.domain.Notification;
import com.kilivana.notifications.domain.NotificationType;
import com.kilivana.notifications.repository.NotificationRepository;
import com.kilivana.security.AuthenticatedUser;
import com.kilivana.security.CurrentUser;
import com.kilivana.users.repository.UserRepository;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final CurrentUser currentUser;

    public NotificationService(
            NotificationRepository notificationRepository,
            UserRepository userRepository,
            CurrentUser currentUser) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.currentUser = currentUser;
    }

    @Transactional
    public void create(UUID recipientId, NotificationType type, String title, String body, String link) {
        Notification notification = new Notification(
                userRepository.findById(recipientId)
                        .orElseThrow(() -> new ResourceNotFoundException("User not found")),
                type,
                title,
                body,
                link);
        notificationRepository.save(notification);
    }

    public List<NotificationResponse> myNotifications(Boolean unread) {
        AuthenticatedUser actor = currentUser.required();
        List<Notification> notifications = Boolean.TRUE.equals(unread)
                ? notificationRepository.findByRecipientIdAndReadOrderByCreatedAtDesc(actor.getId(), false)
                : notificationRepository.findByRecipientIdOrderByCreatedAtDesc(actor.getId());
        return notifications.stream().map(this::toResponse).toList();
    }

    public long unreadCount() {
        return notificationRepository.countByRecipientIdAndReadFalse(currentUser.required().getId());
    }

    public NotificationResponse getById(UUID notificationId) {
        AuthenticatedUser actor = currentUser.required();
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));
        if (!notification.getRecipient().getId().equals(actor.getId())) {
            throw new UnauthorizedOperationException("You can only access your own notifications");
        }
        return toResponse(notification);
    }

    @Transactional
    public NotificationResponse markAsRead(UUID notificationId) {
        Notification notification = requireOwned(notificationId);
        notification.markAsRead();
        return toResponse(notification);
    }

    @Transactional
    public int markAllRead() {
        AuthenticatedUser actor = currentUser.required();
        int updated = 0;
        for (Notification notification : notificationRepository
                .findByRecipientIdAndReadOrderByCreatedAtDesc(actor.getId(), false)) {
            notification.markAsRead();
            updated++;
        }
        return updated;
    }

    @Transactional
    public void delete(UUID notificationId) {
        Notification notification = requireOwned(notificationId);
        notificationRepository.delete(notification);
    }

    private Notification requireOwned(UUID notificationId) {
        AuthenticatedUser actor = currentUser.required();
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));
        if (!notification.getRecipient().getId().equals(actor.getId())) {
            throw new UnauthorizedOperationException("You can only access your own notifications");
        }
        return notification;
    }

    private NotificationResponse toResponse(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getType(),
                notification.getTitle(),
                notification.getBody(),
                notification.isRead(),
                notification.getReadAt(),
                notification.getLink(),
                notification.getCreatedAt());
    }
}
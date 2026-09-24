package com.kilivana.notifications.repository;

import com.kilivana.notifications.domain.Notification;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findByRecipientIdOrderByCreatedAtDesc(UUID recipientId);

    List<Notification> findByRecipientIdAndReadOrderByCreatedAtDesc(UUID recipientId, boolean read);

    long countByRecipientIdAndReadFalse(UUID recipientId);

    void deleteByRecipientIdAndId(UUID recipientId, UUID notificationId);
}
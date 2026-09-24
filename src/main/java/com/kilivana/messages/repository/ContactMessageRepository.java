package com.kilivana.messages.repository;

import com.kilivana.messages.domain.ContactMessage;
import com.kilivana.messages.domain.ContactMessageStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContactMessageRepository extends JpaRepository<ContactMessage, UUID> {

    List<ContactMessage> findByStatusOrderByCreatedAtDesc(ContactMessageStatus status);
}
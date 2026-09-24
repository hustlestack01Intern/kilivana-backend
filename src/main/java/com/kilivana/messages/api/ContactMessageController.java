package com.kilivana.messages.api;

import com.kilivana.messages.domain.ContactMessageStatus;
import com.kilivana.messages.service.ContactMessageService;
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
@RequestMapping("/api/v1/contact-messages")
public class ContactMessageController {

    private final ContactMessageService contactMessageService;

    public ContactMessageController(ContactMessageService contactMessageService) {
        this.contactMessageService = contactMessageService;
    }

    @PostMapping
    public ResponseEntity<ContactMessageResponse> submit(@Valid @RequestBody ContactMessageRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(contactMessageService.submit(request));
    }

    @GetMapping
    public ResponseEntity<List<ContactMessageResponse>> list(
            @RequestParam(required = false) ContactMessageStatus status) {
        return ResponseEntity.ok(contactMessageService.list(status));
    }

    @GetMapping("/{messageId}")
    public ResponseEntity<ContactMessageResponse> getById(@PathVariable UUID messageId) {
        return ResponseEntity.ok(contactMessageService.getById(messageId));
    }

    @PatchMapping("/{messageId}/resolve")
    public ResponseEntity<ContactMessageResponse> resolve(@PathVariable UUID messageId) {
        return ResponseEntity.ok(contactMessageService.resolve(messageId));
    }
}
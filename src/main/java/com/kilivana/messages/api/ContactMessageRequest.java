package com.kilivana.messages.api;

import com.kilivana.messages.domain.ContactMessageStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ContactMessageRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Email @Size(max = 120) String email,
        @NotBlank @Size(max = 200) String subject,
        @NotBlank @Size(max = 2000) String body) {
}
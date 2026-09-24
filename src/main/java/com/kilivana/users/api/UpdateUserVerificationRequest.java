package com.kilivana.users.api;

import com.kilivana.users.domain.VerificationStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateUserVerificationRequest(@NotNull VerificationStatus verificationStatus) {
}
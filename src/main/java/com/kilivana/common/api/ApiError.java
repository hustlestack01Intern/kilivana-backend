package com.kilivana.common.api;

import java.time.OffsetDateTime;
import java.util.List;

public record ApiError(
        String code,
        String message,
        int status,
        String path,
        String requestId,
        OffsetDateTime timestamp,
        List<FieldValidationError> fieldErrors) {
}

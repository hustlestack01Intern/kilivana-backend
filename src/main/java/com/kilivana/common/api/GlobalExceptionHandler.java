package com.kilivana.common.api;

import com.kilivana.common.exception.AccountLoginThrottledException;
import com.kilivana.common.config.RequestContextFilter;
import com.kilivana.media.InvalidMediaException;
import com.kilivana.media.MediaStorageException;
import com.kilivana.common.exception.AuthenticationFailedException;
import com.kilivana.common.exception.BusinessConflictException;
import com.kilivana.common.exception.InvalidTokenException;
import com.kilivana.common.exception.ResourceNotFoundException;
import com.kilivana.common.exception.ServiceUnavailableException;
import com.kilivana.common.exception.UnauthorizedOperationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.MethodValidationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        List<FieldValidationError> fieldErrors = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(this::toFieldError)
                .toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", request, fieldErrors);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiError> handleBind(BindException exception, HttpServletRequest request) {
        List<FieldValidationError> fieldErrors = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(this::toFieldError)
                .toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", request, fieldErrors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request) {
        List<FieldValidationError> fieldErrors = exception.getConstraintViolations()
                .stream()
                .map(violation -> new FieldValidationError(violation.getPropertyPath().toString(), violation.getMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", request, fieldErrors);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class,
            MissingPathVariableException.class,
            MissingServletRequestPartException.class,
            HandlerMethodValidationException.class,
            MethodValidationException.class
    })
    public ResponseEntity<ApiError> handleMalformedRequest(Exception exception, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request could not be understood", request, List.of());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleMaxUpload(
            MaxUploadSizeExceededException exception,
            HttpServletRequest request) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "Uploaded content is too large", request, List.of());
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiError> handleMultipart(MultipartException exception, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "MULTIPART_ERROR", "Multipart request is invalid", request, List.of());
    }

    @ExceptionHandler(InvalidMediaException.class)
    public ResponseEntity<ApiError> handleRejectedMedia(InvalidMediaException exception, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_MEDIA", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(MediaStorageException.class)
    public ResponseEntity<ApiError> handleInvalidMedia(MediaStorageException exception, HttpServletRequest request) {
        // 503, not 400, and only for a genuine storage fault: an unwritable volume or a full
        // disk is the server's problem, so a 400 would tell the client its request was
        // malformed, which will never be true and will never become true on retry. 503 is
        // what tells a load balancer to back off. A request the client itself got wrong is
        // InvalidMediaException and is answered 400 above.
        return build(HttpStatus.SERVICE_UNAVAILABLE, "INVALID_MEDIA", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException exception,
            HttpServletRequest request) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "HTTP method is not supported", request, List.of());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException exception, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException exception, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found", request, List.of());
    }

    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<ApiError> handleAuthentication(
            AuthenticationFailedException exception,
            HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_FAILED", "Authentication failed", request, List.of());
    }

    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<ApiError> handleInvalidToken(InvalidTokenException exception, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_TOKEN", "Invalid or expired token", request, List.of());
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiError> handleBadCredentials(BadCredentialsException exception, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_FAILED", "Invalid credentials", request, List.of());
    }

    /**
     * 429 with a {@code Retry-After}, because the caller is not wrong and retrying sooner is
     * pointless rather than merely inadvisable. The body is the same {@link ApiError} every other
     * branch returns; only the status and the extra header differ.
     *
     * <p>The message is fixed text carried by the exception, and the exception is thrown before
     * the account is ever looked up, so the response is byte-for-byte identical for an email that
     * exists and one that does not. Anything derived from the account itself — whether it exists,
     * whether it is active — would turn this into an account-enumeration oracle on a 429, which
     * is precisely what the fixed message exists to prevent.
     */
    /**
     * 503 for a dependency this request could not proceed without.
     *
     * <p>Distinct from the catch-all {@code Exception} branch, which answers 500. The
     * difference matters to a caller: 500 says this request was broken, 503 says try again, and
     * for a Redis ticket store or a JWKS fetch that reach the client an absolute promise never
     * to retry would cost a user a signup they had already started.
     *
     * <p>The message is the fixed text the exception carries, which names nothing about the
     * dependency that failed beyond the sentence itself.
     */
    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<ApiError> handleServiceUnavailable(
            ServiceUnavailableException exception,
            HttpServletRequest request) {
        return build(
                HttpStatus.SERVICE_UNAVAILABLE,
                "SERVICE_UNAVAILABLE",
                exception.getMessage(),
                request,
                List.of(),
                Map.of("Retry-After", "5"));
    }

    @ExceptionHandler(AccountLoginThrottledException.class)
    public ResponseEntity<ApiError> handleAccountLoginThrottled(
            AccountLoginThrottledException exception,
            HttpServletRequest request) {
        return build(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_ATTEMPTS_EXCEEDED", exception.getMessage(),
                request, List.of(), Map.of("Retry-After", Long.toString(exception.retryAfterSeconds())));
    }

    @ExceptionHandler({BusinessConflictException.class})
    public ResponseEntity<ApiError> handleConflict(RuntimeException exception, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "BUSINESS_CONFLICT", exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(
            OptimisticLockingFailureException exception,
            HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", "The resource was modified concurrently", request, List.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(
            DataIntegrityViolationException exception,
            HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "DATA_CONFLICT", "The request conflicts with existing data", request, List.of());
    }

    @ExceptionHandler({UnauthorizedOperationException.class, AccessDeniedException.class})
    public ResponseEntity<ApiError> handleForbidden(RuntimeException exception, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to perform this action", request, List.of());
    }

    /**
     * Catches everything unhandled — optimistic-lock failures, constraint violations, connection
     * drops — and returns a deliberately opaque body. That opacity is correct for the caller:
     * no stack trace, SQL, or internal path ever crosses the boundary.
     *
     * <p>But it used to discard the exception on the way out, so a genuine server fault
     * produced a 500 with nothing in any log. The failure was invisible to whoever had to
     * diagnose it, which is the worst possible combination: the response is safely generic
     * and the log is completely silent. The exception is now logged at ERROR with the method
     * and path so an operator can find it by request id; the response body is unchanged.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Unhandled exception on {} {} (requestId={})",
                request.getMethod(),
                request.getRequestURI(),
                MDC.get(RequestContextFilter.REQUEST_ID),
                exception);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred", request, List.of());
    }

    private FieldValidationError toFieldError(FieldError fieldError) {
        return new FieldValidationError(fieldError.getField(), fieldError.getDefaultMessage());
    }

    private ResponseEntity<ApiError> build(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request,
            List<FieldValidationError> fieldErrors) {
        return build(status, code, message, request, fieldErrors, Map.of());
    }

    private ResponseEntity<ApiError> build(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request,
            List<FieldValidationError> fieldErrors,
            Map<String, String> extraHeaders) {
        String requestId = MDC.get(RequestContextFilter.REQUEST_ID);
        if (requestId == null || requestId.isBlank()) {
            requestId = (String) request.getAttribute(RequestContextFilter.REQUEST_ID);
        }
        if (requestId == null || requestId.isBlank()) {
            requestId = request.getHeader(RequestContextFilter.REQUEST_ID_HEADER);
        }
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        ApiError payload = new ApiError(
                code,
                message,
                status.value(),
                request.getRequestURI(),
                requestId,
                OffsetDateTime.now(),
                fieldErrors);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
                .header(RequestContextFilter.REQUEST_ID_HEADER, requestId);
        extraHeaders.forEach(builder::header);
        return builder.body(payload);
    }
}

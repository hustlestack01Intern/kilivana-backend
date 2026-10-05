package com.kilivana.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.kilivana.common.exception.AccountLoginThrottledException;
import com.kilivana.common.api.GlobalExceptionHandler;
import com.kilivana.media.InvalidMediaException;
import com.kilivana.media.MediaStorageException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void duplicateKeysAndOptimisticLocksUseSafeErrors() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/orders");
        request.addHeader("X-Request-Id", "error-1");

        var duplicate = handler.handleDataIntegrity(
                new DataIntegrityViolationException("jdbc password secret"),
                request);
        var optimistic = handler.handleOptimisticLock(
                new OptimisticLockingFailureException("version secret"),
                request);

        assertThat(duplicate.getStatusCode().value()).isEqualTo(409);
        assertThat(duplicate.getBody().message()).doesNotContain("jdbc", "password", "secret");
        assertThat(optimistic.getStatusCode().value()).isEqualTo(409);
        assertThat(optimistic.getBody().message()).doesNotContain("version");
    }

    @Test
    void multipartLimitUsesA413Envelope() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/inspections/1/photos");

        var response = handler.handleMaxUpload(new MaxUploadSizeExceededException(10L), request);

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(response.getBody().code()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo(response.getBody().requestId());
    }

    @Test
    @DisplayName("a storage failure is a server fault, so it answers 503 rather than blaming the request")
    void mediaStorageFailureIsAServerFault() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/inspections/1/photos");
        request.addHeader("X-Request-Id", "media-1");

        // The response body shape is unchanged; only the status is. A 400 told the client
        // its upload was malformed, which is never true when the cause is an unwritable
        // volume, and it told a load balancer the request was worth resending elsewhere.
        var response = handler.handleInvalidMedia(
                new MediaStorageException("Failed to store uploaded file", new IOException("disk full")),
                request);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().code()).isEqualTo("INVALID_MEDIA");
        assertThat(response.getBody().status()).isEqualTo(503);
        assertThat(response.getBody().path()).isEqualTo("/api/v1/inspections/1/photos");
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo("media-1");
    }

    @Test
    @DisplayName("a rejected upload is the caller's fault, so it answers 400 and never 503")
    void rejectedMediaIsAClientFault() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/inspections/1/photos");
        request.addHeader("X-Request-Id", "media-2");

        // 503 here told the client to retry an unsupported file type, and a load balancer
        // to pull a healthy instance out of rotation, for a condition retrying never fixes.
        var response = handler.handleRejectedMedia(
                new InvalidMediaException("Unsupported image type"),
                request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().code()).isEqualTo("INVALID_MEDIA");
        assertThat(response.getBody().status()).isEqualTo(400);
        assertThat(response.getBody().message()).isEqualTo("Unsupported image type");
        assertThat(response.getBody().path()).isEqualTo("/api/v1/inspections/1/photos");
        assertThat(response.getBody().requestId()).isEqualTo("media-2");
        assertThat(response.getBody().fieldErrors()).isEmpty();
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo("media-2");
    }

    @Test
    @DisplayName("a spent login budget answers 429 with a Retry-After, not a credential error")
    void aThrottledLoginUsesTheStandardErrorEnvelope() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.addHeader("X-Request-Id", "login-throttle-1");

        var response = handler.handleAccountLoginThrottled(
                new AccountLoginThrottledException(120), request);

        // 401 here would say "your credentials are wrong", which is both false and useless:
        // the caller's credentials may be perfect and the budget simply spent.
        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getBody().code()).isEqualTo("LOGIN_ATTEMPTS_EXCEEDED");
        assertThat(response.getBody().status()).isEqualTo(429);
        assertThat(response.getBody().path()).isEqualTo("/api/v1/auth/login");
        assertThat(response.getBody().fieldErrors()).isEmpty();
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("120");
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo("login-throttle-1");
    }

    @Test
    @DisplayName("a zero or negative retry-after is still a positive number of seconds")
    void theRetryAfterIsNeverZeroOrNegative() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");

        assertThat(handler.handleAccountLoginThrottled(new AccountLoginThrottledException(0), request)
                .getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(handler.handleAccountLoginThrottled(new AccountLoginThrottledException(-30), request)
                .getHeaders().getFirst("Retry-After")).isEqualTo("1");
    }

    // mediaFaultFamiliesDoNotOverlap used to live here as an isAssignableFrom pair. It passed
    // identically whether or not any @ExceptionHandler was registered, and the registry choice
    // it was standing in for is what @WebMvcTest MediaFaultResolutionTest now exercises through
    // DispatcherServlet: a real controller raising each exception, read back as 400 vs 503.
}

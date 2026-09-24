package com.kilivana.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.kilivana.common.api.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
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
}

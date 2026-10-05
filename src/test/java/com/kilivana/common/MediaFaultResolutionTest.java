package com.kilivana.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kilivana.common.api.GlobalExceptionHandler;
import com.kilivana.media.InvalidMediaException;
import com.kilivana.media.MediaStorageException;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Proves that Spring resolves the two media fault families to the two different handlers, by
 * raising each one from a real controller and reading the status off the response.
 *
 * <p>This replaces an assertion that {@code MediaStorageException} and
 * {@code InvalidMediaException} are not assignable from one another. That assertion passed
 * identically whether or not a single {@code @ExceptionHandler} was registered, or registered
 * against the right type: it described the type hierarchy, not the dispatch. The behaviour that
 * matters is that an unsupported upload is answered 400 and an unwritable volume 503, and only
 * a request through the {@code DispatcherServlet}'s exception resolver can show which one
 * happened.
 *
 * <p>The MVC slice is built standalone with the real {@code GlobalExceptionHandler} registered
 * as a controller advice, deliberately rather than with {@code @WebMvcTest}. The sliced context
 * pulls in {@code AuditLogInterceptor}, whose {@code AuditLogService} dependency is not part of
 * the slice, so the context dies at startup with an {@code UnsatisfiedDependencyException} and
 * the test asserts nothing. Standalone keeps the only two things that matter here — the
 * controller and the real advice — and drops the bean graph that was never the subject.
 */
class MediaFaultResolutionTest {

    private MockMvc mockMvc;

    @BeforeEach
    void buildMvc() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FaultRaisingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("a rejected upload reaches the 400 handler, not the 503 one")
    void rejectedMediaIsResolvedToTheClientFaultHandler() throws Exception {
        mockMvc.perform(get("/faults/rejected-media"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_MEDIA"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Unsupported image type"));
    }

    @Test
    @DisplayName("a storage fault reaches the 503 handler, not the 400 one")
    void storageFailureIsResolvedToTheServerFaultHandler() throws Exception {
        mockMvc.perform(get("/faults/storage-failure"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INVALID_MEDIA"))
                .andExpect(jsonPath("$.status").value(503));
    }

    @Test
    @DisplayName("neither handler catches the other family's exception")
    void theTwoHandlersDoNotCollapseIntoOne() throws Exception {
        // The 400 handler must not have been reached by the 503 case, and vice versa. If one
        // family were a supertype of the other the second handler would be dead code and this
        // is where that would show: the status would be the same for both requests.
        int rejected = mockMvc.perform(get("/faults/rejected-media"))
                .andReturn().getResponse().getStatus();
        int storage = mockMvc.perform(get("/faults/storage-failure"))
                .andReturn().getResponse().getStatus();

        assertThat(rejected).isEqualTo(400);
        assertThat(storage).isEqualTo(503);
    }

    @RestController
    static class FaultRaisingController {

        @GetMapping("/faults/rejected-media")
        public String rejectedMedia() {
            throw new InvalidMediaException("Unsupported image type");
        }

        @GetMapping("/faults/storage-failure")
        public String storageFailure() {
            throw new MediaStorageException("Failed to store uploaded file", new IOException("no space left"));
        }
    }
}
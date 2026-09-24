package com.kilivana.common.api;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

/**
 * Sends the API root to the interactive OpenAPI/Swagger UI when docs are enabled,
 * otherwise to a JSON summary of the service.
 */
@RestController
public class RootController {

    @Value("${springdoc.api-docs.enabled:false}")
    private boolean apiDocsEnabled;

    @GetMapping("/")
    public Object root() {
        if (apiDocsEnabled) {
            return new RedirectView("/swagger-ui.html");
        }
        return ResponseEntity.ok(Map.of(
                "name", "kilivana-backend",
                "api", "/api/v1",
                "docs", List.of("/api-docs", "/swagger-ui.html"),
                "health", "/actuator/health"));
    }
}
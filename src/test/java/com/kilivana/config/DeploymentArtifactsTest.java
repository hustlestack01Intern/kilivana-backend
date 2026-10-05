package com.kilivana.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Checks on deployment artifacts that have no runtime behaviour to assert.
 *
 * <p>Deliberately small. An earlier revision of this file asserted that the Dockerfile
 * "contains {@code su-exec}", that the entrypoint "contains {@code exec su-exec appuser}", that
 * the image "contains {@code ENV SPRING_PROFILES_ACTIVE=prod}", that {@code railway.toml} does
 * not exist, and that the spec has no {@code /api/v1/webhooks} path. Every one of those is a
 * statement about a file's characters that passes identically whether or not the behaviour
 * works, and two of them — the privilege drop and the profile activation — are now pinned
 * behaviourally elsewhere:
 *
 * <ul>
 *   <li>{@link EntrypointScriptTest} executes the shipped script and asserts that the chown
 *       runs as root, is skipped as non-root, and drops privileges, with the guard refusals
 *       proven by exit code.
 *   <li>{@code ForwardedHeaderBucketSpoofingIntegrationTest} boots under the prod profile, so
 *       the profile being genuinely active is load-bearing there rather than asserted here.
 * </ul>
 *
 * <p>The spec is the published contract, and its contract with a client is that it parses and
 * that it does not describe endpoints that no longer exist. That has no runtime behaviour in
 * this repository — the running application is not driven by the file — so it is checked as a
 * file, and is the only thing in this class.
 */
class DeploymentArtifactsTest {

    private static final Path ROOT = Path.of("").toAbsolutePath();

    @Test
    @DisplayName("the published spec no longer advertises the deleted unsigned payment webhook")
    void specNoLongerAdvertisesThePaymentWebhook() throws IOException {
        JsonNode spec = new ObjectMapper().readTree(ROOT.resolve("docs/spec/spec.json").toFile());

        List<String> webhookPaths = new ArrayList<>();
        spec.path("paths").fieldNames().forEachRemaining(path -> {
            if (path.startsWith("/api/v1/webhooks")) {
                webhookPaths.add(path);
            }
        });

        assertThat(webhookPaths).isEmpty();
        assertThat(spec.path("components").path("schemas").has("PaymentWebhookRequest")).isFalse();
    }

    @Test
    @DisplayName("the spec still parses as JSON, so a bad edit cannot reach the repository")
    void specIsValidJson() throws IOException {
        String raw = read("docs/spec/spec.json");

        assertThat(new ObjectMapper().readTree(raw).path("openapi").asText()).isEqualTo("3.0.1");
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(ROOT.resolve(relativePath), StandardCharsets.UTF_8);
    }
}

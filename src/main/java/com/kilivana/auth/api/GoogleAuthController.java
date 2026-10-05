package com.kilivana.auth.api;

import com.kilivana.auth.service.GoogleAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Google sign-in surface.
 *
 * <p>Three unauthenticated POSTs and one authenticated POST, and the split is the security
 * property. Everything that can create, mail or authenticate on behalf of an unauthenticated
 * caller lives behind an exact-path rate limit in {@code RateLimitFilter}, not behind the
 * catch-all that protects the credential endpoints.
 *
 * <p>The controller holds no logic and makes no decision. It does not know whether an account
 * exists, whether the address is proven, or which of the three unauthenticated calls is
 * appropriate — that is {@link GoogleAuthService}'s, and keeping it there is what lets the
 * response-shape guarantee be tested as one unit.
 */
@RestController
@RequestMapping("/api/v1/auth/google")
@ConditionalOnProperty(prefix = "kilivana.security.google", name = "enabled", havingValue = "true")
@Tag(name = "Google Authentication", description = "Sign in and sign up with a Google identity")
public class GoogleAuthController {

    private final GoogleAuthService googleAuthService;

    public GoogleAuthController(GoogleAuthService googleAuthService) {
        this.googleAuthService = googleAuthService;
    }

    @Operation(summary = "Exchange a Google ID token for a session, an onboarding ticket, or a "
            + "verification requirement",
            description = "Always answers 200. The status field, never the response code, says what "
                    + "happened, and it never says whether an account already exists.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "One of AUTHENTICATED, ONBOARDING_REQUIRED or "
                + "EMAIL_VERIFICATION_REQUIRED"),
        @ApiResponse(responseCode = "400", description = "Malformed request"),
        @ApiResponse(responseCode = "401", description = "The ID token did not verify. One fixed body for "
                + "every reason"),
        @ApiResponse(responseCode = "429", description = "Rate limited"),
        @ApiResponse(responseCode = "503", description = "A dependency the handshake needs cannot answer")
    })
    @PostMapping
    public ResponseEntity<GoogleHandshakeResponse> handshake(@Valid @RequestBody GoogleAuthRequest request) {
        return ResponseEntity.ok(googleAuthService.handshake(request));
    }

    @Operation(summary = "Exchange a verification token for a proven onboarding ticket",
            description = "Requires both the token from the mailbox and the unproven ticket from the "
                    + "handshake. Neither is sufficient alone.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "A ticket that can complete onboarding"),
        @ApiResponse(responseCode = "400", description = "The pair was rejected. One answer for unknown, "
                + "expired, spent and mismatched"),
        @ApiResponse(responseCode = "429", description = "Rate limited"),
        @ApiResponse(responseCode = "503", description = "The ticket store or the limiter cannot answer")
    })
    @PostMapping("/verify-email")
    public ResponseEntity<OnboardingTicketResponse> verifyEmail(
            @Valid @RequestBody EmailVerificationRequest request) {
        return ResponseEntity.ok(googleAuthService.verifyEmail(request));
    }

    @Operation(summary = "Create the account for a proven onboarding ticket",
            description = "Runs the ordinary signup rules. Returns the tokens for the new account.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Account created"),
        @ApiResponse(responseCode = "400", description = "The ticket was rejected"),
        @ApiResponse(responseCode = "409", description = "The address is not available to this account. "
                + "One answer whether it is taken or merely unproven"),
        @ApiResponse(responseCode = "429", description = "Rate limited"),
        @ApiResponse(responseCode = "503", description = "The ticket store cannot answer")
    })
    @PostMapping("/onboard")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse onboard(@Valid @RequestBody GoogleOnboardingRequest request) {
        return googleAuthService.onboard(request);
    }

    @Operation(summary = "Bind a Google identity to the account you are signed in to",
            description = "Never merges. An identity already bound elsewhere is refused.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Linked, with fresh tokens"),
        @ApiResponse(responseCode = "401", description = "Authentication failed, including an identity "
                + "already bound elsewhere"),
        @ApiResponse(responseCode = "429", description = "Rate limited"),
        @ApiResponse(responseCode = "503", description = "The limiter cannot answer")
    })
    @PostMapping("/link")
    public AuthResponse link(@Valid @RequestBody AccountLinkRequest request, HttpServletRequest httpRequest) {
        // getRemoteAddr, the same source AuditLogInterceptor uses. The forwarded address is
        // attacker-controlled text and is resolved by ClientIpResolver where it is load-bearing
        // for a limit; for an audit note the socket peer is enough, and logging a header
        // nobody has verified would put a client-supplied string into the audit trail.
        return googleAuthService.linkAccount(request, httpRequest.getRemoteAddr());
    }
}
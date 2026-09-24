package com.kilivana.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kilivana.users.domain.User;
import com.kilivana.users.domain.UserRole;
import com.kilivana.users.domain.UserStatus;
import io.jsonwebtoken.JwtException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class JwtServiceTest {

    @Test
    void rejectsMissingOrWeakSigningSecretsAtStartup() {
        assertThatThrownBy(() -> new JwtService(new JwtProperties(
                "issuer",
                "audience",
                15,
                14,
                "")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtService(new JwtProperties(
                "issuer",
                "audience",
                15,
                14,
                "short-secret")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void validatesIssuerAndAudience() {
        String secret = "a-very-long-high-entropy-secret-value-123456";
        JwtService issuer = new JwtService(new JwtProperties("issuer", "audience", 15, 14, secret));
        JwtService otherAudience = new JwtService(new JwtProperties("issuer", "other", 15, 14, secret));
        User user = new User(
                "buyer@example.com",
                "hash",
                "Buyer",
                "+254700000000",
                UserRole.BUYER,
                UserStatus.ACTIVE);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        String token = issuer.generateAccessToken(user);

        assertThat(issuer.parse(token).getAudience()).containsExactly("audience");
        assertThatThrownBy(() -> otherAudience.parse(token)).isInstanceOf(JwtException.class);
    }
}

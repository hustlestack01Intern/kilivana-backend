package com.kilivana.security;

import com.kilivana.users.domain.*;
import com.kilivana.users.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class InspectorAuthorizationTest {
    @ParameterizedTest
    @EnumSource(VerificationStatus.class)
    void onlyVerifiedInspectorsHavePrivilegesAndInspectorAuthority(VerificationStatus status) {
        for (UserRole role : UserRole.values()) {
            AuthenticatedUser principal = new AuthenticatedUser(UUID.randomUUID(), "a@example.com", "hash", role, status);
            boolean allowed = role == UserRole.INSPECTOR && status == VerificationStatus.VERIFIED;
            assertThat(principal.isVerifiedInspector()).isEqualTo(allowed);
            assertThat(principal.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_INSPECTOR"))).isEqualTo(allowed);
            assertThat(principal.isEnabled()).isTrue();
        }
    }

    @ParameterizedTest
    @EnumSource(VerificationStatus.class)
    void authenticationLoadsVerificationFromDatabaseWithoutRejectingPendingAccounts(VerificationStatus status) {
        UserRepository users = mock(UserRepository.class);
        User user = new User("a@example.com", "hash", "Inspector", "phone", UserRole.INSPECTOR, UserStatus.ACTIVE);
        user.markVerification(status);
        when(users.findByEmailIgnoreCase(user.getEmail())).thenReturn(Optional.of(user));
        AuthenticatedUser principal = (AuthenticatedUser) new ApplicationUserDetailsService(users).loadUserByUsername(user.getEmail());
        assertThat(principal.getRole()).isEqualTo(UserRole.INSPECTOR);
        assertThat(principal.isVerifiedInspector()).isEqualTo(status == VerificationStatus.VERIFIED);
    }
}

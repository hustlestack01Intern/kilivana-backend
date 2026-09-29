package com.kilivana.security;

import com.kilivana.users.domain.UserRole;
import com.kilivana.users.domain.VerificationStatus;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public class AuthenticatedUser implements UserDetails {

    private final UUID id;
    private final String email;
    private final String passwordHash;
    private final UserRole role;
    private final VerificationStatus verificationStatus;

    public AuthenticatedUser(UUID id, String email, String passwordHash, UserRole role) {
        this(id, email, passwordHash, role, VerificationStatus.UNVERIFIED);
    }

    public AuthenticatedUser(UUID id, String email, String passwordHash, UserRole role, VerificationStatus verificationStatus) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.verificationStatus = verificationStatus;
    }

    public UUID getId() {
        return id;
    }

    public UserRole getRole() {
        return role;
    }

    /** Uses server state loaded for this request, never a verification claim supplied by the client. */
    public boolean isVerifiedInspector() {
        return role == UserRole.INSPECTOR && verificationStatus == VerificationStatus.VERIFIED;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        if (role == UserRole.INSPECTOR && !isVerifiedInspector()) {
            return List.of();
        }
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}

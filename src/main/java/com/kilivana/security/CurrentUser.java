package com.kilivana.security;

import com.kilivana.common.exception.UnauthorizedOperationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentUser {

    public AuthenticatedUser required() {
        AuthenticatedUser user = maybe();
        if (user == null) {
            throw new UnauthorizedOperationException("Authentication is required");
        }
        return user;
    }

    public AuthenticatedUser maybe() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            return null;
        }
        return user;
    }
}

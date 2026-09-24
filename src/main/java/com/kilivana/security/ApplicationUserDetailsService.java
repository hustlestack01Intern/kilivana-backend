package com.kilivana.security;

import com.kilivana.users.domain.UserStatus;
import com.kilivana.users.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class ApplicationUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public ApplicationUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return userRepository.findByEmailIgnoreCase(username)
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .map(user -> new AuthenticatedUser(user.getId(), user.getEmail(), user.getPasswordHash(), user.getRole()))
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }
}

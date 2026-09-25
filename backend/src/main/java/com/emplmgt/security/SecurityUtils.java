package com.emplmgt.security;

import com.emplmgt.entity.Role;
import com.emplmgt.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class SecurityUtils {

    private final UserDetailsService userDetailsService;
    private final AppUserDetailsService appUserDetailsService;
    private final EmployeeRepository employeeRepository;

    @Value("${application.cors.allowed-origins}")
    private String allowedOrigins;

    public Optional<String> currentEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return Optional.empty();
        }
        return Optional.of(auth.getName());
    }

    public Long currentUserId() {
        return currentEmail().map(email -> appUserDetailsService.loadPrincipal(email).getUserId()).orElse(null);
    }

    public boolean isAdmin() {
        return currentEmail()
                .map(email -> appUserDetailsService.loadPrincipal(email).getRole() == Role.ADMIN)
                .orElse(false);
    }

    /**
     * Team (department) id of the current user's employee profile, or null when
     * there is no authenticated user / no employee profile / no team assigned.
     */
    public Long currentTeamId() {
        Long userId = currentUserId();
        if (userId == null) {
            return null;
        }
        return employeeRepository.findByUserId(userId)
                .map(employee -> employee.getDepartment() != null ? employee.getDepartment().getId() : null)
                .orElse(null);
    }

    public String[] allowedOriginsArray() {
        return allowedOrigins == null ? new String[0] : allowedOrigins.split(",");
    }
}
package com.lrtaxi.fleet.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/** The signed-in user, read from the validated JWT. */
public record CurrentUser(long id, String email, List<String> roles) {

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean canManage() {
        return hasRole("ADMIN") || hasRole("MANAGER");
    }

    /** Null outside an authenticated request (e.g. scheduled jobs). */
    public static CurrentUser get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            return null;
        }
        List<String> roles = jwt.getClaimAsStringList("roles");
        return new CurrentUser(Long.parseLong(jwt.getSubject()), jwt.getClaimAsString("email"), roles == null ? List.of() : roles);
    }

    public static Long idOrNull() {
        CurrentUser u = get();
        return u == null ? null : u.id();
    }

    public static CurrentUser require() {
        CurrentUser u = get();
        if (u == null) {
            throw new IllegalStateException("No authenticated user");
        }
        return u;
    }
}

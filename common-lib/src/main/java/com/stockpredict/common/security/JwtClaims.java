package com.stockpredict.common.security;

import com.stockpredict.common.plan.Plan;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Verified JWT claims. Installed as the {@code Authentication} principal so
 * controllers can read the user id, plan and roles without re-parsing tokens.
 */
public record JwtClaims(
        String subject,
        String email,
        Plan plan,
        Set<String> roles,
        Instant issuedAt,
        Instant expiresAt,
        Map<String, Object> claims) {

    public JwtClaims {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        claims = claims == null ? Map.of() : Map.copyOf(claims);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }
}

package com.stockpredict.common.security;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.stockpredict.common.error.ApiException;
import com.stockpredict.common.error.ErrorCode;
import com.stockpredict.common.plan.Plan;
import com.stockpredict.jwt.JwtVerificationCore;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Verifies RS256 access tokens locally against a JWKS source (or an in-process
 * key set in tests) and maps the result to {@link JwtClaims}. Parsing,
 * signature and time/issuer/audience checks live in the shared framework-free
 * {@link JwtVerificationCore} (D7).
 */
public class JwtVerifier {

    private static final Duration DEFAULT_CLOCK_SKEW = JwtVerificationCore.DEFAULT_CLOCK_SKEW;

    private final JwtVerificationCore core;

    public JwtVerifier(URI jwksUri, String issuer, String audience) {
        this(jwksUri, issuer, audience, Clock.systemUTC(), DEFAULT_CLOCK_SKEW);
    }

    public JwtVerifier(URI jwksUri, String issuer, String audience, Clock clock, Duration clockSkew) {
        this.core = JwtVerificationCore.remote(jwksUri, issuer, audience, clock, clockSkew);
    }

    public JwtVerifier(JWKSource<SecurityContext> jwkSource, String issuer, String audience, Clock clock) {
        this(jwkSource, issuer, audience, clock, DEFAULT_CLOCK_SKEW);
    }

    public JwtVerifier(JWKSource<SecurityContext> jwkSource, String issuer, String audience,
                       Clock clock, Duration clockSkew) {
        this.core = new JwtVerificationCore(jwkSource, issuer, audience, clock, clockSkew);
    }

    public JwtClaims verify(String token) {
        JWTClaimsSet claims;
        try {
            claims = core.verify(token).claims();
        } catch (JwtVerificationCore.JwtVerificationException ex) {
            ErrorCode code = "TOKEN_EXPIRED".equals(ex.code())
                    ? ErrorCode.TOKEN_EXPIRED
                    : ErrorCode.TOKEN_INVALID;
            throw new ApiException(code, ex.getMessage(), ex);
        }

        Instant issuedAt = claims.getIssueTime() == null ? null : claims.getIssueTime().toInstant();
        Map<String, Object> all = new LinkedHashMap<>(claims.getClaims());
        return new JwtClaims(claims.getSubject(), stringClaim(claims, "email"),
                Plan.from(stringClaim(claims, "plan")), rolesClaim(claims), issuedAt,
                claims.getExpirationTime().toInstant(), all);
    }

    private static String stringClaim(JWTClaimsSet claims, String name) {
        Object value = claims.getClaim(name);
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Number number) {
            return number.toString();
        }
        return null;
    }

    private static Set<String> rolesClaim(JWTClaimsSet claims) {
        Object value = claims.getClaim("roles");
        Set<String> roles = new LinkedHashSet<>();
        if (value instanceof List<?> list) {
            list.forEach(role -> roles.add(String.valueOf(role)));
        } else if (value instanceof String text && !text.isBlank()) {
            for (String role : text.split(",")) {
                if (!role.isBlank()) {
                    roles.add(role.trim());
                }
            }
        }
        return roles;
    }
}

package com.stockpredict.gateway.security;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.stockpredict.jwt.JwtVerificationCore;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * RS256 verifier for the gateway pre-check. Parsing, signature and
 * time/issuer/audience checks are delegated to the shared framework-free
 * {@link JwtVerificationCore}, so the edge and the services cannot drift (D7).
 */
public class GatewayJwtVerifier {

    private final JwtVerificationCore core;

    public GatewayJwtVerifier(URI jwksUri, String issuer, String audience,
                              Clock clock, Duration clockSkew) {
        this.core = JwtVerificationCore.remote(jwksUri, issuer, audience, clock, clockSkew);
    }

    public GatewayJwtVerifier(JWKSource<SecurityContext> jwkSource, String issuer, String audience,
                              Clock clock, Duration clockSkew) {
        this.core = new JwtVerificationCore(jwkSource, issuer, audience, clock, clockSkew);
    }

    public GatewayClaims verify(String token) {
        JWTClaimsSet claims;
        try {
            claims = core.verify(token).claims();
        } catch (JwtVerificationCore.JwtVerificationException ex) {
            throw new GatewayAuthException(ex.getMessage(), ex.code());
        }

        String plan = stringClaim(claims, "plan");
        String email = stringClaim(claims, "email");
        return new GatewayClaims(claims.getSubject(), email == null ? "" : email,
                plan == null || plan.isBlank() ? "FREE" : plan, roles(claims));
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

    private static String roles(JWTClaimsSet claims) {
        Object value = claims.getClaim("roles");
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
        }
        return value == null ? "" : String.valueOf(value);
    }

    /** Claims trusted by downstream services (forwarded as headers). */
    public record GatewayClaims(String subject, String email, String plan, String roles) {
        public Set<String> roleSet() {
            return roles == null || roles.isBlank() ? Set.of() : Set.of(roles.split(","));
        }
    }

    public static class GatewayAuthException extends RuntimeException {

        private final String code;

        public GatewayAuthException(String message) {
            this(message, "TOKEN_INVALID");
        }

        public GatewayAuthException(String message, String code) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}

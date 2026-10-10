package com.stockpredict.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.KeyType;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.MalformedURLException;
import java.net.URI;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Framework-free RS256 verification core shared by the servlet services
 * (common-lib {@code JwtVerifier}) and the reactive gateway
 * ({@code GatewayJwtVerifier}). Contains no Spring or servlet types, so the two
 * wrappers cannot drift apart (D7).
 */
public final class JwtVerificationCore {

    public static final Duration DEFAULT_CLOCK_SKEW = Duration.ofSeconds(30);

    private final JWKSource<SecurityContext> jwkSource;
    private final String issuer;
    private final String audience;
    private final Clock clock;
    private final Duration clockSkew;

    public JwtVerificationCore(JWKSource<SecurityContext> jwkSource, String issuer, String audience,
                               Clock clock, Duration clockSkew) {
        this.jwkSource = jwkSource;
        this.issuer = issuer;
        this.audience = audience;
        this.clock = clock;
        this.clockSkew = clockSkew == null ? DEFAULT_CLOCK_SKEW : clockSkew;
    }

    /** Builds a core backed by a remote JWKS endpoint (nimbus caching applies). */
    public static JwtVerificationCore remote(URI jwksUri, String issuer, String audience,
                                             Clock clock, Duration clockSkew) {
        try {
            return new JwtVerificationCore(JWKSourceBuilder.create(jwksUri.toURL()).build(),
                    issuer, audience, clock, clockSkew);
        } catch (MalformedURLException ex) {
            throw new IllegalArgumentException("Invalid JWKS URI: " + jwksUri, ex);
        }
    }

    public VerifiedToken verify(String token) {
        if (token == null || token.isBlank()) {
            throw new JwtVerificationException("Missing token");
        }
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(token);
        } catch (ParseException ex) {
            throw new JwtVerificationException("Malformed token");
        }
        if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
            throw new JwtVerificationException("Unsupported signing algorithm");
        }
        if (!verifySignature(jwt)) {
            throw new JwtVerificationException("Signature verification failed");
        }
        JWTClaimsSet claims;
        try {
            claims = jwt.getJWTClaimsSet();
        } catch (ParseException ex) {
            throw new JwtVerificationException("Malformed token claims");
        }
        validate(claims);
        return new VerifiedToken(claims.getSubject(), claims);
    }

    private void validate(JWTClaimsSet claims) {
        Instant now = clock.instant();
        Date expiry = claims.getExpirationTime();
        if (expiry == null) {
            throw new JwtVerificationException("Token has no expiry");
        }
        if (expiry.toInstant().plus(clockSkew).isBefore(now)) {
            throw new JwtVerificationException("Token expired", "TOKEN_EXPIRED");
        }
        Date notBefore = claims.getNotBeforeTime();
        if (notBefore != null && notBefore.toInstant().minus(clockSkew).isAfter(now)) {
            throw new JwtVerificationException("Token is not yet valid");
        }
        Date issueTime = claims.getIssueTime();
        if (issueTime != null && issueTime.toInstant().minus(clockSkew).isAfter(now)) {
            throw new JwtVerificationException("Token issued in the future");
        }
        if (issuer != null && !issuer.isBlank() && !issuer.equals(claims.getIssuer())) {
            throw new JwtVerificationException("Issuer mismatch");
        }
        if (audience != null && !audience.isBlank()) {
            List<String> audiences = claims.getAudience();
            if (audiences == null || !audiences.contains(audience)) {
                throw new JwtVerificationException("Audience mismatch");
            }
        }
        if (claims.getSubject() == null || claims.getSubject().isBlank()) {
            throw new JwtVerificationException("Subject missing");
        }
    }

    private boolean verifySignature(SignedJWT jwt) {
        try {
            JWKMatcher.Builder matcher = new JWKMatcher.Builder().keyType(KeyType.RSA);
            String keyId = jwt.getHeader().getKeyID();
            if (keyId != null && !keyId.isBlank()) {
                matcher.keyID(keyId);
            }
            List<JWK> candidates = jwkSource.get(new JWKSelector(matcher.build()), null);
            for (JWK candidate : candidates) {
                if (!(candidate instanceof RSAKey rsaKey)) {
                    continue;
                }
                try {
                    if (jwt.verify(new RSASSAVerifier(rsaKey))) {
                        return true;
                    }
                } catch (JOSEException ignored) {
                    // try the next candidate key
                }
            }
        } catch (KeySourceException ex) {
            throw new JwtVerificationException("Unable to resolve signing key: " + ex.getMessage(), "TOKEN_INVALID");
        }
        return false;
    }

    /** Verified token: subject plus the full nimbus claims set. */
    public record VerifiedToken(String subject, JWTClaimsSet claims) {
    }

    /** Verification failure with a stable error code ({@code TOKEN_INVALID}/{@code TOKEN_EXPIRED}). */
    public static class JwtVerificationException extends RuntimeException {

        private final String code;

        public JwtVerificationException(String message) {
            this(message, "TOKEN_INVALID");
        }

        public JwtVerificationException(String message, String code) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}

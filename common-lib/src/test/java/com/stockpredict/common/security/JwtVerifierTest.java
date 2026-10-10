package com.stockpredict.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.stockpredict.common.error.ApiException;
import com.stockpredict.common.error.ErrorCode;
import com.stockpredict.common.plan.Plan;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class JwtVerifierTest {

    private static final String ISSUER = "https://auth.stockpredict.test";
    private static final String AUDIENCE = "stockpredict-api";
    private static final Instant NOW = Instant.parse("2026-06-10T12:00:00Z");

    private static RSAKey signingKey;
    private static JwtVerifier verifier;

    @BeforeAll
    static void setUp() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        JWKSource<SecurityContext> source =
                new ImmutableJWKSet<>(new JWKSet(signingKey.toPublicJWK()));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        verifier = new JwtVerifier(source, ISSUER, AUDIENCE, clock, Duration.ofSeconds(30));
    }

    private static String token(JWTClaimsSet.Builder builder) throws Exception {
        JWTClaimsSet claims = builder.build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    private static JWTClaimsSet.Builder validClaims() {
        return new JWTClaimsSet.Builder()
                .subject("user-123")
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .issueTime(Date.from(NOW))
                .expirationTime(Date.from(NOW.plus(Duration.ofMinutes(15))))
                .claim("email", "user@example.com")
                .claim("plan", "PREMIUM")
                .claim("roles", List.of("USER", "ADMIN"));
    }

    @Test
    void verifiesValidTokenAndMapsClaims() throws Exception {
        JwtClaims claims = verifier.verify(token(validClaims()));
        assertThat(claims.subject()).isEqualTo("user-123");
        assertThat(claims.email()).isEqualTo("user@example.com");
        assertThat(claims.plan()).isEqualTo(Plan.PREMIUM);
        assertThat(claims.roles()).containsExactlyInAnyOrder("USER", "ADMIN");
        assertThat(claims.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    }

    @Test
    void defaultsMissingPlanToFree() throws Exception {
        String jwt = token(validClaims().claim("plan", null));
        assertThat(verifier.verify(jwt).plan()).isEqualTo(Plan.FREE);
    }

    @Test
    void ignoresNonStringEmailClaim() throws Exception {
        String jwt = token(validClaims().claim("email", List.of("a", "b")));
        assertThat(verifier.verify(jwt).email()).isNull();
    }

    @Test
    void nonStringPlanFallsBackToFree() throws Exception {
        String jwt = token(validClaims().claim("plan", List.of("PREMIUM")));
        assertThat(verifier.verify(jwt).plan()).isEqualTo(Plan.FREE);
    }

    @Test
    void acceptsTokenExpiredWithinConfiguredClockSkew() throws Exception {
        JwtVerifier lenient = new JwtVerifier(
                new ImmutableJWKSet<>(new JWKSet(signingKey.toPublicJWK())),
                ISSUER, AUDIENCE, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(10));
        String jwt = token(validClaims().expirationTime(Date.from(NOW.minus(Duration.ofMinutes(5)))));

        assertThat(lenient.verify(jwt).subject()).isEqualTo("user-123");
        assertThatThrownBy(() -> verifier.verify(jwt))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.TOKEN_EXPIRED);
    }

    @Test
    void rejectsTokenIssuedInTheFuture() throws Exception {
        String jwt = token(validClaims().issueTime(Date.from(NOW.plus(Duration.ofMinutes(5)))));
        assertThatThrownBy(() -> verifier.verify(jwt))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.TOKEN_INVALID);
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        String jwt = token(validClaims().expirationTime(Date.from(NOW.minus(Duration.ofMinutes(1)))));
        assertThatThrownBy(() -> verifier.verify(jwt))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.TOKEN_EXPIRED);
    }

    @Test
    void rejectsWrongIssuer() throws Exception {
        String jwt = token(validClaims().issuer("https://evil.example.com"));
        assertThatThrownBy(() -> verifier.verify(jwt))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.TOKEN_INVALID);
    }

    @Test
    void rejectsWrongAudience() throws Exception {
        String jwt = token(validClaims().audience("someone-else"));
        assertThatThrownBy(() -> verifier.verify(jwt))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.TOKEN_INVALID);
    }

    @Test
    void rejectsTokenSignedByAnotherKey() throws Exception {
        RSAKey otherKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        JWTClaimsSet claims = validClaims().build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(otherKey));
        assertThatThrownBy(() -> verifier.verify(jwt.serialize()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.TOKEN_INVALID);
    }

    @Test
    void rejectsMalformedToken() {
        assertThatThrownBy(() -> verifier.verify("not-a-jwt"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.TOKEN_INVALID);
    }

    @Test
    void rejectsTokenWithoutExpiry() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("user-123")
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(signingKey));
        assertThatThrownBy(() -> verifier.verify(jwt.serialize()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.TOKEN_INVALID);
    }
}

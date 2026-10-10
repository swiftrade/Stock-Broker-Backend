package com.stockpredict.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class GatewayJwtVerifierTest {

    private static final String ISSUER = "https://auth.stockpredict.test";
    private static final String AUDIENCE = "stockpredict-api";
    private static final Instant NOW = Instant.parse("2026-06-10T12:00:00Z");

    private static RSAKey signingKey;
    private static GatewayJwtVerifier verifier;

    @BeforeAll
    static void setUp() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        verifier = new GatewayJwtVerifier(
                new ImmutableJWKSet<>(new JWKSet(signingKey.toPublicJWK())),
                ISSUER, AUDIENCE, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(30));
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
                .expirationTime(Date.from(NOW.plus(Duration.ofMinutes(15))))
                .claim("plan", "PREMIUM")
                .claim("roles", "USER,ADMIN");
    }

    @Test
    void verifiesValidToken() throws Exception {
        assertThat(verifier.verify(token(validClaims())).subject()).isEqualTo("user-123");
    }

    @Test
    void expiredTokenReportsTokenExpired() throws Exception {
        String jwt = token(validClaims().expirationTime(Date.from(NOW.minus(Duration.ofMinutes(1)))));
        assertThatThrownBy(() -> verifier.verify(jwt))
                .isInstanceOf(GatewayJwtVerifier.GatewayAuthException.class)
                .extracting(e -> ((GatewayJwtVerifier.GatewayAuthException) e).code())
                .isEqualTo("TOKEN_EXPIRED");
    }

    @Test
    void tokenWithoutExpiryReportsTokenInvalid() throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject("user-123")
                .issuer(ISSUER)
                .audience(AUDIENCE);
        assertThatThrownBy(() -> verifier.verify(token(claims)))
                .isInstanceOf(GatewayJwtVerifier.GatewayAuthException.class)
                .extracting(e -> ((GatewayJwtVerifier.GatewayAuthException) e).code())
                .isEqualTo("TOKEN_INVALID");
    }
}

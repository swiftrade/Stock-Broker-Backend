package com.stockpredict.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.stockpredict.auth.config.AuthJwtProperties;
import com.stockpredict.auth.domain.UserEntity;
import com.stockpredict.common.plan.Plan;
import com.stockpredict.common.security.JwtClaims;
import com.stockpredict.common.security.JwtProperties;
import com.stockpredict.common.security.JwtVerifier;
import com.stockpredict.common.time.MarketClock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SigningKeyProviderTest {

    private static final Instant NOW = Instant.parse("2026-06-10T12:00:00Z");

    @TempDir
    Path tempDir;

    private static AuthJwtProperties properties(Path dir) {
        AuthJwtProperties properties = new AuthJwtProperties();
        properties.setKeyId("unit-test-key");
        properties.setPrivateKeyPath(dir.resolve("private.pem").toString());
        properties.setPublicKeyPath(dir.resolve("public.pem").toString());
        return properties;
    }

    private static JwtProperties jwtProperties() {
        JwtProperties properties = new JwtProperties();
        properties.setIssuer("https://auth.test");
        properties.setAudience("test-api");
        return properties;
    }

    @Test
    void generatesAndPersistsDevelopmentKeys() throws Exception {
        AuthJwtProperties properties = properties(tempDir);
        SigningKeyProvider provider = new SigningKeyProvider(properties);

        assertThat(Files.exists(tempDir.resolve("private.pem"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("public.pem"))).isTrue();
        assertThat(provider.activeKey().getKeyID()).isEqualTo("unit-test-key");

        // Reload from the persisted PEM keeps the same key id.
        SigningKeyProvider reloaded = new SigningKeyProvider(properties);
        assertThat(reloaded.activeKey().toPublicJWK()).isEqualTo(provider.activeKey().toPublicJWK());
    }

    @Test
    void issuedTokenVerifiesAgainstPublishedJwks() {
        AuthJwtProperties properties = properties(tempDir);
        SigningKeyProvider provider = new SigningKeyProvider(properties);
        MarketClock clock = new MarketClock(Clock.fixed(NOW, ZoneOffset.UTC));
        JwtProperties jwtProperties = jwtProperties();
        JwtIssuer issuer = new JwtIssuer(provider, jwtProperties, properties, clock);

        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail("user@example.com");
        user.setPlan("PREMIUM");
        user.setRoles(new String[] {"USER", "ADMIN"});

        String token = issuer.issueAccessToken(user);

        JwtVerifier verifier = new JwtVerifier(
                new ImmutableJWKSet<>(provider.publicJwks()),
                jwtProperties.getIssuer(), jwtProperties.getAudience(), clock.clock());
        JwtClaims claims = verifier.verify(token);

        assertThat(claims.subject()).isEqualTo(user.getId().toString());
        assertThat(claims.email()).isEqualTo("user@example.com");
        assertThat(claims.plan()).isEqualTo(Plan.PREMIUM);
        assertThat(claims.roles()).containsExactlyInAnyOrder("USER", "ADMIN");
    }

    @Test
    void pemParsingProducesRsaPublicKey() throws Exception {
        AuthJwtProperties properties = properties(tempDir);
        new SigningKeyProvider(properties);
        String pem = Files.readString(tempDir.resolve("public.pem"));
        RSAKey key = JWK.parseFromPEMEncodedObjects(pem).toRSAKey();
        assertThat(key.getKeyType().getValue()).isEqualTo("RSA");
    }
}

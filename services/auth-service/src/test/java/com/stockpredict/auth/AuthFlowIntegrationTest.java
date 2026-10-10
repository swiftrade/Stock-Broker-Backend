package com.stockpredict.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpredict.auth.breach.BreachedPasswordChecker;
import com.stockpredict.auth.email.EmailSender;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Full auth journey against a real Postgres (Testcontainers) and the real HTTP
 * stack. Disabled unless {@code -PincludeIntegrationTests=true} is passed, so
 * the default build stays fast and Docker-free.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "includeIntegrationTests", matches = "true")
class AuthFlowIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("stockpredict.auth.jwt.private-key-path",
                () -> "build/test-keys/integration-private.pem");
        registry.add("stockpredict.auth.jwt.public-key-path",
                () -> "build/test-keys/integration-public.pem");
    }

    @TestConfiguration
    static class Stubs {

        @Bean
        @Primary
        CapturingEmailSender capturingEmailSender() {
            return new CapturingEmailSender();
        }

        @Bean
        @Primary
        BreachedPasswordChecker breachedPasswordChecker() {
            return new BreachedPasswordChecker() {
                @Override
                public boolean isBreached(String password) {
                    return false;
                }
            };
        }
    }

    static class CapturingEmailSender implements EmailSender {
        private final Map<String, String> tokens = new ConcurrentHashMap<>();

        @Override
        public void sendVerificationEmail(String email, String verificationToken) {
            tokens.put(email, verificationToken);
        }

        String tokenFor(String email) {
            return tokens.get(email);
        }
    }

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private CapturingEmailSender emailSender;

    @Test
    void fullAuthJourney() throws Exception {
        String email = "journey@example.com";
        String password = "correct horse battery staple";

        // 1. register
        ResponseEntity<String> registered = post("/auth/register",
                Map.of("email", email, "password", password));
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(registered.getBody()).contains("\"verificationRequired\":true");

        // 2. login before verification is rejected
        ResponseEntity<String> blocked = post("/auth/login",
                Map.of("email", email, "password", password));
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(blocked.getBody()).contains("EMAIL_NOT_VERIFIED");

        // 3. verify email with the token captured from the "email"
        String token = emailSender.tokenFor(email);
        assertThat(token).isNotBlank();
        ResponseEntity<String> verified = post("/auth/verify-email", Map.of("token", token));
        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 4. login issues tokens
        ResponseEntity<String> loggedIn = post("/auth/login",
                Map.of("email", email, "password", password));
        assertThat(loggedIn.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode tokens = objectMapper.readTree(loggedIn.getBody());
        String accessToken = tokens.get("accessToken").asText();
        String refreshToken = tokens.get("refreshToken").asText();

        // 5. /auth/me with the access token
        ResponseEntity<String> me = getWithBearer("/auth/me", accessToken);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody()).contains(email).contains("FREE");

        // 6. refresh rotates the pair
        ResponseEntity<String> refreshed = post("/auth/refresh",
                Map.of("refreshToken", refreshToken));
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        String rotatedRefresh = objectMapper.readTree(refreshed.getBody()).get("refreshToken").asText();
        assertThat(rotatedRefresh).isNotEqualTo(refreshToken);

        // 7. replaying the old refresh token is rejected (reuse detection)
        ResponseEntity<String> replayed = post("/auth/refresh",
                Map.of("refreshToken", refreshToken));
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // 8. logout revokes the family; the rotated token no longer refreshes
        ResponseEntity<String> loggedOut = post("/auth/logout",
                Map.of("refreshToken", rotatedRefresh));
        assertThat(loggedOut.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        ResponseEntity<String> afterLogout = post("/auth/refresh",
                Map.of("refreshToken", rotatedRefresh));
        assertThat(afterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refreshReuseRevokesTheWholeFamily() throws Exception {
        String email = "reuse@example.com";
        String password = "correct horse battery staple";
        registerAndVerify(email, password);

        JsonNode tokens = objectMapper.readTree(
                post("/auth/login", Map.of("email", email, "password", password)).getBody());
        String firstRefresh = tokens.get("refreshToken").asText();

        JsonNode rotated = objectMapper.readTree(
                post("/auth/refresh", Map.of("refreshToken", firstRefresh)).getBody());
        String secondRefresh = rotated.get("refreshToken").asText();

        // Replaying the rotated token is theft: 401 and the family is revoked.
        ResponseEntity<String> replayed = post("/auth/refresh", Map.of("refreshToken", firstRefresh));
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // The revocation must have committed: the newer token is now dead too.
        ResponseEntity<String> afterReuse = post("/auth/refresh", Map.of("refreshToken", secondRefresh));
        assertThat(afterReuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(afterReuse.getBody()).contains("TOKEN_INVALID");
    }

    @Test
    void failedLoginsPersistAndLockTheAccount() throws Exception {
        String email = "lockout@example.com";
        String password = "correct horse battery staple";
        registerAndVerify(email, password);

        for (int attempt = 0; attempt < 5; attempt++) {
            ResponseEntity<String> failed = post("/auth/login",
                    Map.of("email", email, "password", "wrong-password-000" + attempt));
            assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(failed.getBody()).contains("INVALID_CREDENTIALS");
        }

        // The failed attempts committed, so even the correct password is locked.
        ResponseEntity<String> locked = post("/auth/login", Map.of("email", email, "password", password));
        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        assertThat(locked.getBody()).contains("LOCKED");
    }

    private void registerAndVerify(String email, String password) {
        ResponseEntity<String> registered = post("/auth/register", Map.of("email", email, "password", password));
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String token = emailSender.tokenFor(email);
        assertThat(token).isNotBlank();
        ResponseEntity<String> verified = post("/auth/verify-email", Map.of("token", token));
        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void jwksIsPublicAndWellFormed() throws Exception {
        ResponseEntity<String> response = rest.getForEntity("/.well-known/jwks.json", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode jwks = objectMapper.readTree(response.getBody());
        assertThat(jwks.get("keys")).hasSize(1);
        assertThat(jwks.get("keys").get(0).get("kty").asText()).isEqualTo("RSA");
        assertThat(jwks.get("keys").get(0).get("kid").asText()).isNotBlank();
    }

    private ResponseEntity<String> post(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> getWithBearer(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }
}

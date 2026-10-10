package com.stockpredict.auth.breach;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Breached-password check via the HaveIBeenPwned k-anonymity range API: only
 * the first five SHA-1 hex characters ever leave the process. Fails open
 * (logs and allows) when the API is unreachable so login is never blocked by a
 * third party.
 */
@Component
public class BreachedPasswordChecker {

    private static final Logger log = LoggerFactory.getLogger(BreachedPasswordChecker.class);
    private static final String RANGE_URL = "https://api.pwnedpasswords.com/range/";
    // Bounded so a stalled HIBP call cannot wedge registration (fail-open anyway).
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    private final RestClient restClient;

    public BreachedPasswordChecker() {
        this(defaultRestClient());
    }

    private static RestClient defaultRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    public BreachedPasswordChecker(RestClient restClient) {
        this.restClient = restClient;
    }

    public boolean isBreached(String password) {
        if (password == null || password.isBlank()) {
            return false;
        }
        try {
            String sha1 = sha1Hex(password).toUpperCase();
            String prefix = sha1.substring(0, 5);
            String suffix = sha1.substring(5);
            String body = restClient.get().uri(RANGE_URL + prefix).retrieve().body(String.class);
            if (body == null) {
                return false;
            }
            return Arrays.stream(body.split("\n"))
                    .map(String::trim)
                    .anyMatch(line -> line.startsWith(suffix + ":"));
        } catch (Exception ex) {
            log.warn("Breached-password check unavailable ({}); allowing password", ex.getMessage());
            return false;
        }
    }

    private static String sha1Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-1 unavailable", ex);
        }
    }
}

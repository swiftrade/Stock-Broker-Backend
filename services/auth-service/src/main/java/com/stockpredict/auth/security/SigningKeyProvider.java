package com.stockpredict.auth.security;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.stockpredict.auth.config.AuthJwtProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Loads the RS256 signing key from PEM files, generating a development key pair
 * on first boot when the files are absent. The public JWK is published through
 * the JWKS endpoint; key rotation adds more entries here.
 */
@Component
public class SigningKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(SigningKeyProvider.class);

    private final RSAKey activeKey;

    @org.springframework.beans.factory.annotation.Autowired
    public SigningKeyProvider(AuthJwtProperties properties) {
        this(loadOrGenerate(properties));
    }

    /** Test/rotation constructor with an in-memory key. */
    public SigningKeyProvider(RSAKey activeKey) {
        this.activeKey = activeKey;
    }

    public RSAKey activeKey() {
        return activeKey;
    }

    public JWKSet publicJwks() {
        return new JWKSet(activeKey.toPublicJWK());
    }

    private static RSAKey loadOrGenerate(AuthJwtProperties properties) {
        Path privatePath = Path.of(properties.getPrivateKeyPath());
        try {
            if (Files.exists(privatePath)) {
                String pem = Files.readString(privatePath, StandardCharsets.UTF_8);
                RSAKey key = JWK.parseFromPEMEncodedObjects(pem).toRSAKey();
                if (key.getKeyID() == null) {
                    key = new RSAKey.Builder(key).keyID(properties.getKeyId()).build();
                }
                log.info("Loaded JWT signing key '{}' from {}", key.getKeyID(), privatePath);
                return key;
            }
            if (!properties.isAllowKeyGeneration()) {
                // Fail fast: silently generating per-replica keys would break
                // cross-pod token verification in production (M12).
                throw new IllegalStateException("No JWT signing key at " + privatePath
                        + " and key generation is disabled; mount the signing key secret");
            }
            log.warn("No JWT signing key at {} - generating a development key pair", privatePath);
            RSAKey generated = new RSAKeyGenerator(2048).keyID(properties.getKeyId()).generate();
            writePem(privatePath, pem("PRIVATE KEY", generated.toPrivateKey().getEncoded()));
            writePem(Path.of(properties.getPublicKeyPath()),
                    pem("PUBLIC KEY", generated.toRSAPublicKey().getEncoded()));
            return generated;
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to load/generate JWT signing key", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to load/generate JWT signing key", ex);
        }
    }

    private static void writePem(Path path, String pem) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, pem, StandardCharsets.UTF_8);
        restrictToOwner(path);
        log.info("Wrote JWT key material to {}", path);
    }

    /** Best-effort owner-only permissions on private key material. */
    private static void restrictToOwner(Path path) {
        try {
            Files.setPosixFilePermissions(path, java.util.Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ex) {
            log.debug("Could not restrict permissions on {}: {}", path, ex.toString());
        }
    }

    /** PKCS#8 / X.509 PEM encoding without external dependencies. */
    private static String pem(String type, byte[] der) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + base64 + "\n-----END " + type + "-----\n";
    }
}

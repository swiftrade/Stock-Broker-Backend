package com.stockpredict.auth.web;

import com.nimbusds.jose.jwk.RSAKey;
import com.stockpredict.auth.api.JwksApi;
import com.stockpredict.auth.api.model.Jwk;
import com.stockpredict.auth.api.model.JwksResponse;
import com.stockpredict.auth.security.SigningKeyProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Publishes the public signing keys for local JWT verification. */
@RestController
public class JwksController implements JwksApi {

    private final SigningKeyProvider signingKeyProvider;

    public JwksController(SigningKeyProvider signingKeyProvider) {
        this.signingKeyProvider = signingKeyProvider;
    }

    @Override
    public ResponseEntity<JwksResponse> getJwks() {
        JwksResponse response = new JwksResponse();
        signingKeyProvider.publicJwks().getKeys().stream()
                .filter(RSAKey.class::isInstance)
                .map(RSAKey.class::cast)
                .map(JwksController::toApiJwk)
                .forEach(response::addKeysItem);
        return ResponseEntity.ok(response);
    }

    private static Jwk toApiJwk(RSAKey key) {
        return new Jwk()
                .kty("RSA")
                .use("sig")
                .alg("RS256")
                .kid(key.getKeyID())
                .n(key.getModulus().toString())
                .e(key.getPublicExponent().toString());
    }
}

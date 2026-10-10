package com.stockpredict.common.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for local JWT verification ({@code stockpredict.jwt.*}). */
@ConfigurationProperties(prefix = "stockpredict.jwt")
public class JwtProperties {

    /** JWKS endpoint used to resolve signing keys. Empty disables the verifier. */
    private String jwksUri;

    /** Expected {@code iss} claim. */
    private String issuer;

    /** Expected {@code aud} claim. */
    private String audience;

    /** Allowed clock skew when validating exp/nbf. */
    private Duration clockSkew = Duration.ofSeconds(30);

    public String getJwksUri() {
        return jwksUri;
    }

    public void setJwksUri(String jwksUri) {
        this.jwksUri = jwksUri;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public Duration getClockSkew() {
        return clockSkew;
    }

    public void setClockSkew(Duration clockSkew) {
        this.clockSkew = clockSkew;
    }
}

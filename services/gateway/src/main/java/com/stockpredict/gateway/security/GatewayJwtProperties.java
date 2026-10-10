package com.stockpredict.gateway.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Gateway JWT pre-check settings ({@code stockpredict.jwt.*}). */
@ConfigurationProperties(prefix = "stockpredict.jwt")
public class GatewayJwtProperties {

    private String jwksUri = "http://localhost:8081/api/v1/.well-known/jwks.json";
    private String issuer = "https://auth.stockpredict.local";
    private String audience = "stockpredict-api";
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

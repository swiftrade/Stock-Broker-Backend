package com.stockpredict.auth.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Auth-specific settings ({@code stockpredict.auth.jwt.*}). */
@ConfigurationProperties(prefix = "stockpredict.auth.jwt")
public class AuthJwtProperties {

    private String keyId = "dev-2026-01";
    private String privateKeyPath = "config/keys/dev-private.pem";
    private String publicKeyPath = "config/keys/dev-public.pem";
    /** Dev convenience: generate a key pair on first boot when files are absent. */
    private boolean allowKeyGeneration = true;
    private Duration accessTokenTtl = Duration.ofMinutes(15);
    private Duration refreshTokenTtl = Duration.ofDays(7);
    private Duration verificationTokenTtl = Duration.ofHours(24);
    private int loginMaxFailures = 5;
    private Duration loginWindow = Duration.ofMinutes(15);
    private String verificationUrlBase = "https://app.stockpredict.local/verify-email";
    private int loginRetentionDays = 30;
    private int tokenRetentionDays = 30;

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public String getPrivateKeyPath() {
        return privateKeyPath;
    }

    public void setPrivateKeyPath(String privateKeyPath) {
        this.privateKeyPath = privateKeyPath;
    }

    public String getPublicKeyPath() {
        return publicKeyPath;
    }

    public void setPublicKeyPath(String publicKeyPath) {
        this.publicKeyPath = publicKeyPath;
    }

    public boolean isAllowKeyGeneration() {
        return allowKeyGeneration;
    }

    public void setAllowKeyGeneration(boolean allowKeyGeneration) {
        this.allowKeyGeneration = allowKeyGeneration;
    }

    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    public void setAccessTokenTtl(Duration accessTokenTtl) {
        this.accessTokenTtl = accessTokenTtl;
    }

    public Duration getRefreshTokenTtl() {
        return refreshTokenTtl;
    }

    public void setRefreshTokenTtl(Duration refreshTokenTtl) {
        this.refreshTokenTtl = refreshTokenTtl;
    }

    public Duration getVerificationTokenTtl() {
        return verificationTokenTtl;
    }

    public void setVerificationTokenTtl(Duration verificationTokenTtl) {
        this.verificationTokenTtl = verificationTokenTtl;
    }

    public int getLoginMaxFailures() {
        return loginMaxFailures;
    }

    public void setLoginMaxFailures(int loginMaxFailures) {
        this.loginMaxFailures = loginMaxFailures;
    }

    public Duration getLoginWindow() {
        return loginWindow;
    }

    public void setLoginWindow(Duration loginWindow) {
        this.loginWindow = loginWindow;
    }

    public String getVerificationUrlBase() {
        return verificationUrlBase;
    }

    public void setVerificationUrlBase(String verificationUrlBase) {
        this.verificationUrlBase = verificationUrlBase;
    }

    public int getLoginRetentionDays() {
        return loginRetentionDays;
    }

    public void setLoginRetentionDays(int loginRetentionDays) {
        this.loginRetentionDays = loginRetentionDays;
    }

    public int getTokenRetentionDays() {
        return tokenRetentionDays;
    }

    public void setTokenRetentionDays(int tokenRetentionDays) {
        this.tokenRetentionDays = tokenRetentionDays;
    }
}

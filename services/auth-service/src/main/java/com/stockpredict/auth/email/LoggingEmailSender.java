package com.stockpredict.auth.email;

import com.stockpredict.auth.config.AuthJwtProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Development email sender: logs the verification link instead of sending mail.
 * Swap for an SMTP/provider implementation without touching callers.
 */
@Component
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    private final AuthJwtProperties properties;

    public LoggingEmailSender(AuthJwtProperties properties) {
        this.properties = properties;
    }

    @Override
    public void sendVerificationEmail(String email, String verificationToken) {
        String link = properties.getVerificationUrlBase() + "?token=" + verificationToken;
        log.info("Verification email for {}: {}", email, link);
    }
}

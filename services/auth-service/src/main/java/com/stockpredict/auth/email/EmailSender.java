package com.stockpredict.auth.email;

/** Sends transactional email; the default implementation logs the link. */
public interface EmailSender {

    void sendVerificationEmail(String email, String verificationToken);
}

package com.stockpredict.auth.service;

import com.stockpredict.auth.api.model.RegisterResponse;
import com.stockpredict.auth.api.model.TokenResponse;
import com.stockpredict.auth.api.model.UserProfile;
import com.stockpredict.auth.breach.BreachedPasswordChecker;
import com.stockpredict.auth.config.AuthJwtProperties;
import com.stockpredict.auth.domain.LoginAttemptEntity;
import com.stockpredict.auth.domain.RefreshTokenEntity;
import com.stockpredict.auth.domain.UserEntity;
import com.stockpredict.auth.email.EmailSender;
import com.stockpredict.auth.repository.LoginAttemptRepository;
import com.stockpredict.auth.repository.RefreshTokenRepository;
import com.stockpredict.auth.repository.UserRepository;
import com.stockpredict.auth.security.JwtIssuer;
import com.stockpredict.auth.security.TokenHasher;
import com.stockpredict.common.error.ApiException;
import com.stockpredict.common.error.ErrorCode;
import com.stockpredict.common.plan.Plan;
import com.stockpredict.common.security.JwtClaims;
import com.stockpredict.common.time.MarketClock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registration, authentication, token lifecycle and email verification. */
@Service
public class AuthService {

    static final int PASSWORD_MIN_LENGTH = 12;
    static final int PASSWORD_MAX_LENGTH = 128;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final LoginAttemptRepository loginAttemptRepository;
    private final PasswordEncoder passwordEncoder;
    private final BreachedPasswordChecker breachedPasswordChecker;
    private final EmailSender emailSender;
    private final JwtIssuer jwtIssuer;
    private final AuthJwtProperties authProperties;
    private final MarketClock clock;
    /**
     * A real Argon2 hash verified when the email is unknown, so login latency
     * does not reveal whether an account exists (timing side channel).
     */
    private final String timingEqualizationHash;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       LoginAttemptRepository loginAttemptRepository,
                       PasswordEncoder passwordEncoder,
                       BreachedPasswordChecker breachedPasswordChecker,
                       EmailSender emailSender,
                       JwtIssuer jwtIssuer,
                       AuthJwtProperties authProperties,
                       MarketClock clock) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.loginAttemptRepository = loginAttemptRepository;
        this.passwordEncoder = passwordEncoder;
        this.breachedPasswordChecker = breachedPasswordChecker;
        this.emailSender = emailSender;
        this.jwtIssuer = jwtIssuer;
        this.authProperties = authProperties;
        this.clock = clock;
        this.timingEqualizationHash = passwordEncoder.encode("timing-equalization-dummy-password");
    }

    @Transactional
    public RegisterResponse register(com.stockpredict.auth.api.model.RegisterRequest request, String ip) {
        requireRequest(request, "Registration request required");
        String email = normalizeEmail(request.getEmail());
        if (email == null || email.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Email is required");
        }
        validatePassword(request.getPassword());
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_REGISTERED, "Email already registered");
        }
        if (breachedPasswordChecker.isBreached(request.getPassword())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "This password has appeared in a known data breach; choose a different one");
        }

        UserEntity user = new UserEntity();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setPlan(Plan.FREE.name());
        user.setRoles(new String[] {"USER"});
        user.setEmailVerified(false);
        String rawToken = TokenHasher.newToken();
        user.setVerificationTokenHash(TokenHasher.sha256Hex(rawToken));
        user.setVerificationExpiresAt(clock.now().plus(authProperties.getVerificationTokenTtl()));
        userRepository.save(user);

        emailSender.sendVerificationEmail(email, rawToken);

        return new RegisterResponse()
                .id(user.getId())
                .email(email)
                .emailVerified(false)
                .verificationRequired(true);
    }

    /**
     * Business rejections must not roll back the failed-attempt row, otherwise
     * the lockout counter never advances; {@code noRollbackFor} keeps the audit
     * row committed while still returning the error to the client.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public TokenResponse login(com.stockpredict.auth.api.model.LoginRequest request, String ip, String userAgent) {
        requireRequest(request, "Login request required");
        String email = normalizeEmail(request.getEmail());
        if (email == null || email.isBlank() || request.getPassword() == null || request.getPassword().isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Email and password are required");
        }
        Instant now = clock.now();

        long recentFailures = loginAttemptRepository
                .countByEmailIgnoreCaseAndSuccessFalseAndAttemptedAtAfter(
                        email, now.minus(authProperties.getLoginWindow()));
        if (recentFailures >= authProperties.getLoginMaxFailures()) {
            throw new ApiException(ErrorCode.LOCKED, "Too many failed login attempts; try again later");
        }

        UserEntity user = userRepository.findByEmailIgnoreCase(email).orElse(null);
        // Always verify against a real hash so unknown emails cost the same
        // ~250 ms as known ones (no account-enumeration timing oracle).
        String encodedPassword = user == null ? timingEqualizationHash : user.getPasswordHash();
        boolean passwordMatches = passwordEncoder.matches(request.getPassword(), encodedPassword);
        if (user == null || !passwordMatches) {
            recordAttempt(email, ip, false);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password");
        }
        if (!user.isEmailVerified()) {
            // Not a password failure: do not count it towards the lockout.
            throw new ApiException(ErrorCode.EMAIL_NOT_VERIFIED, "Verify your email address before logging in");
        }

        recordAttempt(email, ip, true);
        return issueTokens(user, UUID.randomUUID(), ip, userAgent);
    }

    /**
     * Reuse detection revokes the token family and then throws; the revocation
     * must survive that throw ({@code noRollbackFor}) or theft detection would
     * be a no-op.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public TokenResponse refresh(com.stockpredict.auth.api.model.RefreshRequest request, String ip, String userAgent) {
        if (request == null || request.getRefreshToken() == null || request.getRefreshToken().isBlank()) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Refresh token required");
        }
        String rawToken = request.getRefreshToken();
        RefreshTokenEntity token = refreshTokenRepository
                .findByTokenHash(TokenHasher.sha256Hex(rawToken))
                .orElseThrow(() -> new ApiException(ErrorCode.TOKEN_INVALID, "Unknown refresh token"));
        Instant now = clock.now();

        if (token.getRevokedAt() != null) {
            // Reuse of a rotated token: assume theft and revoke the whole family.
            refreshTokenRepository.revokeFamily(token.getFamilyId(), now);
            throw new ApiException(ErrorCode.TOKEN_INVALID,
                    "Refresh token reuse detected; all sessions in this family were revoked");
        }
        if (token.getExpiresAt().isBefore(now)) {
            throw new ApiException(ErrorCode.TOKEN_EXPIRED, "Refresh token expired");
        }
        UserEntity user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new ApiException(ErrorCode.TOKEN_INVALID, "User no longer exists"));

        // Atomic claim: concurrent replays of the same token both pass the read
        // above, but only one wins this conditional update. The loser is treated
        // as token theft (the race itself is the reuse signal).
        if (refreshTokenRepository.revokeIfActive(token.getTokenHash(), now) == 0) {
            refreshTokenRepository.revokeFamily(token.getFamilyId(), now);
            throw new ApiException(ErrorCode.TOKEN_INVALID,
                    "Refresh token reuse detected; all sessions in this family were revoked");
        }
        String newRawToken = TokenHasher.newToken();
        RefreshTokenEntity rotated = newRefreshToken(user, newRawToken, token.getFamilyId(),
                ip, userAgent, now);
        // Persist the new token first so its generated id is available for the link.
        refreshTokenRepository.save(rotated);
        token.setRevokedAt(now);
        token.setReplacedBy(rotated.getId());
        refreshTokenRepository.save(token);

        return tokenResponse(user, newRawToken, now);
    }

    @Transactional
    public void logout(com.stockpredict.auth.api.model.RefreshRequest request) {
        if (request == null || request.getRefreshToken() == null || request.getRefreshToken().isBlank()) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Refresh token required");
        }
        refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex(request.getRefreshToken()))
                .ifPresent(token -> refreshTokenRepository.revokeFamily(token.getFamilyId(), clock.now()));
        // Unknown tokens are treated as already logged out (idempotent).
    }

    @Transactional(readOnly = true)
    public UserProfile me(JwtClaims claims) {
        UUID userId;
        try {
            userId = UUID.fromString(claims.subject());
        } catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.TOKEN_INVALID, "Invalid subject claim");
        }
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "User not found"));
        return toProfile(user);
    }

    @Transactional
    public UserProfile verifyEmail(com.stockpredict.auth.api.model.VerifyEmailRequest request) {
        if (request == null || request.getToken() == null || request.getToken().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Verification token is required");
        }
        UserEntity user = userRepository
                .findByVerificationTokenHash(TokenHasher.sha256Hex(request.getToken()))
                .orElseThrow(() -> new ApiException(ErrorCode.GONE,
                        "Verification token expired or already used"));
        Instant now = clock.now();
        if (user.getVerificationExpiresAt() == null || user.getVerificationExpiresAt().isBefore(now)) {
            throw new ApiException(ErrorCode.GONE, "Verification token expired or already used");
        }
        user.setEmailVerified(true);
        user.setVerificationTokenHash(null);
        user.setVerificationExpiresAt(null);
        userRepository.save(user);
        return toProfile(user);
    }

    @Transactional
    public void resendVerification(com.stockpredict.auth.api.model.ResendVerificationRequest request) {
        if (request == null || request.getEmail() == null || request.getEmail().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Email is required");
        }
        String email = normalizeEmail(request.getEmail());
        userRepository.findByEmailIgnoreCase(email)
                .filter(user -> !user.isEmailVerified())
                .ifPresent(user -> {
                    String rawToken = TokenHasher.newToken();
                    user.setVerificationTokenHash(TokenHasher.sha256Hex(rawToken));
                    user.setVerificationExpiresAt(clock.now().plus(authProperties.getVerificationTokenTtl()));
                    userRepository.save(user);
                    emailSender.sendVerificationEmail(email, rawToken);
                });
        // Always accepted to avoid account enumeration.
    }

    private TokenResponse issueTokens(UserEntity user, UUID familyId, String ip, String userAgent) {
        Instant now = clock.now();
        String rawToken = TokenHasher.newToken();
        refreshTokenRepository.save(newRefreshToken(user, rawToken, familyId, ip, userAgent, now));
        return tokenResponse(user, rawToken, now);
    }

    private RefreshTokenEntity newRefreshToken(UserEntity user, String rawToken, UUID familyId,
                                               String ip, String userAgent, Instant now) {
        RefreshTokenEntity token = new RefreshTokenEntity();
        token.setUserId(user.getId());
        token.setTokenHash(TokenHasher.sha256Hex(rawToken));
        token.setFamilyId(familyId);
        token.setIssuedAt(now);
        token.setExpiresAt(now.plus(authProperties.getRefreshTokenTtl()));
        token.setIp(ip);
        token.setUserAgent(userAgent);
        return token;
    }

    private TokenResponse tokenResponse(UserEntity user, String rawRefreshToken, Instant now) {
        String accessToken = jwtIssuer.issueAccessToken(user);
        return new TokenResponse()
                .accessToken(accessToken)
                .tokenType("Bearer")
                .expiresIn((int) authProperties.getAccessTokenTtl().toSeconds())
                .refreshToken(rawRefreshToken)
                .plan(com.stockpredict.auth.api.model.Plan.fromValue(user.getPlan()));
    }

    private void recordAttempt(String email, String ip, boolean success) {
        LoginAttemptEntity attempt = new LoginAttemptEntity();
        attempt.setEmail(email);
        attempt.setIp(ip);
        attempt.setSuccess(success);
        attempt.setAttemptedAt(clock.now());
        loginAttemptRepository.save(attempt);
    }

    private static UserProfile toProfile(UserEntity user) {
        return new UserProfile()
                .id(user.getId())
                .email(user.getEmail())
                .plan(com.stockpredict.auth.api.model.Plan.fromValue(user.getPlan()))
                .roles(List.of(user.getRoles()))
                .emailVerified(user.isEmailVerified())
                .createdAt(user.getCreatedAt() == null ? null : user.getCreatedAt().atOffset(ZoneOffset.UTC));
    }

    static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private static void requireRequest(Object request, String message) {
        if (request == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, message);
        }
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < PASSWORD_MIN_LENGTH
                || password.length() > PASSWORD_MAX_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Password must be between " + PASSWORD_MIN_LENGTH + " and "
                            + PASSWORD_MAX_LENGTH + " characters");
        }
    }
}

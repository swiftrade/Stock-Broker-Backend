package com.stockpredict.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stockpredict.auth.api.model.LoginRequest;
import com.stockpredict.auth.api.model.RefreshRequest;
import com.stockpredict.auth.api.model.RegisterRequest;
import com.stockpredict.auth.api.model.TokenResponse;
import com.stockpredict.auth.breach.BreachedPasswordChecker;
import com.stockpredict.auth.config.AuthJwtProperties;
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
import com.stockpredict.common.time.MarketClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-10T12:00:00Z");

    @Mock
    private UserRepository userRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private LoginAttemptRepository loginAttemptRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private BreachedPasswordChecker breachedPasswordChecker;
    @Mock
    private EmailSender emailSender;
    @Mock
    private JwtIssuer jwtIssuer;

    private AuthService service;

    @BeforeEach
    void setUp() {
        AuthJwtProperties properties = new AuthJwtProperties();
        properties.setAccessTokenTtl(Duration.ofMinutes(15));
        properties.setRefreshTokenTtl(Duration.ofDays(7));
        properties.setLoginMaxFailures(5);
        properties.setLoginWindow(Duration.ofMinutes(15));
        MarketClock clock = new MarketClock(Clock.fixed(NOW, ZoneOffset.UTC));
        service = new AuthService(userRepository, refreshTokenRepository, loginAttemptRepository,
                passwordEncoder, breachedPasswordChecker, emailSender, jwtIssuer, properties, clock);
        when(refreshTokenRepository.save(any(RefreshTokenEntity.class))).thenAnswer(invocation -> {
            RefreshTokenEntity saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
            }
            return saved;
        });
    }

    private static UserEntity verifiedUser() {
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail("user@example.com");
        user.setPasswordHash("hash");
        user.setPlan("FREE");
        user.setRoles(new String[] {"USER"});
        user.setEmailVerified(true);
        user.setCreatedAt(NOW);
        return user;
    }

    private static RegisterRequest registerRequest() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("New.User@Example.com");
        request.setPassword("correct horse battery staple");
        return request;
    }

    private static LoginRequest loginRequest() {
        LoginRequest request = new LoginRequest();
        request.setEmail("user@example.com");
        request.setPassword("correct horse battery staple");
        return request;
    }

    @Test
    void registerHashesPasswordAndSendsVerification() {
        when(userRepository.existsByEmailIgnoreCase("new.user@example.com")).thenReturn(false);
        when(breachedPasswordChecker.isBreached(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("argon2-hash");
        when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
            }
            return saved;
        });

        var response = service.register(registerRequest(), "203.0.113.7");

        assertThat(response.getEmail()).isEqualTo("new.user@example.com");
        assertThat(response.getVerificationRequired()).isTrue();
        assertThat(response.getId()).isNotNull();
        verify(passwordEncoder).encode("correct horse battery staple");
        verify(emailSender).sendVerificationEmail(eq("new.user@example.com"), anyString());
        verify(loginAttemptRepository, never()).save(any());
    }

    @Test
    void registerRejectsDuplicateEmail() {
        when(userRepository.existsByEmailIgnoreCase(anyString())).thenReturn(true);
        assertThatThrownBy(() -> service.register(registerRequest(), null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.EMAIL_ALREADY_REGISTERED);
    }

    @Test
    void registerRejectsBreachedPassword() {
        when(userRepository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        when(breachedPasswordChecker.isBreached(anyString())).thenReturn(true);
        assertThatThrownBy(() -> service.register(registerRequest(), null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void loginRejectsLockedAccountBeforeCheckingPassword() {
        when(loginAttemptRepository.countByEmailIgnoreCaseAndSuccessFalseAndAttemptedAtAfter(
                eq("user@example.com"), any())).thenReturn(5L);
        assertThatThrownBy(() -> service.login(loginRequest(), null, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.LOCKED);
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    void loginRejectsWrongPasswordAndRecordsFailure() {
        when(loginAttemptRepository.countByEmailIgnoreCaseAndSuccessFalseAndAttemptedAtAfter(
                anyString(), any())).thenReturn(0L);
        when(userRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(verifiedUser()));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.login(loginRequest(), "203.0.113.7", "JUnit"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        verify(loginAttemptRepository).save(any());
    }

    @Test
    void loginRejectsUnverifiedEmail() {
        when(loginAttemptRepository.countByEmailIgnoreCaseAndSuccessFalseAndAttemptedAtAfter(
                anyString(), any())).thenReturn(0L);
        UserEntity user = verifiedUser();
        user.setEmailVerified(false);
        when(userRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.login(loginRequest(), null, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED);
    }

    @Test
    void loginIssuesAccessAndRefreshTokens() {
        when(loginAttemptRepository.countByEmailIgnoreCaseAndSuccessFalseAndAttemptedAtAfter(
                anyString(), any())).thenReturn(0L);
        when(userRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(verifiedUser()));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        when(jwtIssuer.issueAccessToken(any(UserEntity.class))).thenReturn("access-token");

        TokenResponse response = service.login(loginRequest(), "203.0.113.7", "JUnit");

        assertThat(response.getAccessToken()).isEqualTo("access-token");
        assertThat(response.getTokenType()).isEqualTo("Bearer");
        assertThat(response.getExpiresIn()).isEqualTo(900);
        assertThat(response.getRefreshToken()).isNotBlank();
        assertThat(response.getPlan()).isEqualTo(com.stockpredict.auth.api.model.Plan.FREE);
        verify(refreshTokenRepository).save(any(RefreshTokenEntity.class));
    }

    @Test
    void refreshRotatesTokenAndRevokesTheOldOne() {
        UserEntity user = verifiedUser();
        RefreshTokenEntity existing = new RefreshTokenEntity();
        existing.setId(UUID.randomUUID());
        existing.setUserId(user.getId());
        existing.setTokenHash(TokenHasher.sha256Hex("old-token"));
        existing.setFamilyId(UUID.randomUUID());
        existing.setIssuedAt(NOW.minus(Duration.ofDays(1)));
        existing.setExpiresAt(NOW.plus(Duration.ofDays(6)));

        when(refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex("old-token")))
                .thenReturn(Optional.of(existing));
        when(refreshTokenRepository.revokeIfActive(eq(TokenHasher.sha256Hex("old-token")), any()))
                .thenReturn(1);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(jwtIssuer.issueAccessToken(any(UserEntity.class))).thenReturn("new-access");

        RefreshRequest request = new RefreshRequest();
        request.setRefreshToken("old-token");
        TokenResponse response = service.refresh(request, null, null);

        assertThat(existing.getRevokedAt()).isNotNull();
        assertThat(existing.getReplacedBy()).isNotNull();
        assertThat(response.getAccessToken()).isEqualTo("new-access");
        assertThat(response.getRefreshToken()).isNotBlank();
    }

    @Test
    void refreshDetectsReuseAndRevokesFamily() {
        RefreshTokenEntity revoked = new RefreshTokenEntity();
        revoked.setId(UUID.randomUUID());
        revoked.setUserId(UUID.randomUUID());
        revoked.setTokenHash(TokenHasher.sha256Hex("stolen"));
        revoked.setFamilyId(UUID.randomUUID());
        revoked.setExpiresAt(NOW.plus(Duration.ofDays(6)));
        revoked.setRevokedAt(NOW.minus(Duration.ofMinutes(1)));

        when(refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex("stolen")))
                .thenReturn(Optional.of(revoked));

        RefreshRequest request = new RefreshRequest();
        request.setRefreshToken("stolen");
        assertThatThrownBy(() -> service.refresh(request, null, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.TOKEN_INVALID);
        verify(refreshTokenRepository).revokeFamily(eq(revoked.getFamilyId()), any());
    }

    @Test
    void concurrentRefreshLosesTheAtomicClaimAndRevokesTheFamily() {
        UserEntity user = verifiedUser();
        RefreshTokenEntity existing = new RefreshTokenEntity();
        existing.setId(UUID.randomUUID());
        existing.setUserId(user.getId());
        existing.setTokenHash(TokenHasher.sha256Hex("racy"));
        existing.setFamilyId(UUID.randomUUID());
        existing.setExpiresAt(NOW.plus(Duration.ofDays(6)));

        when(refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex("racy")))
                .thenReturn(Optional.of(existing));
        when(refreshTokenRepository.revokeIfActive(eq(TokenHasher.sha256Hex("racy")), any()))
                .thenReturn(0);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        RefreshRequest request = new RefreshRequest();
        request.setRefreshToken("racy");
        assertThatThrownBy(() -> service.refresh(request, null, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.TOKEN_INVALID);
        verify(refreshTokenRepository).revokeFamily(eq(existing.getFamilyId()), any());
    }

    @Test
    void registerRejectsShortOrMissingPasswordWithValidationFailed() {
        RegisterRequest shortPassword = registerRequest();
        shortPassword.setPassword("short");
        assertThatThrownBy(() -> service.register(shortPassword, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        RegisterRequest missingPassword = registerRequest();
        missingPassword.setPassword(null);
        assertThatThrownBy(() -> service.register(missingPassword, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThatThrownBy(() -> service.register(new RegisterRequest(), null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void loginWithUnknownEmailStillPerformsAPasswordCheck() {
        when(loginAttemptRepository.countByEmailIgnoreCaseAndSuccessFalseAndAttemptedAtAfter(
                anyString(), any())).thenReturn(0L);
        when(userRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(loginRequest(), null, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        verify(passwordEncoder).matches(eq("correct horse battery staple"), any());
    }

    @Test
    void verifyEmailRejectsExpiredToken() {
        UserEntity user = verifiedUser();
        user.setEmailVerified(false);
        user.setVerificationTokenHash(TokenHasher.sha256Hex("expired"));
        user.setVerificationExpiresAt(NOW.minus(Duration.ofMinutes(1)));
        when(userRepository.findByVerificationTokenHash(TokenHasher.sha256Hex("expired")))
                .thenReturn(Optional.of(user));

        var request = new com.stockpredict.auth.api.model.VerifyEmailRequest();
        request.setToken("expired");
        assertThatThrownBy(() -> service.verifyEmail(request))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.GONE);
    }

    @Test
    void verifyEmailMarksUserVerifiedAndClearsToken() {
        UserEntity user = verifiedUser();
        user.setEmailVerified(false);
        user.setVerificationTokenHash(TokenHasher.sha256Hex("good"));
        user.setVerificationExpiresAt(NOW.plus(Duration.ofHours(1)));
        when(userRepository.findByVerificationTokenHash(TokenHasher.sha256Hex("good")))
                .thenReturn(Optional.of(user));

        var request = new com.stockpredict.auth.api.model.VerifyEmailRequest();
        request.setToken("good");
        var profile = service.verifyEmail(request);

        assertThat(profile.getEmailVerified()).isTrue();
        assertThat(user.getVerificationTokenHash()).isNull();
        assertThat(user.getVerificationExpiresAt()).isNull();
    }

    @Test
    void resendVerificationIsSilentForUnknownEmail() {
        when(userRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
        var request = new com.stockpredict.auth.api.model.ResendVerificationRequest();
        request.setEmail("nobody@example.com");
        service.resendVerification(request);
        verify(emailSender, never()).sendVerificationEmail(anyString(), anyString());
    }
}

package com.stockpredict.auth.web;

import com.stockpredict.auth.api.AuthApi;
import com.stockpredict.auth.api.model.LoginRequest;
import com.stockpredict.auth.api.model.RefreshRequest;
import com.stockpredict.auth.api.model.RegisterRequest;
import com.stockpredict.auth.api.model.RegisterResponse;
import com.stockpredict.auth.api.model.ResendVerificationRequest;
import com.stockpredict.auth.api.model.TokenResponse;
import com.stockpredict.auth.api.model.UserProfile;
import com.stockpredict.auth.api.model.VerifyEmailRequest;
import com.stockpredict.auth.service.AuthService;
import com.stockpredict.common.error.ApiException;
import com.stockpredict.common.error.ErrorCode;
import com.stockpredict.common.security.JwtClaims;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

/** Implements the frozen auth contract. */
@RestController
public class AuthController implements AuthApi {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public ResponseEntity<RegisterResponse> register(@Valid RegisterRequest registerRequest) {
        RegisterResponse response = authService.register(registerRequest, RequestInfo.clientIp());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Override
    public ResponseEntity<TokenResponse> login(@Valid LoginRequest loginRequest) {
        return ResponseEntity.ok(authService.login(loginRequest, RequestInfo.clientIp(), RequestInfo.userAgent()));
    }

    @Override
    public ResponseEntity<TokenResponse> refresh(@Valid RefreshRequest refreshRequest) {
        return ResponseEntity.ok(authService.refresh(refreshRequest, RequestInfo.clientIp(), RequestInfo.userAgent()));
    }

    @Override
    public ResponseEntity<Void> logout(@Valid RefreshRequest refreshRequest) {
        authService.logout(refreshRequest);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<UserProfile> getMe() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof JwtClaims claims)) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return ResponseEntity.ok(authService.me(claims));
    }

    @Override
    public ResponseEntity<UserProfile> verifyEmail(@Valid VerifyEmailRequest verifyEmailRequest) {
        return ResponseEntity.ok(authService.verifyEmail(verifyEmailRequest));
    }

    @Override
    public ResponseEntity<Void> resendVerification(@Valid ResendVerificationRequest resendVerificationRequest) {
        authService.resendVerification(resendVerificationRequest);
        return ResponseEntity.accepted().build();
    }
}

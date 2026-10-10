package com.stockpredict.common.error;

import org.springframework.http.HttpStatus;

/**
 * Stable machine-readable error codes. The {@code code} field is part of the
 * frozen API contract (RFC 7807 responses) and must not change silently.
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed"),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "Malformed request"),

    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid email or password"),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Invalid token"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Token expired"),

    FORBIDDEN(HttpStatus.FORBIDDEN, "Access denied"),
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "Email address is not verified"),
    PLAN_LIMIT(HttpStatus.FORBIDDEN, "Plan limit exceeded"),
    SYMBOL_NOT_IN_WATCHLIST(HttpStatus.FORBIDDEN, "Symbol is not in the watchlist"),

    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    GONE(HttpStatus.GONE, "Resource is no longer available"),

    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "Email already registered"),
    CONFLICT(HttpStatus.CONFLICT, "Conflicting state"),

    LOCKED(HttpStatus.LOCKED, "Account temporarily locked"),

    UNPROCESSABLE(HttpStatus.UNPROCESSABLE_ENTITY, "Request cannot be executed"),
    MARKET_CLOSED(HttpStatus.UNPROCESSABLE_ENTITY, "Market is closed"),
    INSUFFICIENT_CASH(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient cash"),

    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded"),

    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");

    private final HttpStatus status;
    private final String defaultTitle;

    ErrorCode(HttpStatus status, String defaultTitle) {
        this.status = status;
        this.defaultTitle = defaultTitle;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultTitle() {
        return defaultTitle;
    }

    /** RFC 7807 problem type URI for this code. */
    public String type() {
        return "https://errors.stockpredict.com/" + name().toLowerCase().replace('_', '-');
    }
}

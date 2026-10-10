package com.stockpredict.common.error;

import java.util.Map;
import java.util.Objects;

/** Business exception carrying a stable {@link ErrorCode}. */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient Map<String, Object> extensions;

    public ApiException(ErrorCode errorCode, String detail) {
        this(errorCode, detail, Map.of());
    }

    public ApiException(ErrorCode errorCode, String detail, Map<String, Object> extensions) {
        super(detail == null ? errorCode.defaultTitle() : detail);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        this.extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

    public ApiException(ErrorCode errorCode, String detail, Throwable cause) {
        super(detail == null ? errorCode.defaultTitle() : detail, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        this.extensions = Map.of();
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Map<String, Object> extensions() {
        return extensions;
    }

    public static ApiException of(ErrorCode errorCode, String detail) {
        return new ApiException(errorCode, detail);
    }

    public static ApiException notFound(String what) {
        return new ApiException(ErrorCode.NOT_FOUND, what + " not found");
    }
}

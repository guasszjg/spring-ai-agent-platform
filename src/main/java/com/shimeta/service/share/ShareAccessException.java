package com.shimeta.service.share;

import org.springframework.http.HttpStatus;

/**
 * 访客接口的拒绝。message 直接显示给访客，所以不能带智能体、提示词、通道等内部信息；
 * 链接不可用的各种原因（过期、停用、用完、智能体停用）对访客统一用 {@link #linkInvalid()}
 */
public class ShareAccessException extends RuntimeException {

    public static final String LINK_INVALID = "link_invalid";
    public static final String SESSION_EXPIRED = "session_expired";
    public static final String ACCESS_CODE_INVALID = "access_code_invalid";
    public static final String TOO_MANY_ATTEMPTS = "too_many_attempts";
    public static final String RATE_LIMITED = "rate_limited";
    public static final String BAD_REQUEST = "bad_request";
    public static final String VISITOR_LIMIT = "visitor_limit";

    private final String code;
    private final HttpStatus status;
    private final long retryAfterSeconds;

    public ShareAccessException(String code, HttpStatus status, String message) {
        this(code, status, message, 0);
    }

    public ShareAccessException(String code, HttpStatus status, String message, long retryAfterSeconds) {
        super(message);
        this.code = code;
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static ShareAccessException linkInvalid() {
        return new ShareAccessException(LINK_INVALID, HttpStatus.FORBIDDEN, "链接已失效，请联系提供方");
    }

    public static ShareAccessException sessionExpired() {
        return new ShareAccessException(SESSION_EXPIRED, HttpStatus.UNAUTHORIZED, "访问已过期，请重新进入");
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}

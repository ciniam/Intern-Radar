package com.ciniam.internradar.security;

/**
 * 未登录或会话已失效
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}

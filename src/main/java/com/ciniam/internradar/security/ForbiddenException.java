package com.ciniam.internradar.security;

/**
 * 已登录但无权访问目标数据
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}

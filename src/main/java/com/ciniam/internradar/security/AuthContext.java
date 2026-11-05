package com.ciniam.internradar.security;

import com.ciniam.internradar.model.SysUser;

/**
 * 当前请求的登录用户。由拦截器写入，请求结束时清理
 */
public final class AuthContext {

    private static final ThreadLocal<SysUser> CURRENT = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void set(SysUser user) {
        CURRENT.set(user);
    }

    public static SysUser get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}

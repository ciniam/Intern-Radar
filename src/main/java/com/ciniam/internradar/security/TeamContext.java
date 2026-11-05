package com.ciniam.internradar.security;

/**
 * 当前请求所属的团队。由拦截器写入，定时任务在逐个用户处理前自行设置
 */
public final class TeamContext {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private TeamContext() {
    }

    public static void set(Long teamId) {
        CURRENT.set(teamId);
    }

    public static Long get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}

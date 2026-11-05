package com.ciniam.internradar.model;

import java.time.LocalDateTime;

/**
 * 登录结果。passwordResetRequired 为真时前端先引导改密再进页面
 */
public record LoginResult(
        String token,
        String userId,
        String name,
        UserRole role,
        LocalDateTime expireAt,
        boolean passwordResetRequired
) {
}

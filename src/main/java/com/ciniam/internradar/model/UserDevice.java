package com.ciniam.internradar.model;

import java.time.LocalDateTime;

/**
 * 一条登录设备记录，对应用户的一次会话
 */
public record UserDevice(
        Long id,
        String device,
        String ip,
        LocalDateTime loginAt,
        LocalDateTime expireAt,
        boolean current
) {
}

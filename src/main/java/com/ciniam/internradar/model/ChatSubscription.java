package com.ciniam.internradar.model;

/**
 * 一个待采集的会话。来源可以是配置文件，也可以是数据库里的白名单
 */
public record ChatSubscription(
        String userId,
        String chatId,
        String name,
        String chatType,
        String identity
) {

    public boolean userIdentity() {
        return "USER".equalsIgnoreCase(identity);
    }
}

package com.ciniam.internradar.push;

/**
 * 个人消息渠道，接收人为 open_id
 */
public interface UserPusher {

    String getChannel();

    PushResult push(String openId, String content);
}

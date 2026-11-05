package com.ciniam.internradar.push;

/**
 * 群消息渠道
 */
public interface GroupPusher {

    String getChannel();

    PushResult push(String content);
}

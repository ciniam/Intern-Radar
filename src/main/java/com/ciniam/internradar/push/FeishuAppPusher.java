package com.ciniam.internradar.push;

import com.ciniam.internradar.feishu.FeishuClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 企业自建应用机器人私发，按 open_id 单聊发送
 * 群自定义机器人不支持单聊，个人消息必须走这条通道
 */
@Component
public class FeishuAppPusher implements UserPusher {

    private static final Logger log = LoggerFactory.getLogger(FeishuAppPusher.class);

    private static final String CHANNEL = "feishu-app";

    private final FeishuClient feishuClient;

    public FeishuAppPusher(FeishuClient feishuClient) {
        this.feishuClient = feishuClient;
    }

    @Override
    public String getChannel() {
        return CHANNEL;
    }

    @Override
    public PushResult push(String openId, String content) {
        if (openId == null || openId.isBlank()) {
            return PushResult.fail("未配置接收人 open_id");
        }
        if (!feishuClient.appConfigured()) {
            return PushResult.fail("未配置飞书自建应用凭证，无法私发");
        }
        try {
            Map<String, Object> body = Map.of(
                    "receive_id", openId,
                    "msg_type", "text",
                    "content", feishuClient.toJson(Map.of("text", content)));
            feishuClient.post("/im/v1/messages?receive_id_type=open_id", body, null);
            return PushResult.ok();
        } catch (Exception e) {
            log.error("飞书私发失败，接收人 {}", openId, e);
            return PushResult.fail(e.getMessage());
        }
    }
}

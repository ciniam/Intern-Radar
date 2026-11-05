package com.ciniam.internradar.push;

import com.ciniam.internradar.config.InternRadarProperties;
import com.ciniam.internradar.service.SystemConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

/**
 * 飞书群自定义机器人推送，走 Webhook 直发，不需要企业自建应用
 */
@Component
public class FeishuWebhookPusher implements GroupPusher {

    private static final Logger log = LoggerFactory.getLogger(FeishuWebhookPusher.class);

    private static final String CHANNEL = "feishu-webhook";
    private static final String PLACEHOLDER = "REPLACE_WITH";

    private final InternRadarProperties properties;
    private final SystemConfigService systemConfig;
    private final RestClient restClient;

    public FeishuWebhookPusher(InternRadarProperties properties, SystemConfigService systemConfig) {
        this.properties = properties;
        this.systemConfig = systemConfig;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(properties.getFeishu().getTimeoutSeconds());
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public String getChannel() {
        return CHANNEL;
    }

    @Override
    public PushResult push(String content) {
        String webhookUrl = systemConfig.feishuWebhookUrl();
        if (webhookUrl == null || webhookUrl.isBlank() || webhookUrl.contains(PLACEHOLDER)) {
            return PushResult.fail("未配置飞书群机器人 Webhook 地址");
        }
        Map<String, Object> body = Map.of(
                "msg_type", "text",
                "content", Map.of("text", content));
        try {
            Map<?, ?> response = restClient.post()
                    .uri(webhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (response == null) {
                return PushResult.fail("飞书返回空响应");
            }
            String code = String.valueOf(response.get("code"));
            if ("0".equals(code)) {
                return PushResult.ok();
            }
            return PushResult.fail("飞书返回 code=" + code + "，msg=" + response.get("msg"));
        } catch (Exception e) {
            log.error("飞书群推送异常", e);
            return PushResult.fail(e.getMessage());
        }
    }
}

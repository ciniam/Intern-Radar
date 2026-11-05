package com.ciniam.internradar.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 飞书开放平台客户端，负责租户令牌缓存与通用请求
 */
@Component
public class FeishuClient {

    private static final Logger log = LoggerFactory.getLogger(FeishuClient.class);

    private static final String BASE_URL = "https://open.feishu.cn/open-apis";
    private static final String PLACEHOLDER = "REPLACE_WITH";
    private static final long EXPIRE_SAFETY_MILLIS = 60_000L;

    private final InternRadarProperties properties;
    private final SystemConfigService systemConfig;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    private volatile String tenantToken;
    private volatile long tenantTokenExpireAt;
    private volatile String tenantTokenAppId;

    public FeishuClient(InternRadarProperties properties, SystemConfigService systemConfig,
                        ObjectMapper objectMapper) {
        this.properties = properties;
        this.systemConfig = systemConfig;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(properties.getFeishu().getTimeoutSeconds());
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public boolean appConfigured() {
        String appId = systemConfig.feishuAppId();
        String appSecret = systemConfig.feishuAppSecret();
        return appId != null && !appId.isBlank() && !appId.contains(PLACEHOLDER)
                && appSecret != null && !appSecret.isBlank() && !appSecret.contains(PLACEHOLDER);
    }

    /**
     * 租户令牌，进程内缓存，过期前自动续取
     * 应用标识在界面上被改过时旧令牌立即作废，否则会一直用错应用的身份
     */
    public String tenantAccessToken() {
        String appId = systemConfig.feishuAppId();
        long now = System.currentTimeMillis();
        if (tenantToken != null && now < tenantTokenExpireAt && appId.equals(tenantTokenAppId)) {
            return tenantToken;
        }
        synchronized (this) {
            if (tenantToken != null && System.currentTimeMillis() < tenantTokenExpireAt
                    && appId.equals(tenantTokenAppId)) {
                return tenantToken;
            }
            Map<String, Object> body = Map.of(
                    "app_id", appId,
                    "app_secret", systemConfig.feishuAppSecret());
            Map<String, Object> response = request("POST", "/auth/v3/tenant_access_token/internal", body, null);
            String token = text(response, "tenant_access_token");
            if (token == null) {
                throw new FeishuApiException("获取租户令牌失败，响应：" + response);
            }
            int expire = number(response, "expire", 3600);
            this.tenantToken = token;
            this.tenantTokenAppId = appId;
            this.tenantTokenExpireAt = System.currentTimeMillis() + expire * 1000L - EXPIRE_SAFETY_MILLIS;
            log.info("飞书租户令牌已刷新，有效期 {} 秒", expire);
            return token;
        }
    }

    public Map<String, Object> post(String path, Object body, String userToken) {
        return request("POST", path, body, resolveToken(userToken));
    }

    public Map<String, Object> get(String path, String userToken) {
        return request("GET", path, null, resolveToken(userToken));
    }

    private String resolveToken(String userToken) {
        return userToken != null ? userToken : tenantAccessToken();
    }

    /**
     * 给指定用户发一条文本消息。官方说明：接收者是用户时，响应里会回带与该用户单聊的 chat_id
     * 这是获取机器人与用户单聊 chat_id 的官方途径，群列表接口不返回单聊
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> sendTextToUser(String openId, String text) {
        Map<String, Object> body = Map.of(
                "receive_id", openId,
                "msg_type", "text",
                "content", toJson(Map.of("text", text)));
        Map<String, Object> response = post("/im/v1/messages?receive_id_type=open_id", body, null);
        Object data = response.get("data");
        return data instanceof Map ? (Map<String, Object>) data : Map.of();
    }

    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> parse(String json) {
        try {
            return json == null || json.isBlank() ? Map.of() : objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    public String text(Map<String, Object> source, String key) {
        Object value = source == null ? null : source.get(key);
        return value == null ? null : String.valueOf(value);
    }

    public int number(Map<String, Object> source, String key, int defaultValue) {
        Object value = source == null ? null : source.get(key);
        if (value instanceof Number num) {
            return num.intValue();
        }
        try {
            return value == null ? defaultValue : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> request(String method, String path, Object body, String token) {
        try {
            RestClient.RequestBodySpec spec = restClient.method(org.springframework.http.HttpMethod.valueOf(method))
                    .uri(BASE_URL + path);
            if (token != null && !token.isBlank()) {
                spec.header("Authorization", "Bearer " + token);
            }
            Map<String, Object> response;
            if ("POST".equals(method)) {
                response = spec.contentType(MediaType.APPLICATION_JSON).body(body == null ? Map.of() : body)
                        .retrieve().body(Map.class);
            } else {
                response = spec.retrieve().body(Map.class);
            }
            if (response == null) {
                return Map.of();
            }
            Object code = response.get("code");
            if (code != null && !"0".equals(String.valueOf(code))) {
                throw new FeishuApiException("飞书接口返回 code=" + code + "，msg=" + response.get("msg"));
            }
            return response;
        } catch (FeishuApiException e) {
            throw e;
        } catch (Exception e) {
            throw new FeishuApiException("飞书请求失败：" + e.getMessage(), e);
        }
    }
}

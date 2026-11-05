package com.ciniam.internradar.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ciniam.internradar.config.InternRadarProperties;
import com.ciniam.internradar.entity.FeishuAuthEntity;
import com.ciniam.internradar.mapper.FeishuAuthMapper;
import com.ciniam.internradar.security.TokenCipher;
import com.ciniam.internradar.service.SystemConfigService;
import com.ciniam.internradar.service.TeamScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 飞书用户身份授权。读取本人私聊必须使用 user_access_token，需要用户本人完成一次授权
 * 令牌密文落库，过期自动用 refresh_token 续期
 */
@Service
public class FeishuUserAuthService {

    private static final Logger log = LoggerFactory.getLogger(FeishuUserAuthService.class);

    private static final String AUTHORIZE_URL = "https://accounts.feishu.cn/open-apis/authen/v1/authorize";
    private static final String BASE_URL = "https://open.feishu.cn/open-apis";
    private static final long EXPIRE_SAFETY_SECONDS = 120L;

    private final InternRadarProperties properties;
    private final SystemConfigService systemConfig;
    private final FeishuAuthMapper feishuAuthMapper;
    private final TokenCipher tokenCipher;
    private final ObjectMapper objectMapper;
    private final TeamScope teamScope;
    private final RestClient restClient;

    public FeishuUserAuthService(InternRadarProperties properties,
                                 SystemConfigService systemConfig,
                                 FeishuAuthMapper feishuAuthMapper,
                                 TokenCipher tokenCipher,
                                 ObjectMapper objectMapper,
                                 TeamScope teamScope) {
        this.properties = properties;
        this.systemConfig = systemConfig;
        this.feishuAuthMapper = feishuAuthMapper;
        this.tokenCipher = tokenCipher;
        this.objectMapper = objectMapper;
        this.teamScope = teamScope;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(properties.getFeishu().getTimeoutSeconds());
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * 聊天采集授权链接，state 带上 userId，回调时据此绑定授权人
     */
    public String authorizeUrl(String userId) {
        return authorizeUrl(userId, systemConfig.feishuRedirectUri());
    }

    /**
     * 授权链接。登录与聊天采集的回调地址必须分开传，两条回调的处理逻辑不同
     */
    public String authorizeUrl(String state, String redirectUri) {
        return AUTHORIZE_URL + "?client_id=" + encode(systemConfig.feishuAppId())
                + "&response_type=code"
                + "&redirect_uri=" + encode(redirectUri)
                + "&scope=" + encode(systemConfig.feishuOauthScope())
                + "&state=" + encode(state)
                + "&prompt=consent";
    }

    /**
     * 一次授权换回的身份与令牌。open_id 用于反查系统用户，是本项目做登录的关键
     */
    public record FeishuIdentity(String openId, String name, String scope,
                                 String accessToken, String refreshToken, int expiresIn) {
    }

    /**
     * 处理授权回调，保存加密后的令牌
     */
    public String handleCallback(String code, String state) {
        if (code == null || code.isBlank()) {
            return "授权失败：回调未携带 code";
        }
        String userId = state == null || state.isBlank() ? "unknown" : state;
        FeishuIdentity identity = authorize(code);
        if (identity.accessToken() == null || identity.refreshToken() == null) {
            return "授权失败：未取得令牌";
        }
        save(userId, identity.openId(), identity.accessToken(), identity.refreshToken(),
                identity.scope(), identity.expiresIn());
        log.info("飞书用户授权完成，用户 {}，令牌有效期 {} 秒，授权范围 {}",
                userId, identity.expiresIn(), identity.scope());
        return "授权成功，用户 " + userId + " 已绑定。\n本次授权范围：" + identity.scope()
                + "\n请核对是否包含 im:message:readonly、im:chat:readonly、im:message.p2p_msg:get_as_user";
    }

    /**
     * 用授权码换取身份与令牌。部分响应不带 open_id，此时再查一次用户信息兜底
     */
    public FeishuIdentity authorize(String code) {
        JsonNode data = exchangeCode(code);
        String accessToken = data.path("access_token").asText(null);
        String refreshToken = data.path("refresh_token").asText(null);
        if (accessToken == null) {
            throw new FeishuApiException("换取用户令牌失败，响应 " + data);
        }
        String openId = data.path("open_id").asText("");
        String name = data.path("name").asText("");
        if (openId.isBlank()) {
            JsonNode info = userInfo(accessToken);
            openId = info.path("open_id").asText("");
            name = info.path("name").asText(name);
        }
        return new FeishuIdentity(openId, name, data.path("scope").asText(""),
                accessToken, refreshToken, data.path("expires_in").asInt(7200));
    }

    /**
     * 查询令牌对应的用户信息，用于拿 open_id
     */
    private JsonNode userInfo(String userAccessToken) {
        try {
            Map<?, ?> response = restClient.get()
                    .uri(BASE_URL + "/authen/v1/user_info")
                    .header("Authorization", "Bearer " + userAccessToken)
                    .retrieve()
                    .body(Map.class);
            JsonNode root = objectMapper.valueToTree(response == null ? Map.of() : response);
            if (root.path("code").asInt(-1) != 0) {
                log.warn("查询飞书用户信息失败，code={}，msg={}",
                        root.path("code").asInt(), root.path("msg").asText());
            }
            return root.path("data");
        } catch (Exception e) {
            log.warn("查询飞书用户信息异常：{}", e.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    /**
     * 取可用的用户令牌，过期自动刷新，未授权返回 null
     */
    public synchronized String accessToken(String userId) {
        FeishuAuthEntity entity = feishuAuthMapper.selectByUser(userId);
        if (entity == null) {
            log.warn("用户 {} 尚未完成飞书授权，跳过需要用户身份的采集", userId);
            return null;
        }
        if (entity.getExpireAt() != null
                && entity.getExpireAt().isAfter(LocalDateTime.now().plusSeconds(EXPIRE_SAFETY_SECONDS))) {
            return tokenCipher.decrypt(entity.getAccessToken());
        }
        return refresh(userId, entity);
    }

    public boolean authorized(String userId) {
        return feishuAuthMapper.selectByUser(userId) != null;
    }

    /**
     * 登录成功后把令牌一并落库，这样采集本人会话不必再单独授权一次
     */
    public void bindTokens(String userId, FeishuIdentity identity) {
        save(userId, identity.openId(), identity.accessToken(), identity.refreshToken(),
                identity.scope(), identity.expiresIn());
    }

    private String refresh(String userId, FeishuAuthEntity entity) {
        String refreshToken = tokenCipher.decrypt(entity.getRefreshToken());
        // 刷新与换取是两个不同接口，混用会返回 20003
        JsonNode data = callToken("/authen/v1/oidc/refresh_access_token",
                Map.of("grant_type", "refresh_token", "refresh_token", refreshToken));
        String accessToken = data.path("access_token").asText(null);
        if (accessToken == null) {
            log.error("飞书令牌刷新失败，用户 {}，响应 {}", userId, data);
            return null;
        }
        String newRefresh = data.path("refresh_token").asText(refreshToken);
        String scope = data.path("scope").asText(entity.getScope());
        save(userId, entity.getOpenId(), accessToken, newRefresh, scope, data.path("expires_in").asInt(7200));
        log.info("飞书用户令牌已刷新，用户 {}，授权范围 {}", userId, scope);
        return accessToken;
    }

    private void save(String userId, String openId, String accessToken, String refreshToken,
                      String scope, int expiresIn) {
        feishuAuthMapper.upsert(FeishuAuthEntity.builder()
                .teamId(teamScope.of(userId))
                .userId(userId)
                .openId(openId)
                .accessToken(tokenCipher.encrypt(accessToken))
                .refreshToken(tokenCipher.encrypt(refreshToken))
                .scope(scope)
                .expireAt(LocalDateTime.now().plusSeconds(expiresIn))
                .updatedAt(LocalDateTime.now())
                .build());
    }

    /**
     * 令牌实际授予的权限范围，用于快速判断某项接口为什么被拒
     */
    public String grantedScope(String userId) {
        FeishuAuthEntity entity = feishuAuthMapper.selectByUser(userId);
        return entity == null || entity.getScope() == null ? "" : entity.getScope();
    }

    /**
     * 调用令牌接口，需要 app_access_token
     * 换取与刷新是两个不同路径，由调用方指定
     */
    private JsonNode callToken(String path, Map<String, Object> body) {
        try {
            String appToken = appAccessToken();
            Map<?, ?> response = restClient.post()
                    .uri(BASE_URL + path)
                    .header("Authorization", "Bearer " + appToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            JsonNode root = objectMapper.valueToTree(response == null ? Map.of() : response);
            if (root.path("code").asInt(-1) != 0) {
                log.error("飞书令牌接口 {} 返回异常，code={}，msg={}", path, root.path("code").asInt(),
                        root.path("msg").asText());
            }
            return root.path("data");
        } catch (Exception e) {
            log.error("飞书令牌接口 {} 调用失败", path, e);
            return objectMapper.createObjectNode();
        }
    }

    private JsonNode exchangeCode(String code) {
        return callToken("/authen/v1/oidc/access_token",
                Map.of("grant_type", "authorization_code", "code", code));
    }

    private String appAccessToken() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("app_id", systemConfig.feishuAppId());
        body.put("app_secret", systemConfig.feishuAppSecret());
        Map<?, ?> response = restClient.post()
                .uri(BASE_URL + "/auth/v3/app_access_token/internal")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        if (response == null) {
            throw new FeishuApiException("获取 app_access_token 返回空响应");
        }
        JsonNode root = objectMapper.valueToTree(response);
        String token = root.path("app_access_token").asText(null);
        if (token == null) {
            throw new FeishuApiException("获取 app_access_token 失败，响应 " + root);
        }
        return token;
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /**
     * 供排障使用，展示当前授权状态
     */
    public String status(String userId) {
        FeishuAuthEntity entity = feishuAuthMapper.selectByUser(userId);
        if (entity == null) {
            return "未授权";
        }
        return "已授权，令牌到期时间 " + entity.getExpireAt() + "，更新时间 " + entity.getUpdatedAt()
                + "，授权范围：" + (entity.getScope() == null ? "未记录" : entity.getScope());
    }

    public String requestedScope() {
        return systemConfig.feishuOauthScope();
    }

    public String callbackUri() {
        return UriComponentsBuilder.fromUriString(systemConfig.feishuRedirectUri()).toUriString();
    }
}

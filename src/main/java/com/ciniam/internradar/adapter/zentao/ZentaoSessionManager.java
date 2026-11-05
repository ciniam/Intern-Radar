package com.ciniam.internradar.adapter.zentao;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ciniam.internradar.config.InternRadarProperties;
import com.ciniam.internradar.model.DataSourceCredential;
import com.ciniam.internradar.service.SystemConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 禅道会话管理。登录属于高风险动作，连续失败会锁号，因此做三重保护：
 * 一是默认关闭登录的显式闸门，二是会话复用减少登录次数，三是失败熔断
 * 会话按使用者隔离：每人一份独立 Cookie 与登录状态，互不干扰
 */
@Component
public class ZentaoSessionManager {

    private static final Logger log = LoggerFactory.getLogger(ZentaoSessionManager.class);

    private static final long BREAK_MILLIS = 30 * 60 * 1000L;
    private static final String DEFAULT_KEY = "default";

    private final InternRadarProperties properties;
    private final SystemConfigService systemConfig;
    private final ObjectMapper objectMapper;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public ZentaoSessionManager(InternRadarProperties properties,
                                SystemConfigService systemConfig,
                                ObjectMapper objectMapper) {
        this.properties = properties;
        this.systemConfig = systemConfig;
        this.objectMapper = objectMapper;
    }

    public static String defaultKey() {
        return DEFAULT_KEY;
    }

    /**
     * 确保该会话可用。返回 false 时调用方应直接跳过采集
     */
    public boolean ensureSession(String key, DataSourceCredential credential) {
        Session session = session(key);
        if (session.blocked()) {
            session.lastError = "登录连续失败已熔断，恢复时间 " + LocalDateTime.now().plusMinutes(30);
            log.warn("禅道会话不可用，会话 {}：{}", key, session.lastError);
            return false;
        }
        if (session.alive(properties)) {
            return true;
        }
        if (!credential.allowLogin()) {
            session.lastError = "未开启登录闸门，已跳过登录";
            log.warn("禅道会话 {} {}。该开关用于避免反复登录导致账号锁定", key, session.lastError);
            return false;
        }
        return login(key, session, credential);
    }

    public ZentaoHttpClient client(String key) {
        return session(key).client;
    }

    /**
     * 配置页的登录探针：用给定站点地址与凭据走一次完整登录
     * 使用独立的临时会话，不影响正在使用的登录态，也不受熔断影响
     */
    public Map<String, Object> probe(String baseUrl, String account, String password) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (baseUrl == null || baseUrl.isBlank()) {
            result.put("success", false);
            result.put("message", "未填写站点地址");
            return result;
        }
        if (account == null || account.isBlank() || password == null || password.isBlank()) {
            result.put("success", false);
            result.put("message", "未填写账号或密码");
            return result;
        }
        Session temp = new Session("probe", new ZentaoHttpClient(properties, systemConfig));
        temp.client.overrideBaseUrl(baseUrl);
        DataSourceCredential credential = new DataSourceCredential(account, password, true, "探针");
        boolean ok;
        try {
            ok = login("probe", temp, credential);
        } catch (Exception e) {
            ok = false;
            temp.lastError = e.getMessage();
        }
        result.put("success", ok);
        result.put("message", ok ? "登录成功" : "登录失败：" + (temp.lastError == null ? "未知原因" : temp.lastError));
        result.put("baseUrl", temp.client.baseUrl());
        return result;
    }

    public void invalidate(String key) {
        Session session = session(key);
        session.lastLoginAt = null;
        session.client.clearCookies();
        log.info("禅道会话已失效，会话 {}，下次采集将重新建立", key);
    }

    public String status(String key) {
        Session session = session(key);
        return "session=" + key
                + ", loggedIn=" + session.alive(properties)
                + ", lastLoginAt=" + session.lastLoginAt
                + ", consecutiveFailures=" + session.consecutiveFailures.get()
                + ", blocked=" + session.blocked()
                + ", lastError=" + session.lastError;
    }

    private Session session(String key) {
        String resolved = key == null || key.isBlank() ? DEFAULT_KEY : key;
        return sessions.computeIfAbsent(resolved,
                name -> new Session(name, new ZentaoHttpClient(properties, systemConfig)));
    }

    private boolean login(String key, Session session, DataSourceCredential credential) {
        String sessionId = fetchSessionId(session);
        if (sessionId == null) {
            return onFailure(session, credential, null);
        }
        Map<String, String> form = new HashMap<>();
        form.put("account", credential.account());
        form.put("password", credential.password());
        form.put("sessionName", sessionId);
        form.put("keepLogin", "0");
        form.put("referer", "");
        ZentaoHttpClient.Response response = session.client.postForm("/user-login.json", form);
        if (!response.reachable()) {
            return onFailure(session, credential, response.body());
        }
        JsonNode root = read(response.body());
        boolean success = root != null && "success".equalsIgnoreCase(root.path("status").asText(""));
        if (!success) {
            return onFailure(session, credential, "登录被拒，原文片段：" + shorten(response.body()));
        }
        session.lastLoginAt = LocalDateTime.now();
        session.consecutiveFailures.set(0);
        session.lastError = null;
        log.info("禅道登录成功，会话 {}，凭据来源 {}，有效期 {} 分钟",
                key, credential.origin(), properties.getZentao().getSessionTtlMinutes());
        return true;
    }

    private String fetchSessionId(Session session) {
        ZentaoHttpClient.Response response = session.client.get("/api-getSessionID.json");
        if (!response.reachable()) {
            onFailure(session, null, response.body());
            return null;
        }
        JsonNode root = read(response.body());
        if (root == null) {
            onFailure(session, null, "获取 SessionID 返回非 JSON：" + shorten(response.body()));
            return null;
        }
        JsonNode data = root.path("data");
        if (data.isTextual()) {
            data = read(data.asText());
        }
        String sessionId = data == null ? null : data.path("sessionID").asText(null);
        if (sessionId == null || sessionId.isBlank()) {
            onFailure(session, null, "未取到 sessionID，原文片段：" + shorten(response.body()));
            return null;
        }
        return sessionId;
    }

    private boolean onFailure(Session session, DataSourceCredential credential, String reason) {
        int failures = session.consecutiveFailures.incrementAndGet();
        session.lastError = reason;
        int threshold = properties.getZentao().getMaxConsecutiveFailures();
        log.error("禅道登录失败，会话 {}，账号 {}，累计 {} 次，原因：{}",
                session.name, credential == null ? "未知" : credential.account(), failures, reason);
        if (failures >= threshold) {
            session.blockedUntil = System.currentTimeMillis() + BREAK_MILLIS;
            log.error("禅道登录连续失败达到 {} 次，会话 {} 已熔断 30 分钟。请检查账号是否被锁定或密码是否过期，不要再重试",
                    failures, session.name);
        }
        return false;
    }

    private JsonNode read(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    private String shorten(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    /**
     * 单个使用者的会话：独立的 HTTP 客户端与登录状态
     */
    private static final class Session {

        private final String name;
        private final ZentaoHttpClient client;
        private final AtomicInteger consecutiveFailures = new AtomicInteger();
        private volatile long blockedUntil;
        private volatile LocalDateTime lastLoginAt;
        private volatile String lastError;

        private Session(String name, ZentaoHttpClient client) {
            this.name = name;
            this.client = client;
        }

        private boolean alive(InternRadarProperties properties) {
            return lastLoginAt != null && lastLoginAt
                    .plusMinutes(properties.getZentao().getSessionTtlMinutes())
                    .isAfter(LocalDateTime.now());
        }

        private boolean blocked() {
            return blockedUntil > System.currentTimeMillis();
        }
    }
}

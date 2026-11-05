package com.ciniam.internradar.service;

import com.ciniam.internradar.config.InternRadarProperties;
import com.ciniam.internradar.entity.TeamSettingEntity;
import com.ciniam.internradar.mapper.TeamSettingMapper;
import com.ciniam.internradar.model.SystemSettings;
import com.ciniam.internradar.model.SystemSettingsView;
import com.ciniam.internradar.security.TokenCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 服务端级配置读写：飞书应用、内部任务系统站点、git 兜底仓库
 * 复用团队设置表，库里有值就以库为准，没有则回退 application.yml，改完即时生效无需重启
 * 密钥类字段加密入库，读取接口只回传是否已配置，不回传明文
 */
@Service
public class SystemConfigService {

    private static final Logger log = LoggerFactory.getLogger(SystemConfigService.class);

    /**
     * 服务端密钥类配置仍按默认团队存一份。飞书应用凭据要在登录前就用上，
     * 而登录时还拿不到团队归属，等接入多企业的独立应用时再按团队拆
     */
    private static final long CONFIG_TEAM_ID = 1L;
    private static final long CACHE_MILLIS = 3000L;

    public static final String KEY_FEISHU_APP_ID = "feishu.appId";
    public static final String KEY_FEISHU_APP_SECRET = "feishu.appSecret";
    public static final String KEY_FEISHU_WEBHOOK_URL = "feishu.webhookUrl";
    public static final String KEY_FEISHU_REDIRECT_URI = "feishu.oauthRedirectUri";
    public static final String KEY_FEISHU_LOGIN_REDIRECT_URI = "feishu.loginRedirectUri";
    public static final String KEY_FEISHU_OAUTH_SCOPE = "feishu.oauthScope";
    public static final String KEY_ZENTAO_BASE_URL = "zentao.baseUrl";
    public static final String KEY_ZENTAO_ACCOUNT = "zentao.account";
    public static final String KEY_ZENTAO_PASSWORD = "zentao.password";
    public static final String KEY_ZENTAO_ALLOW_LOGIN = "zentao.allowLogin";
    public static final String KEY_ZENTAO_SKIP_TLS = "zentao.skipTlsVerify";
    public static final String KEY_GIT_REPOSITORIES = "git.repositories";
    public static final String KEY_MODEL_BASE_URL = "model.baseUrl";
    public static final String KEY_MODEL_API_KEY = "model.apiKey";
    public static final String KEY_MODEL_NAME = "model.name";
    public static final String KEY_MODEL_TEMPERATURE = "model.temperature";
    public static final String KEY_MODEL_TIMEOUT = "model.timeoutSeconds";
    public static final String KEY_SEARCH_API_KEY = "search.apiKey";
    public static final String KEY_MAIL_HOST = "mail.host";
    public static final String KEY_MAIL_PORT = "mail.port";
    public static final String KEY_MAIL_USERNAME = "mail.username";
    public static final String KEY_MAIL_PASSWORD = "mail.password";
    public static final String KEY_MAIL_FROM = "mail.from";
    public static final String KEY_MAIL_SSL = "mail.ssl";
    public static final String KEY_MAIL_STARTTLS = "mail.starttls";

    private static final Set<String> SECRET_KEYS = Set.of(KEY_FEISHU_APP_SECRET, KEY_ZENTAO_PASSWORD,
            KEY_MODEL_API_KEY, KEY_SEARCH_API_KEY, KEY_MAIL_PASSWORD);

    private final TeamSettingMapper teamSettingMapper;
    private final InternRadarProperties properties;
    private final TokenCipher tokenCipher;

    /**
     * 模型密钥的兜底来源。密钥历来只写在本地配置文件里，键名沿用原有写法，避免两处维护
     */
    @Value("${spring.ai.openai.api-key:}")
    private String springAiApiKey;

    private volatile Map<String, String> cache;
    private volatile long cachedAt;

    public SystemConfigService(TeamSettingMapper teamSettingMapper,
                               InternRadarProperties properties,
                               TokenCipher tokenCipher) {
        this.teamSettingMapper = teamSettingMapper;
        this.properties = properties;
        this.tokenCipher = tokenCipher;
    }

    public String feishuAppId() {
        return text(KEY_FEISHU_APP_ID, properties.getFeishu().getAppId());
    }

    public String feishuAppSecret() {
        return secret(KEY_FEISHU_APP_SECRET, properties.getFeishu().getAppSecret());
    }

    public String feishuWebhookUrl() {
        return text(KEY_FEISHU_WEBHOOK_URL, properties.getFeishu().getWebhookUrl());
    }

    public String feishuRedirectUri() {
        return text(KEY_FEISHU_REDIRECT_URI, properties.getFeishu().getOauthRedirectUri());
    }

    public String feishuLoginRedirectUri() {
        return text(KEY_FEISHU_LOGIN_REDIRECT_URI, properties.getFeishu().getLoginRedirectUri());
    }

    public String feishuOauthScope() {
        return text(KEY_FEISHU_OAUTH_SCOPE, properties.getFeishu().getOauthScope());
    }

    /**
     * 联网搜索密钥，走 Firecrawl。基线里的 search-api.api-key 是另一家服务，不能互相兜底
     * 两处都没配就表示不启用联网搜索，学习问答里不会出现该工具
     */
    public String searchApiKey() {
        return secret(KEY_SEARCH_API_KEY, properties.getSearch().getApiKey());
    }

    public boolean searchConfigured() {
        String key = searchApiKey();
        return key != null && !key.isBlank();
    }

    public String searchApiKeySource() {
        return source(KEY_SEARCH_API_KEY, properties.getSearch().getApiKey());
    }

    public String zentaoBaseUrl() {
        return text(KEY_ZENTAO_BASE_URL, properties.getZentao().getBaseUrl());
    }

    public String zentaoAccount() {
        return text(KEY_ZENTAO_ACCOUNT, properties.getZentao().getAccount());
    }

    public String zentaoPassword() {
        return secret(KEY_ZENTAO_PASSWORD, properties.getZentao().getPassword());
    }

    public boolean zentaoAllowLogin() {
        return flag(KEY_ZENTAO_ALLOW_LOGIN, properties.getZentao().isAllowLogin());
    }

    public boolean zentaoSkipTlsVerify() {
        return flag(KEY_ZENTAO_SKIP_TLS, properties.getZentao().isSkipTlsVerify());
    }

    /**
     * 团队级兜底仓库列表。个人偏好里配了仓库时不会用到这里
     */
    public List<String> gitRepositories() {
        String raw = raw(KEY_GIT_REPOSITORIES);
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>(properties.getGit().getRepositories());
        }
        List<String> result = new ArrayList<>();
        for (String item : raw.split("[,，;；\\n]")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    public String modelBaseUrl() {
        return text(KEY_MODEL_BASE_URL, properties.getModel().getBaseUrl());
    }

    public String modelApiKey() {
        return secret(KEY_MODEL_API_KEY, modelApiKeyFallback());
    }

    public String modelName() {
        return text(KEY_MODEL_NAME, properties.getModel().getName());
    }

    public Double modelTemperature() {
        return decimal(KEY_MODEL_TEMPERATURE, properties.getModel().getTemperature());
    }

    public int modelTimeoutSeconds() {
        return integer(KEY_MODEL_TIMEOUT, properties.getModel().getTimeoutSeconds());
    }

    public String mailHost() {
        return text(KEY_MAIL_HOST, properties.getMail().getHost());
    }

    public int mailPort() {
        return integer(KEY_MAIL_PORT, properties.getMail().getPort());
    }

    public String mailUsername() {
        return text(KEY_MAIL_USERNAME, properties.getMail().getUsername());
    }

    public String mailPassword() {
        return secret(KEY_MAIL_PASSWORD, properties.getMail().getPassword());
    }

    public String mailFrom() {
        return text(KEY_MAIL_FROM, properties.getMail().getFrom());
    }

    public boolean mailSsl() {
        return flag(KEY_MAIL_SSL, properties.getMail().isSsl());
    }

    public boolean mailStarttls() {
        return flag(KEY_MAIL_STARTTLS, properties.getMail().isStarttls());
    }

    public int mailTimeoutSeconds() {
        return properties.getMail().getTimeoutSeconds();
    }

    /**
     * 发信邮箱是否可用，决定忘记密码能否自助完成
     */
    public boolean mailConfigured() {
        return !isBlank(mailHost()) && !isBlank(mailFrom());
    }

    public String mailPasswordSource() {
        return source(KEY_MAIL_PASSWORD, properties.getMail().getPassword());
    }

    public String feishuAppSecretSource() {
        return source(KEY_FEISHU_APP_SECRET, properties.getFeishu().getAppSecret());
    }

    public String zentaoPasswordSource() {
        return source(KEY_ZENTAO_PASSWORD, properties.getZentao().getPassword());
    }

    public String modelApiKeySource() {
        return source(KEY_MODEL_API_KEY, modelApiKeyFallback());
    }

    private String modelApiKeyFallback() {
        String own = properties.getModel().getApiKey();
        return own == null || own.isBlank() ? springAiApiKey : own;
    }

    /**
     * 当前生效配置，密钥只给是否已配置与来源
     */
    public SystemSettingsView view() {
        String appSecret = feishuAppSecret();
        String zentaoPassword = zentaoPassword();
        String apiKey = modelApiKey();
        String searchKey = searchApiKey();
        return new SystemSettingsView(
                new SystemSettingsView.Feishu(feishuAppId(), !isBlank(appSecret), feishuAppSecretSource(),
                        feishuWebhookUrl(), feishuRedirectUri(), feishuLoginRedirectUri(), feishuOauthScope()),
                new SystemSettingsView.Zentao(zentaoBaseUrl(), zentaoAccount(), !isBlank(zentaoPassword),
                        zentaoPasswordSource(), zentaoAllowLogin(), zentaoSkipTlsVerify()),
                new SystemSettingsView.Git(gitRepositories()),
                new SystemSettingsView.Model(modelBaseUrl(), !isBlank(apiKey), modelApiKeySource(),
                        modelName(), modelTemperature(), modelTimeoutSeconds()),
                new SystemSettingsView.Search(!isBlank(searchKey), searchApiKeySource()),
                new SystemSettingsView.Mail(mailHost(), mailPort(), mailUsername(),
                        !isBlank(mailPassword()), mailPasswordSource(), mailFrom(), mailSsl(),
                        mailStarttls(), mailConfigured()));
    }

    /**
     * 保存服务端配置。密钥字段约定：null 表示不改动，空串表示清除，其余按新值加密入库
     */
    public void save(SystemSettings settings) {
        if (settings == null) {
            return;
        }
        SystemSettings.Feishu feishu = settings.feishu();
        if (feishu != null) {
            upsert(KEY_FEISHU_APP_ID, feishu.appId(), false);
            upsert(KEY_FEISHU_WEBHOOK_URL, feishu.webhookUrl(), false);
            upsert(KEY_FEISHU_REDIRECT_URI, feishu.redirectUri(), false);
            upsert(KEY_FEISHU_LOGIN_REDIRECT_URI, feishu.loginRedirectUri(), false);
            upsert(KEY_FEISHU_OAUTH_SCOPE, feishu.oauthScope(), false);
            upsert(KEY_FEISHU_APP_SECRET, feishu.appSecret(), true);
        }
        SystemSettings.Zentao zentao = settings.zentao();
        if (zentao != null) {
            upsert(KEY_ZENTAO_BASE_URL, zentao.baseUrl(), false);
            upsert(KEY_ZENTAO_ACCOUNT, zentao.account(), false);
            upsert(KEY_ZENTAO_PASSWORD, zentao.password(), true);
            if (zentao.allowLogin() != null) {
                upsert(KEY_ZENTAO_ALLOW_LOGIN, String.valueOf(zentao.allowLogin()), false);
            }
            if (zentao.skipTlsVerify() != null) {
                upsert(KEY_ZENTAO_SKIP_TLS, String.valueOf(zentao.skipTlsVerify()), false);
            }
        }
        SystemSettings.Git git = settings.git();
        if (git != null && git.repositories() != null) {
            upsert(KEY_GIT_REPOSITORIES, String.join(",", git.repositories()), false);
        }
        SystemSettings.Model model = settings.model();
        if (model != null) {
            upsert(KEY_MODEL_BASE_URL, model.baseUrl(), false);
            upsert(KEY_MODEL_NAME, model.name(), false);
            upsert(KEY_MODEL_API_KEY, model.apiKey(), true);
            if (model.temperature() != null) {
                upsert(KEY_MODEL_TEMPERATURE, String.valueOf(model.temperature()), false);
            }
            if (model.timeoutSeconds() != null) {
                upsert(KEY_MODEL_TIMEOUT, String.valueOf(model.timeoutSeconds()), false);
            }
        }
        SystemSettings.Search search = settings.search();
        if (search != null) {
            upsert(KEY_SEARCH_API_KEY, search.apiKey(), true);
        }
        SystemSettings.Mail mail = settings.mail();
        if (mail != null) {
            upsert(KEY_MAIL_HOST, mail.host(), false);
            upsert(KEY_MAIL_PORT, mail.port() == null ? null : String.valueOf(mail.port()), false);
            upsert(KEY_MAIL_USERNAME, mail.username(), false);
            upsert(KEY_MAIL_PASSWORD, mail.password(), true);
            upsert(KEY_MAIL_FROM, mail.from(), false);
            upsert(KEY_MAIL_SSL, mail.ssl() == null ? null : String.valueOf(mail.ssl()), false);
            upsert(KEY_MAIL_STARTTLS, mail.starttls() == null ? null : String.valueOf(mail.starttls()), false);
        }
        cache = null;
        log.info("服务端配置已保存，即时生效");
    }

    private void upsert(String key, String value, boolean secret) {
        if (value == null) {
            return;
        }
        String stored = value.isBlank() ? "" : (secret ? tokenCipher.encrypt(value.trim()) : value.trim());
        teamSettingMapper.upsert(TeamSettingEntity.builder()
                .teamId(CONFIG_TEAM_ID)
                .settingKey(key)
                .settingValue(stored)
                .updatedAt(LocalDateTime.now())
                .build());
    }

    private String raw(String key) {
        return values().get(key);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String text(String key, String fallback) {
        String value = raw(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean flag(String key, boolean fallback) {
        String value = raw(key);
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value.trim());
    }

    private Double decimal(String key, Double fallback) {
        String value = raw(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Double.valueOf(value.trim());
        } catch (NumberFormatException e) {
            log.warn("配置项 {} 不是合法小数，回退配置文件中的值", key);
            return fallback;
        }
    }

    private int integer(String key, int fallback) {
        String value = raw(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("配置项 {} 不是合法整数，回退配置文件中的值", key);
            return fallback;
        }
    }

    /**
     * 密钥读取：库里存的是密文，解密失败时回退配置文件，避免一处改坏导致整站不可用
     */
    private String secret(String key, String fallback) {
        String value = raw(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return tokenCipher.decrypt(value);
        } catch (Exception e) {
            log.error("配置项 {} 解密失败，回退配置文件中的值", key, e);
            return fallback;
        }
    }

    private String source(String key, String fallback) {
        String value = raw(key);
        if (value != null && !value.isBlank()) {
            return "界面配置";
        }
        return fallback == null || fallback.isBlank() ? "未配置" : "配置文件";
    }

    private Map<String, String> values() {
        Map<String, String> local = cache;
        if (local != null && System.currentTimeMillis() - cachedAt < CACHE_MILLIS) {
            return local;
        }
        Map<String, String> loaded = new LinkedHashMap<>();
        try {
            for (TeamSettingEntity entity : teamSettingMapper.selectByTeam(CONFIG_TEAM_ID)) {
                loaded.put(entity.getSettingKey(), entity.getSettingValue());
            }
        } catch (Exception e) {
            log.warn("服务端配置读取失败，全部回退配置文件：{}", e.getMessage());
        }
        cache = loaded;
        cachedAt = System.currentTimeMillis();
        return loaded;
    }
}

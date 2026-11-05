package com.ciniam.internradar.model;

import java.util.List;

/**
 * 服务端级配置的提交体
 * 密钥字段约定：null 表示不改动，空串表示清除，其余按新值加密入库
 */
public record SystemSettings(
        Feishu feishu,
        Zentao zentao,
        Git git,
        Model model,
        Search search,
        Mail mail
) {

    /**
     * 联网搜索（Firecrawl）。密钥留空表示不启用联网搜索，学习问答里不会出现该工具
     */
    public record Search(String apiKey) {
    }

    /**
     * 发信邮箱。留空表示不启用自助找回密码，此时只能由管理员重置
     */
    public record Mail(String host, Integer port, String username, String password,
                       String from, Boolean ssl, Boolean starttls) {
    }

    public record Feishu(String appId, String appSecret, String webhookUrl,
                         String redirectUri, String loginRedirectUri, String oauthScope) {
    }

    public record Zentao(String baseUrl, String account, String password,
                         Boolean allowLogin, Boolean skipTlsVerify) {
    }

    public record Git(List<String> repositories) {
    }

    public record Model(String baseUrl, String apiKey, String name, Double temperature, Integer timeoutSeconds) {
    }
}

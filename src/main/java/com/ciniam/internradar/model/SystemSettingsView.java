package com.ciniam.internradar.model;

import java.util.List;

/**
 * 服务端级配置的展示体。密钥只回传是否已配置与来源，不回传明文
 */
public record SystemSettingsView(
        Feishu feishu,
        Zentao zentao,
        Git git,
        Model model,
        Search search,
        Mail mail
) {

    public record Feishu(String appId, boolean appSecretSet, String appSecretSource,
                         String webhookUrl, String redirectUri, String loginRedirectUri, String oauthScope) {
    }

    public record Zentao(String baseUrl, String account, boolean passwordSet, String passwordSource,
                         boolean allowLogin, boolean skipTlsVerify) {
    }

    public record Git(List<String> repositories) {
    }

    public record Model(String baseUrl, boolean apiKeySet, String apiKeySource,
                        String name, double temperature, int timeoutSeconds) {
    }

    public record Search(boolean apiKeySet, String apiKeySource) {
    }

    public record Mail(String host, int port, String username, boolean passwordSet,
                       String passwordSource, String from, boolean ssl, boolean starttls,
                       boolean configured) {
    }
}

package com.ciniam.internradar.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 模型注册表。地址、密钥、模型名与超时以库中配置为准，配置文件只作兜底
 * 配置变化时按需重建实例，界面上改完即时生效，无需重启
 * 基线页面仍使用框架自动装配的实例，本类只服务业务链路
 */
@Service
public class ModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistry.class);

    private static final int MIN_TIMEOUT_SECONDS = 5;

    private final SystemConfigService systemConfigService;

    private volatile OpenAiChatModel chatModel;
    private volatile String signature;

    public ModelRegistry(SystemConfigService systemConfigService) {
        this.systemConfigService = systemConfigService;
    }

    public OpenAiChatModel chatModel() {
        String current = signature();
        OpenAiChatModel local = chatModel;
        if (local != null && current.equals(signature)) {
            return local;
        }
        synchronized (this) {
            if (chatModel == null || !current.equals(signature)) {
                chatModel = build(systemConfigService.modelBaseUrl(), systemConfigService.modelApiKey(),
                        systemConfigService.modelName(), systemConfigService.modelTemperature(),
                        systemConfigService.modelTimeoutSeconds());
                signature = current;
                log.info("模型实例已按配置重建，地址 {}，模型 {}", systemConfigService.modelBaseUrl(),
                        systemConfigService.modelName());
            }
            return chatModel;
        }
    }

    public String modelName() {
        return systemConfigService.modelName();
    }

    /**
     * 按给定参数构建实例，供注册表与连接测试共用，保证测试走的就是业务实际调用的路径
     */
    public static OpenAiChatModel build(String baseUrl, String apiKey, String name,
                                        Double temperature, Integer timeoutSeconds) {
        int seconds = timeoutSeconds == null || timeoutSeconds < MIN_TIMEOUT_SECONDS
                ? MIN_TIMEOUT_SECONDS : timeoutSeconds;
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(seconds))
                .withReadTimeout(Duration.ofSeconds(seconds));
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings));
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl.trim())
                .apiKey(apiKey.trim())
                .restClientBuilder(builder)
                .build();
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().model(name.trim());
        if (temperature != null) {
            options.temperature(temperature);
        }
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options.build())
                .build();
    }

    private String signature() {
        return systemConfigService.modelBaseUrl() + "|" + systemConfigService.modelApiKey() + "|"
                + systemConfigService.modelName() + "|" + systemConfigService.modelTemperature() + "|"
                + systemConfigService.modelTimeoutSeconds();
    }
}

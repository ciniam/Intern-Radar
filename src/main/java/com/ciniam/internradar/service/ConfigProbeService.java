package com.ciniam.internradar.service;

import com.ciniam.internradar.adapter.git.GitDataSource;
import com.ciniam.internradar.adapter.zentao.ZentaoSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 配置页的连接测试：飞书应用凭证、飞书群机器人、内部任务系统登录、git 仓库
 * 探针只用传入的候选值，不读也不写生效配置，因此可以「先测再存」
 */
@Service
public class ConfigProbeService {

    private static final Logger log = LoggerFactory.getLogger(ConfigProbeService.class);

    private static final String FEISHU_BASE_URL = "https://open.feishu.cn/open-apis";
    private static final int SCAN_MAX_DEPTH = 4;
    private static final int SCAN_LIMIT = 200;

    private final GitDataSource gitDataSource;
    private final ZentaoSessionManager zentaoSessionManager;
    private final RestClient restClient;

    public ConfigProbeService(GitDataSource gitDataSource, ZentaoSessionManager zentaoSessionManager) {
        this.gitDataSource = gitDataSource;
        this.zentaoSessionManager = zentaoSessionManager;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(10);
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * 用应用凭证换一次 tenant_access_token，能换到就说明应用标识与密钥可用
     */
    public Map<String, Object> probeFeishuApp(String appId, String appSecret) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (isBlank(appId) || isBlank(appSecret)) {
            result.put("success", false);
            result.put("message", "未填写应用标识或应用密钥");
            return result;
        }
        try {
            Map<?, ?> response = restClient.post()
                    .uri(FEISHU_BASE_URL + "/auth/v3/app_access_token/internal")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("app_id", appId.trim(), "app_secret", appSecret.trim()))
                    .retrieve()
                    .body(Map.class);
            int code = number(response, "code", -1);
            boolean success = code == 0 && text(response, "tenant_access_token") != null;
            result.put("success", success);
            result.put("code", code);
            result.put("message", success ? "应用凭证可用，已成功换取租户令牌"
                    : "换取令牌失败：" + text(response, "msg"));
            return result;
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "请求飞书失败：" + e.getMessage());
            return result;
        }
    }

    /**
     * 往群机器人发一条测试消息，能发出去就说明 Webhook 可用
     */
    public Map<String, Object> probeFeishuWebhook(String webhookUrl, String receiver) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (isBlank(webhookUrl)) {
            result.put("success", false);
            result.put("message", "未填写群机器人 Webhook 地址");
            return result;
        }
        String target = isBlank(receiver) ? "群聊" : receiver;
        try {
            Map<?, ?> response = restClient.post()
                    .uri(webhookUrl.trim())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("msg_type", "text",
                            "content", Map.of("text", "实习雷达连接测试：这条消息用于验证群机器人 Webhook 是否可用，收到即配置成功。")))
                    .retrieve()
                    .body(Map.class);
            // 自定义机器人成功时返回 code=0，部分版本用 StatusCode 字段
            int code = number(response, "code", number(response, "StatusCode", -1));
            boolean success = code == 0;
            result.put("success", success);
            result.put("message", success ? "测试消息已发往" + target + "，请查看是否收到"
                    : "发送失败：" + text(response, "msg"));
            return result;
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "请求群机器人失败：" + e.getMessage());
            return result;
        }
    }

    public Map<String, Object> probeZentao(String baseUrl, String account, String password) {
        return zentaoSessionManager.probe(baseUrl, account, password);
    }

    /**
     * 发一条最小请求验证模型。走的是业务实际使用的构建方式，因此报错与真实调用一致
     */
    public Map<String, Object> probeModel(String baseUrl, String apiKey, String name,
                                          Double temperature, Integer timeoutSeconds) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (isBlank(baseUrl) || isBlank(apiKey) || isBlank(name)) {
            result.put("success", false);
            result.put("message", "未填写接口地址、密钥或模型名");
            return result;
        }
        try {
            int seconds = timeoutSeconds == null || timeoutSeconds <= 0 ? 30 : Math.min(timeoutSeconds, 30);
            OpenAiChatModel model = ModelRegistry.build(baseUrl, apiKey, name, temperature, seconds);
            String answer = model.call("连接测试，只回复 ok 即可");
            result.put("success", true);
            result.put("message", "模型可用，已返回响应" + (isBlank(answer) ? "" : "：" + answer.trim()));
            return result;
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", modelErrorMessage(e));
            return result;
        }
    }

    /**
     * 把底层异常摊平成可读结论，密钥与模型名是最常见的两类配置错误
     */
    private String modelErrorMessage(Throwable error) {
        StringBuilder builder = new StringBuilder();
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor.getMessage() != null && !cursor.getMessage().isBlank()) {
                builder.append(cursor.getMessage()).append(' ');
            }
            cursor = cursor.getCause();
        }
        String text = builder.toString().trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("401") || lower.contains("unauthorized") || lower.contains("invalid api key")
                || lower.contains("authentication")) {
            return "鉴权失败，密钥可能无效：" + text;
        }
        if (lower.contains("404") || lower.contains("model_not_found") || lower.contains("does not exist")) {
            return "地址或模型名可能不正确：" + text;
        }
        return "调用失败：" + text;
    }

    /**
     * 逐个校验仓库，任一不可用都会在结果里说明原因
     */
    public Map<String, Object> probeGitRepositories(List<String> repositories, List<String> authors) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> items = new ArrayList<>();
        boolean allOk = true;
        for (String repository : repositories == null ? List.<String>of() : repositories) {
            Map<String, Object> item = gitDataSource.probe(repository, authors);
            if (!Boolean.TRUE.equals(item.get("ok"))) {
                allOk = false;
            }
            items.add(item);
        }
        result.put("success", allOk && !items.isEmpty());
        result.put("items", items);
        result.put("message", items.isEmpty() ? "未配置任何仓库"
                : allOk ? "全部 " + items.size() + " 个仓库可用" : "存在不可用的仓库，见明细");
        return result;
    }

    /**
     * 扫描根目录下的 git 仓库，供配置页批量勾选。只读目录，不修改任何仓库
     */
    public List<String> scanRepositories(String rootDir) {
        List<String> found = new ArrayList<>();
        if (isBlank(rootDir)) {
            return found;
        }
        Path root = Paths.get(rootDir.trim());
        if (!Files.isDirectory(root)) {
            return found;
        }
        try (Stream<Path> stream = Files.walk(root, SCAN_MAX_DEPTH)) {
            stream.filter(Files::isDirectory)
                    .filter(path -> ".git".equals(path.getFileName().toString()))
                    .map(path -> path.getParent().toString().replace('\\', '/'))
                    .distinct()
                    .sorted()
                    .limit(SCAN_LIMIT)
                    .forEach(found::add);
        } catch (IOException e) {
            log.warn("扫描仓库根目录失败：{}", e.getMessage());
        }
        return found;
    }

    private int number(Map<?, ?> source, String key, int fallback) {
        if (source == null) {
            return fallback;
        }
        Object value = source.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String text(Map<?, ?> source, String key) {
        if (source == null) {
            return null;
        }
        Object value = source.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

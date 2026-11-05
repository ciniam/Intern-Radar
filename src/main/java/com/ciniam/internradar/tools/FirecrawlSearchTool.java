package com.ciniam.internradar.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 联网搜索工具，走 Firecrawl 的检索接口
 * 只在配置了密钥时才注册进来，未配置时模型看不到这个工具
 */
public class FirecrawlSearchTool {

    private static final Logger log = LoggerFactory.getLogger(FirecrawlSearchTool.class);

    private static final String SEARCH_PATH = "/search";
    private static final int SUMMARY_LIMIT = 300;

    private final String apiKey;
    private final String baseUrl;
    private final int limit;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public FirecrawlSearchTool(String apiKey, String baseUrl, int timeoutSeconds, int limit,
                               ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.limit = limit <= 0 ? 5 : limit;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(Math.max(timeoutSeconds, 5));
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Tool(description = "联网搜索公开资料，返回标题、链接与摘要。"
            + "只在团队知识库没有覆盖时使用，答案里要标注这是联网参考并给出链接")
    public String searchWeb(
            @ToolParam(description = "检索关键词，越具体越好，例如：RabbitMQ 延迟消息 实现方式")
            String query) {
        if (query == null || query.isBlank()) {
            return "请给出检索关键词。";
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", query.trim());
            body.put("limit", limit);
            body.put("sources", List.of("web"));
            Map<?, ?> response = restClient.post()
                    .uri(this.baseUrl + SEARCH_PATH)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            JsonNode root = objectMapper.valueToTree(response == null ? Map.of() : response);
            JsonNode results = root.path("data").path("web");
            if (!results.isArray() || results.isEmpty()) {
                return "联网检索没有返回结果，可以换更常见的说法再试一次。";
            }
            StringBuilder builder = new StringBuilder("联网检索结果：\n");
            for (JsonNode item : results) {
                builder.append("- ").append(item.path("title").asText("")).append('\n')
                        .append("  链接：").append(item.path("url").asText("")).append('\n')
                        .append("  摘要：").append(clip(item.path("description").asText(""))).append('\n');
            }
            builder.append("这些内容来自公开网络，不是团队知识库，引用时要注明来源链接。");
            return builder.toString();
        } catch (Exception e) {
            log.warn("联网检索失败，关键词 {}：{}", query, e.getMessage());
            return "联网检索失败：" + e.getMessage() + "。可以先用知识库里的内容作答，并说明联网检索未成功。";
        }
    }

    private String clip(String text) {
        String plain = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return plain.length() > SUMMARY_LIMIT ? plain.substring(0, SUMMARY_LIMIT) + "…" : plain;
    }
}

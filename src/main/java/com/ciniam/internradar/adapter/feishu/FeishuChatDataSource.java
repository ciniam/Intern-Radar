package com.ciniam.internradar.adapter.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ciniam.internradar.adapter.WorkDataSource;
import com.ciniam.internradar.config.InternRadarProperties;
import com.ciniam.internradar.entity.ChatCursorEntity;
import com.ciniam.internradar.feishu.ChatSummaryService;
import com.ciniam.internradar.feishu.FeishuClient;
import com.ciniam.internradar.feishu.FeishuUserAuthService;
import com.ciniam.internradar.mapper.ChatCursorMapper;
import com.ciniam.internradar.model.ChatSubscription;
import com.ciniam.internradar.model.ChatSummary;
import com.ciniam.internradar.model.WorkRecord;
import com.ciniam.internradar.service.ChatSubscriptionService;
import com.ciniam.internradar.model.WorkRecordType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 飞书聊天记录数据源。以用户身份读取本人参与的白名单会话
 * 原始消息只在内存中使用，落库的只有摘要，符合最小必要原则
 */
@Component
public class FeishuChatDataSource implements WorkDataSource {

    private static final Logger log = LoggerFactory.getLogger(FeishuChatDataSource.class);

    private static final String SOURCE_NAME = "feishu-chat";
    private static final long DEFAULT_TEAM_ID = 1L;
    private static final int PAGE_SIZE = 50;
    private static final int MAX_PAGES = 5;
    private static final int MAX_MESSAGE_CHARS = 200;
    /**
     * 单次摘要最多送入的消息条数，取最新的这些条
     */
    private static final int MAX_MESSAGES = 100;

    private final InternRadarProperties properties;
    private final ChatSubscriptionService subscriptionService;
    private final FeishuClient feishuClient;
    private final FeishuUserAuthService userAuthService;
    private final ChatSummaryService chatSummaryService;
    private final ChatCursorMapper chatCursorMapper;
    private final ObjectMapper objectMapper;

    public FeishuChatDataSource(InternRadarProperties properties,
                                ChatSubscriptionService subscriptionService,
                                FeishuClient feishuClient,
                                FeishuUserAuthService userAuthService,
                                ChatSummaryService chatSummaryService,
                                ChatCursorMapper chatCursorMapper,
                                ObjectMapper objectMapper) {
        this.properties = properties;
        this.subscriptionService = subscriptionService;
        this.feishuClient = feishuClient;
        this.userAuthService = userAuthService;
        this.chatSummaryService = chatSummaryService;
        this.chatCursorMapper = chatCursorMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getSourceName() {
        return SOURCE_NAME;
    }

    @Override
    public boolean healthCheck() {
        return feishuClient.appConfigured() && subscriptionService.anyConfigured();
    }

    /**
     * 会话内的机器人与用户单聊，应用身份即可读取，无需任何人授权
     */
    public boolean needsUserAuthorization(String identity) {
        return "USER".equalsIgnoreCase(identity);
    }

    /**
     * 排障用：按指定身份直接探一次会话，返回会话类型与消息读取结果
     * 用于判断某个 chat_id 是单聊还是群、以及哪种身份可读
     */
    public Map<String, Object> probe(String chatId, String identity, String userId, int days) {
        Map<String, Object> result = new LinkedHashMap<>();
        String token = null;
        if ("USER".equalsIgnoreCase(identity)) {
            token = userAuthService.accessToken(userId);
            if (token == null) {
                result.put("identity", "USER");
                result.put("error", "用户 " + userId + " 未授权，无法以用户身份调用");
                return result;
            }
            result.put("identity", "USER");
            result.put("grantedScope", userAuthService.grantedScope(userId));
        } else {
            result.put("identity", "APP");
        }
        try {
            Map<String, Object> info = feishuClient.get("/im/v1/chats/" + chatId, token);
            result.put("chatInfo", info.get("data"));
        } catch (Exception e) {
            result.put("chatInfoError", e.getMessage());
        }
        LocalDateTime end = LocalDate.now().plusDays(1).atStartOfDay();
        LocalDateTime start = LocalDate.now().minusDays(Math.max(0, days - 1L)).atStartOfDay();
        String path = "/im/v1/messages?container_id_type=chat&container_id=" + chatId
                + "&start_time=" + toEpochSecond(start)
                + "&end_time=" + toEpochSecond(end)
                + "&page_size=10&sort_type=ByCreateTimeAsc";
        result.put("window", start + " 至 " + end);
        try {
            Map<String, Object> response = feishuClient.get(path, token);
            JsonNode data = objectMapper.valueToTree(response.get("data"));
            result.put("messageCount", data.path("items").size());
            result.put("hasMore", data.path("has_more").asBoolean());
            List<Map<String, Object>> samples = new ArrayList<>();
            for (JsonNode item : data.path("items")) {
                Map<String, Object> sample = new LinkedHashMap<>();
                sample.put("msg_type", item.path("msg_type").asText());
                sample.put("chat_id", item.path("chat_id").asText());
                sample.put("createTime", toDateTime(item.path("create_time").asText(null)));
                sample.put("text", extractText(item));
                samples.add(sample);
            }
            result.put("samples", samples);
        } catch (Exception e) {
            result.put("messageError", e.getMessage());
        }
        return result;
    }

    @Override
    public List<WorkRecord> fetch(String userId, LocalDate date) {
        List<ChatSubscription> subscriptions = subscriptionService.forUser(userId);
        if (subscriptions.isEmpty()) {
            return List.of();
        }
        List<WorkRecord> records = new ArrayList<>();
        for (ChatSubscription subscription : subscriptions) {
            String token = null;
            if (subscription.userIdentity()) {
                token = userAuthService.accessToken(userId);
                if (token == null) {
                    log.warn("会话 {} 配置为用户身份，但用户 {} 尚未授权，跳过", subscription.name(), userId);
                    continue;
                }
            }
            try {
                WorkRecord record = collectChat(userId, token, subscription, date);
                if (record != null) {
                    records.add(record);
                }
            } catch (Exception e) {
                log.error("会话 {} 采集失败", subscription.name(), e);
            }
        }
        return records;
    }

    private WorkRecord collectChat(String userId, String token, ChatSubscription subscription,
                                   LocalDate date) {
        LocalDateTime start = startTime(userId, subscription.chatId(), date);
        LocalDateTime end = date.plusDays(1).atStartOfDay();
        List<String> texts = new ArrayList<>();
        LocalDateTime lastTime = start;
        String pageToken = null;
        // 倒序取最新的消息，达到上限即止，同一次请求内排序方式不可变
        for (int page = 0; page < MAX_PAGES && texts.size() < MAX_MESSAGES; page++) {
            String path = "/im/v1/messages?container_id_type=chat&container_id=" + subscription.chatId()
                    + "&start_time=" + toEpochSecond(start)
                    + "&end_time=" + toEpochSecond(end)
                    + "&page_size=" + PAGE_SIZE
                    + "&sort_type=ByCreateTimeDesc"
                    + (pageToken == null ? "" : "&page_token=" + pageToken);
            Map<String, Object> response = feishuClient.get(path, token);
            JsonNode data = objectMapper.valueToTree(response.get("data"));
            for (JsonNode item : data.path("items")) {
                if (texts.size() >= MAX_MESSAGES) {
                    break;
                }
                String text = extractText(item);
                if (text != null) {
                    texts.add(text);
                }
                lastTime = max(lastTime, toDateTime(item.path("create_time").asText(null)));
            }
            boolean hasMore = data.path("has_more").asBoolean(false);
            pageToken = data.path("page_token").asText(null);
            if (!hasMore || pageToken == null) {
                break;
            }
        }
        // 倒序取回的文本恢复成时间顺序，便于模型理解
        java.util.Collections.reverse(texts);
        if (texts.isEmpty()) {
            return null;
        }
        ChatSummary summary = chatSummaryService.summarize(userId, subscription.name(), texts);
        if (summary == null) {
            return null;
        }
        // 游标后移一秒，避免边界消息在下次采集时被重复读取
        saveCursor(userId, subscription.chatId(), lastTime.plusSeconds(1));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kind", "chat");
        metadata.put("chatId", subscription.chatId());
        metadata.put("chatName", subscription.name());
        metadata.put("messageCount", texts.size());
        String content = buildContent(summary);
        log.info("会话 {} 采集完成，消息 {} 条", subscription.name(), texts.size());
        return new WorkRecord(userId, lastTime.isAfter(start) ? lastTime : date.atTime(20, 0),
                WorkRecordType.MEETING, subscription.name() + " 沟通摘要", content, "done",
                "chat:" + subscription.chatId() + ":" + date, metadata);
    }

    private String buildContent(ChatSummary summary) {
        StringBuilder builder = new StringBuilder();
        builder.append(summary.summary() == null ? "" : summary.summary());
        if (summary.keyPoints() != null && !summary.keyPoints().isEmpty()) {
            builder.append("\n关键结论：").append(String.join("；", summary.keyPoints()));
        }
        if (summary.blockers() != null && !summary.blockers().isEmpty()) {
            builder.append("\n待确认：").append(String.join("；", summary.blockers()));
        }
        return builder.toString();
    }

    /**
     * 只保留文本消息，其余类型不参与摘要，避免把文件内容带进模型
     */
    private String extractText(JsonNode item) {
        if (!"text".equals(item.path("msg_type").asText(""))) {
            return null;
        }
        String body = item.path("body").path("content").asText("");
        try {
            JsonNode content = objectMapper.readTree(body);
            String text = content.path("text").asText("");
            if (text.isBlank()) {
                return null;
            }
            text = text.replace("\n", " ").trim();
            return text.length() > MAX_MESSAGE_CHARS ? text.substring(0, MAX_MESSAGE_CHARS) : text;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 起始时间：有游标从游标开始，首次接入则回补一段时间
     */
    private LocalDateTime startTime(String userId, String chatId, LocalDate date) {
        long lookback = Math.max(1L, properties.getFeishu().getChatLookbackDays());
        LocalDateTime floor = date.minusDays(lookback - 1L).atStartOfDay();
        ChatCursorEntity cursor = chatCursorMapper.selectOne(chatId, userId);
        if (cursor == null || cursor.getLastMessageTime() == null) {
            return floor;
        }
        return cursor.getLastMessageTime().isAfter(floor) ? cursor.getLastMessageTime() : floor;
    }

    private void saveCursor(String userId, String chatId, LocalDateTime lastTime) {
        chatCursorMapper.upsert(ChatCursorEntity.builder()
                .teamId(DEFAULT_TEAM_ID)
                .chatId(chatId)
                .userId(userId)
                .lastMessageTime(lastTime)
                .updatedAt(LocalDateTime.now())
                .build());
    }

    private long toEpochSecond(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toEpochSecond();
    }

    private LocalDateTime toDateTime(String millis) {
        if (millis == null || millis.isBlank()) {
            return LocalDateTime.now();
        }
        try {
            return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(Long.parseLong(millis)),
                    ZoneId.systemDefault());
        } catch (NumberFormatException e) {
            return LocalDateTime.now();
        }
    }

    private LocalDateTime max(LocalDateTime left, LocalDateTime right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isAfter(right) ? left : right;
    }
}

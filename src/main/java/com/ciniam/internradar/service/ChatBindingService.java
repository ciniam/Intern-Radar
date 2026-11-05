package com.ciniam.internradar.service;

import com.ciniam.internradar.adapter.feishu.FeishuChatDataSource;
import com.ciniam.internradar.entity.ChatBindingEntity;
import com.ciniam.internradar.mapper.ChatBindingMapper;
import com.ciniam.internradar.mapper.WorkRecordMapper;
import com.ciniam.internradar.model.ChatBindingView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天白名单管理。chat_id 只能从飞书客户端手工取得，绑定时先探测一次，避免把错的 id 配进来
 */
@Service
public class ChatBindingService {

    private static final Logger log = LoggerFactory.getLogger(ChatBindingService.class);

    private static final long DEFAULT_TEAM_ID = 1L;
    private static final int VERIFY_DAYS = 30;

    private final ChatBindingMapper chatBindingMapper;
    private final FeishuChatDataSource chatDataSource;
    private final WorkRecordMapper workRecordMapper;

    private final TeamScope teamScope;

    public ChatBindingService(ChatBindingMapper chatBindingMapper, FeishuChatDataSource chatDataSource,
                              WorkRecordMapper workRecordMapper, TeamScope teamScope) {
        this.chatBindingMapper = chatBindingMapper;
        this.chatDataSource = chatDataSource;
        this.workRecordMapper = workRecordMapper;
        this.teamScope = teamScope;
    }

    public Map<String, Object> bind(String userId, String chatId, String name, String identity) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("chatId", chatId);
        if (userId == null || userId.isBlank() || chatId == null || chatId.isBlank()) {
            result.put("bound", false);
            result.put("reason", "userId 与 chatId 都不能为空");
            return result;
        }
        String resolvedIdentity = identity == null || identity.isBlank() ? "USER" : identity.toUpperCase();
        Map<String, Object> probe = chatDataSource.probe(chatId, resolvedIdentity, userId, VERIFY_DAYS);
        String chatType = detectType(probe);
        String verifyMsg = firstError(probe);
        boolean verified = verifyMsg == null;
        chatBindingMapper.upsert(ChatBindingEntity.builder()
                .teamId(teamScope.of(userId))
                .userId(userId)
                .chatId(chatId)
                .name(name == null || name.isBlank() ? chatName(probe, chatId) : name)
                .chatType(chatType)
                .identity(resolvedIdentity)
                .enabled(true)
                .verified(verified)
                .verifyMsg(verifyMsg)
                .updatedAt(LocalDateTime.now())
                .build());
        result.put("bound", true);
        result.put("verified", verified);
        result.put("chatType", chatType);
        result.put("identity", resolvedIdentity);
        result.put("verifyMsg", verifyMsg);
        result.put("messageCount", probe.get("messageCount"));
        result.put("hint", verified ? "绑定成功，下一次采集会包含这个会话"
                : "已保存，但校验未通过。请确认身份、权限与会话 ID 是否正确");
        return result;
    }

    public List<Map<String, Object>> bindBatch(List<Map<String, String>> items) {
        List<Map<String, Object>> results = new ArrayList<>();
        if (items == null) {
            return results;
        }
        for (Map<String, String> item : items) {
            results.add(bind(item.get("userId"), item.get("chatId"), item.get("name"), item.get("identity")));
        }
        return results;
    }

    public List<ChatBindingEntity> list(String userId) {
        return userId == null || userId.isBlank()
                ? chatBindingMapper.selectEnabled()
                : chatBindingMapper.selectByUser(userId);
    }

    /**
     * 绑定列表带最近一次摘要，列表列与摘要抽屉共用这一份数据
     */
    public List<ChatBindingView> listWithSummary(String userId) {
        List<ChatBindingView> views = new ArrayList<>();
        for (ChatBindingEntity entity : list(userId)) {
            Map<String, Object> latest = workRecordMapper.selectLatestChatSummary(entity.getUserId(), entity.getChatId());
            views.add(new ChatBindingView(entity.getId(), entity.getUserId(), entity.getChatId(), entity.getName(),
                    entity.getChatType(), entity.getIdentity(), Boolean.TRUE.equals(entity.getVerified()),
                    entity.getVerifyMsg(), latest == null ? null : toTime(latest.get("occurredAt")),
                    latest == null ? null : (String) latest.get("content")));
        }
        return views;
    }

    private LocalDateTime toTime(Object value) {
        if (value instanceof LocalDateTime time) {
            return time;
        }
        if (value instanceof Timestamp stamp) {
            return stamp.toLocalDateTime();
        }
        return null;
    }

    public boolean unbind(Long id) {
        return chatBindingMapper.deleteById(id) > 0;
    }

    /**
     * 按主键取绑定记录，接口层用它判断这条绑定属不属于调用人
     */
    public ChatBindingEntity findById(Long id) {
        return chatBindingMapper.selectById(id);
    }

    public boolean unbind(String userId, String chatId) {
        return chatBindingMapper.deleteByChat(userId, chatId) > 0;
    }

    private String detectType(Map<String, Object> probe) {
        Object chatInfo = probe.get("chatInfo");
        if (chatInfo instanceof Map<?, ?> info) {
            String mode = String.valueOf(info.get("chat_mode"));
            if ("p2p".equalsIgnoreCase(mode)) {
                return "P2P";
            }
            if ("group".equalsIgnoreCase(mode) || "topic".equalsIgnoreCase(mode)) {
                return "GROUP";
            }
        }
        return "P2P";
    }

    private String chatName(Map<String, Object> probe, String fallback) {
        Object chatInfo = probe.get("chatInfo");
        if (chatInfo instanceof Map<?, ?> info) {
            String name = info.get("name") == null ? "" : String.valueOf(info.get("name"));
            if (!name.isBlank()) {
                return name;
            }
        }
        return "会话 " + fallback;
    }

    private String firstError(Map<String, Object> probe) {
        for (String key : List.of("error", "chatInfoError", "messageError")) {
            Object value = probe.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).length() > 480
                        ? String.valueOf(value).substring(0, 480) : String.valueOf(value);
            }
        }
        return null;
    }
}

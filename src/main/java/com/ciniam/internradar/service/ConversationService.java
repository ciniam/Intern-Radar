package com.ciniam.internradar.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ciniam.internradar.entity.LearnConversationEntity;
import com.ciniam.internradar.entity.LearnMessageEntity;
import com.ciniam.internradar.mapper.LearnConversationMapper;
import com.ciniam.internradar.mapper.LearnMessageMapper;
import com.ciniam.internradar.model.KnowledgeDocRef;
import com.ciniam.internradar.model.LearnConversation;
import com.ciniam.internradar.model.LearnConversationView;
import com.ciniam.internradar.model.LearnMessage;
import com.ciniam.internradar.security.ForbiddenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 学习问答会话的落库与读取。会话与消息都按用户隔离，跨用户访问直接拒绝
 * 每轮问答结束后写两条消息，作答时按条数取回最近几轮拼进提示词，从而支持追问
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    public static final String ROLE_USER = "USER";
    public static final String ROLE_ASSISTANT = "ASSISTANT";

    private static final int TITLE_LIMIT = 40;
    private static final int LIST_LIMIT = 50;
    /**
     * 拼进提示词的最近消息条数，一对问答算两条
     */
    private static final int HISTORY_LIMIT = 6;
    /**
     * 单条会话的消息上限，超出后从最早的消息开始清理
     */
    private static final int MAX_MESSAGES = 200;

    private final LearnConversationMapper conversationMapper;
    private final LearnMessageMapper messageMapper;
    private final ObjectMapper objectMapper;

    private final TeamScope teamScope;

    public ConversationService(LearnConversationMapper conversationMapper,
                               LearnMessageMapper messageMapper,
                               ObjectMapper objectMapper,
                               TeamScope teamScope) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.objectMapper = objectMapper;
        this.teamScope = teamScope;
    }

    /**
     * 定位本轮问答所属会话：传了且归属正确就复用，否则新建一条
     */
    public Long resolveConversation(String userId, Long conversationId, String question) {
        if (conversationId != null) {
            LearnConversationEntity existing = conversationMapper.selectById(conversationId);
            if (existing != null) {
                if (!userId.equals(existing.getUserId())) {
                    throw new ForbiddenException("无权访问该会话");
                }
                return existing.getId();
            }
        }
        LocalDateTime now = LocalDateTime.now();
        LearnConversationEntity created = LearnConversationEntity.builder()
                .teamId(teamScope.of(userId))
                .userId(userId)
                .title(titleOf(question))
                .createdAt(now)
                .updatedAt(now)
                .build();
        conversationMapper.insert(created);
        return created.getId();
    }

    public List<LearnConversation> list(String userId, String keyword) {
        String trimmed = keyword == null ? null : keyword.trim();
        List<LearnConversation> result = new ArrayList<>();
        for (LearnConversationEntity entity : conversationMapper.selectByUser(userId, trimmed, LIST_LIMIT)) {
            result.add(new LearnConversation(entity.getId(), entity.getTitle(),
                    messageMapper.countByConversation(entity.getId()), entity.getUpdatedAt()));
        }
        return result;
    }

    /**
     * 重命名。标题只做长度与空值校验，内容不做限制
     */
    public LearnConversationView rename(String userId, Long conversationId, String title) {
        require(userId, conversationId);
        String trimmed = title == null ? "" : title.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalStateException("标题不能为空");
        }
        conversationMapper.updateTitle(conversationId,
                trimmed.length() > TITLE_LIMIT ? trimmed.substring(0, TITLE_LIMIT) : trimmed,
                LocalDateTime.now());
        return history(userId, conversationId);
    }

    /**
     * 回退最后一轮：删掉最后一条答复与它对应的问题，把问题返回给调用方重发
     * 用于「重新生成」，避免在历史里留下两条同一问题的记录
     */
    public String rewind(String userId, Long conversationId) {
        require(userId, conversationId);
        List<LearnMessageEntity> tail = messageMapper.selectRecent(conversationId, 2);
        if (tail.isEmpty()) {
            throw new IllegalStateException("该会话还没有可重新生成的内容");
        }
        LearnMessageEntity last = tail.get(tail.size() - 1);
        if (!ROLE_ASSISTANT.equals(last.getRole())) {
            // 上一轮没有产出答复，直接重发这条问题
            return last.getContent();
        }
        messageMapper.deleteById(last.getId());
        LearnMessageEntity question = tail.size() == 2 && ROLE_USER.equals(tail.get(0).getRole())
                ? tail.get(0) : null;
        if (question == null) {
            throw new IllegalStateException("未找到可重新生成的问题");
        }
        messageMapper.deleteById(question.getId());
        return question.getContent();
    }

    public LearnConversationView history(String userId, Long conversationId) {
        LearnConversationEntity conversation = require(userId, conversationId);
        List<LearnMessage> messages = new ArrayList<>();
        for (LearnMessageEntity entity : messageMapper.selectByConversation(conversationId)) {
            messages.add(toMessage(entity));
        }
        return new LearnConversationView(conversation.getId(), conversation.getTitle(), messages);
    }

    public void remove(String userId, Long conversationId) {
        require(userId, conversationId);
        messageMapper.deleteByConversation(conversationId);
        conversationMapper.deleteById(conversationId);
    }

    /**
     * 作答前取最近几轮对话，用于把上文拼进提示词
     */
    public List<LearnMessage> recentMessages(Long conversationId) {
        List<LearnMessage> messages = new ArrayList<>();
        for (LearnMessageEntity entity : messageMapper.selectRecent(conversationId, HISTORY_LIMIT)) {
            messages.add(toMessage(entity));
        }
        return messages;
    }

    public void appendUser(Long conversationId, String userId, String content) {
        append(conversationId, userId, ROLE_USER, content, List.of(), 0, 0, null);
    }

    public void appendAssistant(Long conversationId, String userId, String content,
                                List<KnowledgeDocRef> references, int promptTokens, int completionTokens,
                                Long agentRunId) {
        if (content == null || content.isBlank()) {
            return;
        }
        append(conversationId, userId, ROLE_ASSISTANT, content, references, promptTokens, completionTokens,
                agentRunId);
    }

    private void append(Long conversationId, String userId, String role, String content,
                        List<KnowledgeDocRef> references, int promptTokens, int completionTokens,
                        Long agentRunId) {
        try {
            messageMapper.insert(LearnMessageEntity.builder()
                    .teamId(teamScope.of(userId))
                    .conversationId(conversationId)
                    .userId(userId)
                    .role(role)
                    .content(content)
                    .referenceDocs(writeReferences(references))
                    .promptTokens(promptTokens)
                    .completionTokens(completionTokens)
                    .agentRunId(agentRunId)
                    .createdAt(LocalDateTime.now())
                    .build());
            conversationMapper.touch(conversationId, LocalDateTime.now());
            messageMapper.deleteOldest(conversationId, MAX_MESSAGES);
        } catch (Exception e) {
            // 落库失败不影响本轮作答，只记日志
            log.warn("问答消息落库失败，会话 {}：{}", conversationId, e.getMessage());
        }
    }

    private LearnConversationEntity require(String userId, Long conversationId) {
        LearnConversationEntity entity = conversationId == null ? null : conversationMapper.selectById(conversationId);
        if (entity == null) {
            throw new IllegalStateException("会话不存在：" + conversationId);
        }
        if (!userId.equals(entity.getUserId())) {
            throw new ForbiddenException("无权访问该会话");
        }
        return entity;
    }

    private LearnMessage toMessage(LearnMessageEntity entity) {
        return new LearnMessage(entity.getId(), entity.getRole(), entity.getContent(),
                readReferences(entity.getReferenceDocs()),
                entity.getPromptTokens() == null ? 0 : entity.getPromptTokens(),
                entity.getCompletionTokens() == null ? 0 : entity.getCompletionTokens(),
                entity.getAgentRunId(),
                entity.getCreatedAt());
    }

    private String writeReferences(List<KnowledgeDocRef> references) {
        if (references == null || references.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(references);
        } catch (Exception e) {
            log.warn("引用文档序列化失败：{}", e.getMessage());
            return null;
        }
    }

    private List<KnowledgeDocRef> readReferences(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<KnowledgeDocRef>>() {
            });
        } catch (Exception e) {
            log.warn("引用文档解析失败：{}", e.getMessage());
            return List.of();
        }
    }

    private String titleOf(String question) {
        String text = question == null ? "" : question.trim().replaceAll("\\s+", " ");
        if (text.isEmpty()) {
            return "新的提问";
        }
        return text.length() > TITLE_LIMIT ? text.substring(0, TITLE_LIMIT) : text;
    }
}

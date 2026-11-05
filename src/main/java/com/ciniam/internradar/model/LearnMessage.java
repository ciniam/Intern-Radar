package com.ciniam.internradar.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话里的一条消息。引用文档只保留展示需要的字段
 */
public record LearnMessage(
        Long id,
        String role,
        String content,
        List<KnowledgeDocRef> references,
        int promptTokens,
        int completionTokens,
        Long agentRunId,
        LocalDateTime createdAt
) {
}

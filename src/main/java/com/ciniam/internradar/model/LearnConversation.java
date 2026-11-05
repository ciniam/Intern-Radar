package com.ciniam.internradar.model;

import java.time.LocalDateTime;

/**
 * 会话列表条目
 */
public record LearnConversation(
        Long id,
        String title,
        int messageCount,
        LocalDateTime updatedAt
) {
}

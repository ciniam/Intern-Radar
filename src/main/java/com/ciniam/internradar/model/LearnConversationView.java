package com.ciniam.internradar.model;

import java.util.List;

/**
 * 会话详情：标题与全部消息
 */
public record LearnConversationView(
        Long id,
        String title,
        List<LearnMessage> messages
) {
}

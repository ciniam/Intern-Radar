package com.ciniam.internradar.model;

import java.time.LocalDateTime;

/**
 * 会话绑定列表项。最近摘要取该会话最新一条聊天记录，没有则为空
 */
public record ChatBindingView(
        Long id,
        String userId,
        String chatId,
        String name,
        String chatType,
        String identity,
        boolean verified,
        String verifyMsg,
        LocalDateTime lastSummaryAt,
        String lastSummary
) {
}

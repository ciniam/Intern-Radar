package com.ciniam.internradar.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一次智能体运行。列表里 steps 为空，详情里带上全部步骤
 */
public record AgentRunView(
        Long id,
        String userId,
        String scene,
        String title,
        String status,
        int stepCount,
        int promptTokens,
        int completionTokens,
        long durationMs,
        String errorMsg,
        LocalDateTime createdAt,
        List<AgentStepView> steps
) {
}

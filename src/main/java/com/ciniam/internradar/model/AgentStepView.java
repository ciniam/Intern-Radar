package com.ciniam.internradar.model;

import java.time.LocalDateTime;

/**
 * 一条步骤轨迹。工具名为空表示这一步没有调用工具，是模型的最终产出
 */
public record AgentStepView(
        Long id,
        int stepNo,
        String thought,
        String toolName,
        String toolArgs,
        String toolResult,
        long durationMs,
        LocalDateTime createdAt
) {
}

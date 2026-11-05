package com.ciniam.internradar.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 通用报告视图，内容不做结构化解析，日报、今日计划与周报共用
 */
public record ReportDetail(
        Long id,
        String userId,
        String type,
        LocalDate periodStart,
        LocalDate periodEnd,
        JsonNode content,
        Integer tokensUsed,
        Long agentRunId,
        LocalDateTime generatedAt,
        String status
) {
}

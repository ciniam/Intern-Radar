package com.ciniam.internradar.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 团队总览，供导师看板使用
 * alertByLevel 统计未处理告警按等级的条数
 */
public record MentorOverview(
        int windowDays,
        int internCount,
        int recordCount,
        int openAlertCount,
        int openTaskCount,
        Map<String, Integer> alertByLevel,
        LocalDateTime generatedAt,
        List<InternStatus> interns
) {
}

package com.ciniam.internradar.model;

import java.time.LocalDateTime;

/**
 * 团队成材料目。产出与活跃按近七天统计，且不含聊天采集的摘要
 */
public record TeamMember(
        String userId,
        String name,
        String role,
        boolean pending,
        String email,
        int recordCount,
        int openTaskCount,
        LocalDateTime lastActiveAt
) {
}

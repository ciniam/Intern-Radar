package com.ciniam.internradar.model;

import java.time.LocalDateTime;

/**
 * 团队信息。邀请码只回传给团队创建人与管理员
 * joinStatus 是未归属用户最近一次申请的状态，已归属时为 null
 */
public record TeamInfo(
        Long id,
        String name,
        String inviteCode,
        String description,
        String myRole,
        boolean owner,
        int memberCount,
        int pendingCount,
        String joinStatus,
        LocalDateTime createdAt
) {
}

package com.ciniam.internradar.model;

import java.time.LocalDateTime;

/**
 * 一条加入团队申请，供团队创建人在界面上审批
 */
public record TeamJoinRequest(
        Long id,
        Long teamId,
        String teamName,
        String userId,
        String userName,
        String status,
        String message,
        String role,
        LocalDateTime createdAt
) {
}

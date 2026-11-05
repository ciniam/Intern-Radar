package com.ciniam.internradar.model;

import java.time.LocalDateTime;

/**
 * 一条待确认的配置变更提案。确认前不会写进用户偏好
 */
public record ConfigProposal(
        Long id,
        String fieldKey,
        String fieldLabel,
        String fieldValue,
        String reason,
        String status,
        LocalDateTime createdAt,
        LocalDateTime handledAt
) {
}

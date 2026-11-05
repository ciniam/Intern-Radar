package com.ciniam.internradar.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * config_proposal 表映射对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfigProposalEntity {

    private Long id;
    private Long teamId;
    private String userId;
    private Long conversationId;
    private String fieldKey;
    private String fieldLabel;
    private String fieldValue;
    private String reason;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime handledAt;
}

package com.ciniam.internradar.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskAlertEntity {

    private Long id;
    private Long teamId;
    private String userId;
    private String level;
    private String type;
    private String title;
    private String fingerprint;
    private String evidence;
    private String suggestion;
    private String status;
    private Long agentRunId;
    private LocalDateTime createdAt;
    private LocalDateTime notifiedAt;
}

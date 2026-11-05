package com.ciniam.internradar.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * agent_run 表映射对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRunEntity {

    private Long id;
    private Long teamId;
    private String userId;
    private String scene;
    private String title;
    private String status;
    private Integer stepCount;
    private Integer promptTokens;
    private Integer completionTokens;
    private Long durationMs;
    private String errorMsg;
    private LocalDateTime createdAt;
}

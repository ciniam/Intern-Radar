package com.ciniam.internradar.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * agent_step 表映射对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentStepEntity {

    private Long id;
    private Long teamId;
    private Long runId;
    private Integer stepNo;
    private String thought;
    private String toolName;
    private String toolArgs;
    private String toolResult;
    private Long durationMs;
    private LocalDateTime createdAt;
}

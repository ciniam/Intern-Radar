package com.ciniam.internradar.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * report 表映射对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportEntity {

    private Long id;
    private Long teamId;
    private String userId;
    private String type;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private String contentJson;
    private Integer tokensUsed;
    private BigDecimal cost;
    private String status;
    private Long agentRunId;
    private LocalDateTime generatedAt;
}

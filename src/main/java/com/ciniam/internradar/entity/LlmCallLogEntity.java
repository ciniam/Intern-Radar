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
public class LlmCallLogEntity {

    private Long id;
    private Long teamId;
    private String userId;
    private String scene;
    private String model;
    private Integer promptTokens;
    private Integer completionTokens;
    private Long durationMs;
    private Boolean success;
    private String errorMsg;
    private LocalDateTime createdAt;
}

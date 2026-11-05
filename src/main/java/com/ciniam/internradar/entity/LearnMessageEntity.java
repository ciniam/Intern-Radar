package com.ciniam.internradar.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * learn_message 表映射对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LearnMessageEntity {

    private Long id;
    private Long teamId;
    private Long conversationId;
    private String userId;
    private String role;
    private String content;
    private String referenceDocs;
    private Integer promptTokens;
    private Integer completionTokens;
    private Long agentRunId;
    private LocalDateTime createdAt;
}

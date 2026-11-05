package com.ciniam.internradar.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * learn_conversation 表映射对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LearnConversationEntity {

    private Long id;
    private Long teamId;
    private String userId;
    private String title;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

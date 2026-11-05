package com.ciniam.internradar.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * work_record 表映射对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkRecordEntity {

    private Long id;
    private Long teamId;
    private String userId;
    private String source;
    private String type;
    private String title;
    private String content;
    private String status;
    private LocalDateTime occurredAt;
    private String externalId;
    private String metadata;
    private LocalDateTime createdAt;
}

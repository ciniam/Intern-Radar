package com.ciniam.internradar.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * doc_retrieve_log 表映射对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocRetrieveLogEntity {

    private Long id;
    private Long teamId;
    private String userId;
    private String scene;
    private String queryText;
    private Integer hitCount;
    private Integer readCount;
    private Integer readChars;
    private Long durationMs;
    private Boolean success;
    private String errorMsg;
    private LocalDateTime createdAt;
}

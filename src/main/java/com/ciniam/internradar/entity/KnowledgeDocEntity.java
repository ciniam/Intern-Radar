package com.ciniam.internradar.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * knowledge_doc 表映射对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocEntity {

    private Long id;
    private Long teamId;
    private String title;
    private String category;
    private String tags;
    private String source;
    private String visibility;
    private String contentRef;
    private String summary;
    private Boolean enabled;
    private String uploaderUserId;
    private LocalDateTime uploadedAt;
    private LocalDateTime updatedAt;
}

package com.ciniam.internradar.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识文档，正文在文件中，这里只承载元数据
 */
public record KnowledgeDoc(
        Long id,
        String title,
        String category,
        List<String> tags,
        String source,
        String visibility,
        String contentRef,
        String summary,
        String uploaderUserId,
        String uploaderName,
        LocalDateTime uploadedAt,
        LocalDateTime updatedAt
) {
}

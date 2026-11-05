package com.ciniam.internradar.model;

/**
 * 消息里引用的知识文档，只存展示需要的字段
 */
public record KnowledgeDocRef(
        Long id,
        String title,
        String category
) {
}

package com.ciniam.internradar.model;

import java.util.List;

/**
 * 学习资源推荐结果，clues 是触发本次推荐的任务线索
 * fallback 为真表示线索没有精确匹配的文档，返回的是全部文档目录
 */
public record LearnRecommend(
        String userId,
        List<String> clues,
        boolean fallback,
        List<KnowledgeDoc> docs,
        List<Pitfall> pitfalls
) {
}

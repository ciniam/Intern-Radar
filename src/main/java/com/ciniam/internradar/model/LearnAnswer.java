package com.ciniam.internradar.model;

import java.util.List;

/**
 * 学习问答结果。references 是智能体真正读取过的文档，matched 是检索命中的全部文档
 */
public record LearnAnswer(
        Long conversationId,
        String answer,
        List<KnowledgeDoc> references,
        List<KnowledgeDoc> matched,
        int searchCalls,
        int promptTokens,
        int completionTokens,
        Long agentRunId,
        long durationMs
) {
}

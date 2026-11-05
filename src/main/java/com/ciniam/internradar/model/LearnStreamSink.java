package com.ciniam.internradar.model;

import java.util.List;

/**
 * 流式作答的事件出口。检索阶段先给引用，再逐字给答复，最后给用量
 */
public interface LearnStreamSink {

    /**
     * 本轮问答所属会话，检索开始前先下发，前端据此记住会话
     */
    void onConversation(Long conversationId);

    /**
     * 本轮问答的执行轨迹标识，用于事后查看完整步骤
     */
    default void onRun(Long runId) {
    }

    /**
     * 检索阶段结束，引用清单已确定
     */
    void onReferences(List<KnowledgeDoc> references, List<KnowledgeDoc> matchedDocs, int searchCalls, int readCount);

    /**
     * 答复增量文本
     */
    void onDelta(String text);

    void onDone(int promptTokens, int completionTokens, long elapsedMs);

    void onError(String message);
}

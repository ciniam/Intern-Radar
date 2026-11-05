package com.ciniam.internradar.agent;

/**
 * 单步轨迹出口。代理不认识库表，只把每一步交出去，由业务层决定落库还是丢弃
 */
public interface AgentStepListener {

    /**
     * 一步一次模型调用，一步内调用了多个工具会回调多次，thought 相同
     */
    void onStep(int stepNo, String thought, String toolName, String toolArgs, String toolResult, long durationMs);
}

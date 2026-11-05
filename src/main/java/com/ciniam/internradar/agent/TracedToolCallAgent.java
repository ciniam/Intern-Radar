package com.ciniam.internradar.agent;

import cn.hutool.core.util.StrUtil;
import com.ciniam.core.agent.ToolCallAgent;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可记录轨迹的工具调用代理。在框架的单步执行外再包一层，抓出每步的思考、工具、入参与返回
 * 每步的用量也在这里累计，子类不必各自处理
 */
public class TracedToolCallAgent extends ToolCallAgent {

    private AgentStepListener stepListener;
    private int executedSteps;
    private ChatResponse countedResponse;
    private int promptTokens;
    private int completionTokens;

    public TracedToolCallAgent(ToolCallback[] tools) {
        super(tools);
    }

    public void setStepListener(AgentStepListener listener) {
        this.stepListener = listener;
    }

    @Override
    public String step() {
        long begin = System.currentTimeMillis();
        String result = super.step();
        int stepNo = ++executedSteps;
        readUsage();
        emit(stepNo, System.currentTimeMillis() - begin);
        return result;
    }

    /**
     * 消息上下文里最后一条有内容的助手消息，即模型的最终产出
     */
    protected String lastAssistantText() {
        List<Message> messages = getMessageList();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AssistantMessage assistant && StrUtil.isNotBlank(assistant.getText())) {
                return assistant.getText();
            }
        }
        return "";
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public int getCompletionTokens() {
        return completionTokens;
    }

    public int getTotalTokens() {
        return promptTokens + completionTokens;
    }

    public int getExecutedSteps() {
        return executedSteps;
    }

    private void emit(int stepNo, long cost) {
        AgentStepListener listener = this.stepListener;
        if (listener == null) {
            return;
        }
        String thought = getLastThinkResult();
        List<AssistantMessage.ToolCall> calls = toolCalls();
        if (calls.isEmpty()) {
            listener.onStep(stepNo, thought, null, null, null, cost);
            return;
        }
        Map<String, String> results = toolResults();
        for (AssistantMessage.ToolCall call : calls) {
            listener.onStep(stepNo, thought, call.name(), call.arguments(), results.get(call.name()), cost);
        }
    }

    private List<AssistantMessage.ToolCall> toolCalls() {
        ChatResponse response = getToolCallChatResponse();
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return List.of();
        }
        List<AssistantMessage.ToolCall> calls = response.getResult().getOutput().getToolCalls();
        return calls == null ? List.of() : calls;
    }

    /**
     * 工具返回结果按工具名归集，一步里同名工具被调用多次时按先后拼接
     */
    private Map<String, String> toolResults() {
        Map<String, String> results = new LinkedHashMap<>();
        List<Message> messages = getMessageList();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof ToolResponseMessage toolMessage) {
                for (ToolResponseMessage.ToolResponse response : toolMessage.getResponses()) {
                    String data = response.responseData() == null ? "" : response.responseData();
                    results.merge(response.name(), data, (left, right) -> left + "\n" + right);
                }
                break;
            }
        }
        return results;
    }

    /**
     * 流式与非流式都会产生用量，只取有新响应时的那一次，避免同一步重复累加
     */
    private void readUsage() {
        ChatResponse response = getToolCallChatResponse();
        if (response == null || response == countedResponse) {
            return;
        }
        countedResponse = response;
        ChatResponseMetadata metadata = response.getMetadata();
        Usage usage = metadata == null ? null : metadata.getUsage();
        if (usage == null) {
            return;
        }
        this.promptTokens += usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        this.completionTokens += usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
    }
}

package com.ciniam.internradar.agent;

import com.ciniam.internradar.util.JsonTextRepair;
import com.ciniam.core.agent.BaseAgent;
import com.ciniam.core.agent.model.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;

/**
 * 无工具的结构化输出智能体，材料来自调用方给出的提示词
 * 单步完成一次模型调用，模型偶尔会漏写数组闭合符号，因此解析先做结构修复，仍失败再带提示重试一次
 */
public class StructuredAgent<T> extends BaseAgent {

    private static final Logger log = LoggerFactory.getLogger(StructuredAgent.class);

    private static final String RETRY_SUFFIX = """

            上一次输出不是合法 JSON，解析失败。请只输出一个完整的 JSON 对象，
            所有数组与对象必须用正确的括号闭合，不要输出任何解释文字或代码块标记。
            """;

    private final ChatClient chatClient;
    private final BeanOutputConverter<T> converter;
    private final Class<T> outputType;
    private String userPrompt;
    private T output;
    private Exception failure;
    private int promptTokens;
    private int completionTokens;

    public StructuredAgent(ChatModel chatModel, String agentName, String systemPrompt, Class<T> outputType) {
        setName(agentName);
        setSystemPrompt(systemPrompt);
        setMaxSteps(1);
        this.outputType = outputType;
        this.chatClient = ChatClient.builder(chatModel).build();
        this.converter = new BeanOutputConverter<>(outputType);
    }

    public T generate(String prompt) {
        this.userPrompt = prompt;
        run(prompt);
        if (output == null) {
            throw new IllegalStateException("模型未返回可用的 " + outputType.getSimpleName(),
                    failure == null ? null : failure);
        }
        return output;
    }

    @Override
    public String step() {
        setState(AgentState.FINISHED);
        try {
            ChatResponse response = call(userPrompt);
            String text = response.getResult().getOutput().getText();
            readUsage(response);
            try {
                this.output = parse(text);
            } catch (Exception parseFailure) {
                log.warn("结构化输出解析失败，将带提示重试一次。目标类型 {}，原因：{}",
                        outputType.getSimpleName(), parseFailure.getMessage());
                ChatResponse retry = call(userPrompt + RETRY_SUFFIX);
                String retryText = retry.getResult().getOutput().getText();
                readUsage(retry);
                this.output = parse(retryText);
                text = retryText;
            }
            return text;
        } catch (Exception e) {
            this.failure = e;
            throw e;
        }
    }

    private ChatResponse call(String prompt) {
        return chatClient.prompt()
                .system(getSystemPrompt() + "\n" + converter.getFormat())
                .user(prompt)
                .call()
                .chatResponse();
    }

    /**
     * 先按原样解析，失败则修补结构后再试
     */
    private T parse(String text) {
        try {
            return converter.convert(text);
        } catch (Exception e) {
            T repaired = converter.convert(JsonTextRepair.repair(text));
            log.info("结构化输出经修复后解析成功，目标类型 {}", outputType.getSimpleName());
            return repaired;
        }
    }

    private void readUsage(ChatResponse response) {
        ChatResponseMetadata metadata = response.getMetadata();
        Usage usage = metadata == null ? null : metadata.getUsage();
        if (usage == null) {
            return;
        }
        this.promptTokens += usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        this.completionTokens += usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
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
}

package com.ciniam.internradar.agent;

import com.ciniam.internradar.util.JsonTextRepair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.tool.ToolCallback;

/**
 * 报告生成智能体。材料由模型自己用工具取，不再由代码预先拼进提示词
 * 模型偶尔会漏写数组闭合符号，因此解析先做结构修复，仍失败由调用方带提示重试一次
 */
public class ReportAgent<T> extends TracedToolCallAgent {

    private static final Logger log = LoggerFactory.getLogger(ReportAgent.class);

    /**
     * 重试时追加的提示，说明上一次输出了什么格式问题
     */
    public static final String RETRY_SUFFIX = """

            上一次输出不是合法 JSON，解析失败。请只输出一个完整的 JSON 对象，
            所有数组与对象必须用正确的括号闭合，不要输出任何解释文字或代码块标记。
            """;

    private static final String TOOL_GUIDE = """

            材料获取规则：
            1. 先用工具查询真实数据，只依据工具返回的内容下结论，不要凭记忆编造；
            2. 需要的材料齐了就停止调用工具；
            3. 最后一条回答必须是符合下面格式要求的 JSON，不要写解释文字。
            """;

    private static final String NEXT_STEP_PROMPT = """
            先判断材料是否已经齐全：不够就继续调用工具查询，齐全就直接输出最终 JSON，不要再调用工具。
            """;

    private final BeanOutputConverter<T> converter;
    private final Class<T> outputType;

    public ReportAgent(ToolCallback[] tools, ChatModel chatModel, String agentName,
                       String systemPrompt, Class<T> outputType) {
        super(tools);
        setName(agentName);
        this.outputType = outputType;
        this.converter = new BeanOutputConverter<>(outputType);
        setSystemPrompt(systemPrompt + TOOL_GUIDE + converter.getFormat());
        setNextStepPrompt(NEXT_STEP_PROMPT);
        setMaxSteps(6);
        setChatClient(ChatClient.builder(chatModel).build());
    }

    public T generate(String prompt) {
        run(prompt);
        T parsed = parse(lastAssistantText());
        if (parsed == null) {
            throw new IllegalStateException("模型未返回可用的 " + outputType.getSimpleName());
        }
        return parsed;
    }

    /**
     * 先按原样解析，失败则修补结构后再试，两处都失败返回 null
     */
    private T parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return converter.convert(text);
        } catch (Exception e) {
            log.warn("结构化输出初次解析失败，目标类型 {}：{}", outputType.getSimpleName(), e.getMessage());
        }
        try {
            T repaired = converter.convert(JsonTextRepair.repair(text));
            log.info("结构化输出经修复后解析成功，目标类型 {}", outputType.getSimpleName());
            return repaired;
        } catch (Exception e) {
            log.warn("结构化输出修复后仍失败，目标类型 {}：{}", outputType.getSimpleName(), e.getMessage());
            return null;
        }
    }
}

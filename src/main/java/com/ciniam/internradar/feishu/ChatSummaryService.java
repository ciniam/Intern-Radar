package com.ciniam.internradar.feishu;

import com.ciniam.internradar.agent.StructuredAgent;
import com.ciniam.internradar.model.ChatSummary;
import com.ciniam.internradar.service.ModelRegistry;
import com.ciniam.internradar.service.ObserveService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 聊天记录摘要。原始消息只用于本次生成，不写入数据库
 */
@Service
public class ChatSummaryService {

    private static final Logger log = LoggerFactory.getLogger(ChatSummaryService.class);

    private static final String SCENE = "CHAT_SUMMARY";

    private static final String SYSTEM_PROMPT = """
            你是团队沟通记录归纳助手。输入是某会话一段时间的消息文本。
            规则：
            1. 只归纳与工作推进相关的内容，忽略闲聊与表情；
            2. summary 用两三句话概括这段沟通的核心信息；
            3. keyPoints 列出明确的工作结论或进展；
            4. blockers 列出提到的阻塞与待确认事项，没有就返回空数组；
            5. 不要输出任何原始消息全文，也不要包含人名之外的个人信息。
            """;

    private final ModelRegistry modelRegistry;
    private final ObserveService observeService;

    public ChatSummaryService(ModelRegistry modelRegistry, ObserveService observeService) {
        this.modelRegistry = modelRegistry;
        this.observeService = observeService;
    }

    public ChatSummary summarize(String userId, String chatName, List<String> messages) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("会话名称：").append(chatName).append('\n');
        prompt.append("消息条数：").append(messages.size()).append('\n');
        prompt.append("消息内容：").append('\n');
        for (String message : messages) {
            prompt.append("- ").append(message).append('\n');
        }
        StructuredAgent<ChatSummary> agent = new StructuredAgent<>(modelRegistry.chatModel(),
                "chatSummaryAgent", SYSTEM_PROMPT, ChatSummary.class);
        long begin = System.currentTimeMillis();
        try {
            ChatSummary summary = agent.generate(prompt.toString());
            observeService.recordLlm(userId, SCENE, modelRegistry.modelName(), agent.getPromptTokens(),
                    agent.getCompletionTokens(), System.currentTimeMillis() - begin, true, null);
            return summary;
        } catch (Exception e) {
            observeService.recordLlm(userId, SCENE, modelRegistry.modelName(), 0, 0,
                    System.currentTimeMillis() - begin, false, e.getMessage());
            log.error("聊天摘要生成失败，会话 {}", chatName, e);
            return null;
        }
    }
}

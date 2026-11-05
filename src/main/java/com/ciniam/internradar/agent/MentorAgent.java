package com.ciniam.internradar.agent;

import cn.hutool.core.util.StrUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.Locale;

/**
 * 学习助手智能体。检索与读取均由模型自主发起，检索过程可见，便于评测命中率
 */
public class MentorAgent extends TracedToolCallAgent {

    private static final Logger log = LoggerFactory.getLogger(MentorAgent.class);

    private static final String SYSTEM_PROMPT = """
            你是实习生学习助手，负责回答实习过程中的技术问题与系统使用问题。
            规则：
            1. 团队知识库能回答的，先检索再读取最相关的文档，不要在没读文档的情况下作答；
            2. 回答要基于读到的内容，说明依据来自哪几篇文档；
            3. 知识库没有覆盖、但联网检索能补上的，要标注这是联网参考并给出链接；
            4. 涉及实习雷达本身怎么用时，按使用说明直接给出可执行步骤；
            5. 不要编造事实与链接，用中文分点作答。
            """;

    private static final String NEXT_STEP_PROMPT = """
            根据问题决定下一步：信息不够就继续检索、读取文档或联网搜索，信息足够就直接给出最终回答。
            给出最终回答时不要再调用工具。
            """;

    private static final String RESEARCH_PROMPT = """
            你是实习生学习助手的资料检索员，负责为后续作答准备材料。
            规则：
            1. 先判断问题能不能用团队知识库回答：能就先检索，再读取最相关的文档，至少读完一篇；
            2. 问题涉及公开技术资料、知识库明显没有覆盖时，可以用联网搜索补充，并记下来源链接；
            3. 问题关于实习雷达本身怎么用、有哪些页面与配置项时，优先查知识库里的使用说明；
            4. 用户要求你帮他配置仓库、作者标识、推送时间等个人配置时，用 proposeConfigChange 生成待确认提案，
               并明确告诉用户提案需要他确认后才会生效，绝不要声称配置已经改好；
            5. 只汇报找到的依据与事实，不要写最终答复；
            6. 知识库与联网都没有覆盖的内容要明确指出。
            """;

    private static final String RESEARCH_STEP_PROMPT = """
            信息不够就继续检索、读取文档或联网搜索；材料够了就停下，简短列出找到的依据
            （知识库用文档编号、联网给出链接），不要再调用工具。
            """;

    public MentorAgent(ToolCallback[] tools, ChatModel chatModel) {
        this(tools, chatModel, null);
    }

    public MentorAgent(ToolCallback[] tools, ChatModel chatModel, String answerStyle) {
        super(tools);
        setName("mentorAgent");
        setSystemPrompt(SYSTEM_PROMPT + styleHint(answerStyle));
        setNextStepPrompt(NEXT_STEP_PROMPT);
        setMaxSteps(6);
        setChatClient(ChatClient.builder(chatModel).build());
    }

    /**
     * 只做检索与阅读的实例。流式作答分两段，第一段用它拿到依据，第二段再逐字生成答复
     */
    public static MentorAgent forResearch(ToolCallback[] tools, ChatModel chatModel) {
        MentorAgent agent = new MentorAgent(tools, chatModel, null);
        agent.setSystemPrompt(RESEARCH_PROMPT);
        agent.setNextStepPrompt(RESEARCH_STEP_PROMPT);
        return agent;
    }

    /**
     * 检索阶段的资料要点，即最后一条有内容的助手消息
     */
    public String notes() {
        return lastAssistantText();
    }

    /**
     * 作答详略程度来自用户偏好
     */
    public static String styleHint(String answerStyle) {
        if (answerStyle == null || answerStyle.isBlank()) {
            return "";
        }
        return switch (answerStyle.trim().toUpperCase(Locale.ROOT)) {
            case "CONCISE" -> "\n补充要求：作答要精简，只给结论与关键步骤，不展开原理。\n";
            case "THOROUGH" -> "\n补充要求：作答要详尽，补充原理与常见变体，并指出容易踩错的地方。\n";
            default -> "";
        };
    }

    /**
     * 提问并返回最终回答，取消息上下文里最后一条有内容的助手消息
     */
    public String ask(String question) {
        run(question);
        String answer = lastAssistantText();
        if (StrUtil.isBlank(answer)) {
            log.warn("{}未产出有效回答，最终状态 {}", getName(), getState());
            return "";
        }
        return answer;
    }
}

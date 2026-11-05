package com.ciniam.internradar.service;

import cn.hutool.core.util.StrUtil;
import com.ciniam.internradar.agent.MentorAgent;
import com.ciniam.internradar.entity.WorkRecordEntity;
import com.ciniam.internradar.model.KnowledgeDoc;
import com.ciniam.internradar.model.KnowledgeDocRef;
import com.ciniam.internradar.model.LearnAnswer;
import com.ciniam.internradar.model.LearnMessage;
import com.ciniam.internradar.model.LearnRecommend;
import com.ciniam.internradar.model.LearnStreamSink;
import com.ciniam.internradar.model.Pitfall;
import com.ciniam.internradar.model.UserPreference;
import com.ciniam.internradar.tools.DocumentReferenceRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 学习赋能编排：问答走智能体自主检索，资源推荐走确定性检索
 * 两条路径都会记录检索埋点，命中率可直接从埋点统计
 */
@Service
public class LearnService {

    private static final Logger log = LoggerFactory.getLogger(LearnService.class);

    public static final String SCENE_ASK = "LEARN_ASK";
    public static final String SCENE_RECOMMEND = "LEARN_RECOMMEND";

    private static final int RECOMMEND_LOOKBACK_DAYS = 7;
    private static final int RECOMMEND_LIMIT = 5;
    private static final int QUERY_TEXT_LIMIT = 480;
    private static final Set<String> CLOSED_STATUS = Set.of("done", "closed", "cancel", "resolved");

    private static final int MATERIAL_CHAR_LIMIT = 9000;
    private static final int MATERIAL_PER_DOC_LIMIT = 4000;
    /**
     * 拼接历史时每条消息的上限，避免长答复把上下文吃满
     */
    private static final int HISTORY_CHAR_LIMIT = 400;

    private static final String ANSWER_SYSTEM_PROMPT = """
            你是实习生学习助手，负责回答实习过程中的技术问题与系统使用问题。
            规则：
            1. 优先依据给定资料作答，引用知识库内容时用文档编号标注；
            2. 资料里没有依据时先明确说明「知识库未覆盖」，再给出资料中来自联网检索的参考内容并附链接，
               两者要分开说，不要把联网内容说成团队结论；
            3. 问题关于实习雷达本身怎么用（有哪些页面、怎么配置、常见操作）时，直接给出可执行步骤，
               必要时指出对应的页面名称；
            4. 涉及修改配置时只说明操作路径与自己能代办的边界，配置真正生效需要用户在提案上确认；
            5. 不要编造事实、数字与链接；
            6. 用中文分点作答，步骤要具体可执行。
            """;

    private final KnowledgeService knowledgeService;
    private final ReportService reportService;
    private final ObserveService observeService;
    private final PreferenceService preferenceService;
    private final ModelRegistry modelRegistry;
    private final ConversationService conversationService;
    private final ToolRegistry toolRegistry;
    private final AgentTraceService agentTraceService;

    public LearnService(KnowledgeService knowledgeService,
                        ReportService reportService,
                        ObserveService observeService,
                        PreferenceService preferenceService,
                        ModelRegistry modelRegistry,
                        ConversationService conversationService,
                        ToolRegistry toolRegistry,
                        AgentTraceService agentTraceService) {
        this.knowledgeService = knowledgeService;
        this.reportService = reportService;
        this.observeService = observeService;
        this.preferenceService = preferenceService;
        this.modelRegistry = modelRegistry;
        this.conversationService = conversationService;
        this.toolRegistry = toolRegistry;
        this.agentTraceService = agentTraceService;
    }

    /**
     * 提问（非流式）。与流式走同一套两段式流程，只是把答复收集起来一次性返回，保证两个入口答案一致
     */
    public LearnAnswer ask(String userId, Long conversationId, String question) {
        CollectingSink sink = new CollectingSink();
        askStream(userId, conversationId, question, sink);
        if (sink.error != null) {
            throw new IllegalStateException(sink.error);
        }
        String answer = StrUtil.isBlank(sink.answer) ? "本次没有生成有效回答，请换个说法再问一次。"
                : sink.answer.toString();
        return new LearnAnswer(sink.conversationId, answer, sink.references, sink.matchedDocs, sink.searchCalls,
                sink.promptTokens, sink.completionTokens, sink.runId, sink.elapsedMs);
    }

    /**
     * 提问（流式）。第一段阻塞跑完检索与阅读，引用清单先推给前端；第二段带着资料逐字生成答复
     * 拆成两段是因为框架的智能体循环用的是阻塞调用，答案只能整段产出
     */
    public void askStream(String userId, Long conversationId, String question, LearnStreamSink sink) {
        long begin = System.currentTimeMillis();
        ChatModel chatModel = modelRegistry.chatModel();
        UserPreference preference = preferenceService.get(userId);
        Long conversation;
        List<LearnMessage> history;
        try {
            conversation = conversationService.resolveConversation(userId, conversationId, question);
            history = conversationService.recentMessages(conversation);
            conversationService.appendUser(conversation, userId, question);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - begin;
            observeService.recordLlm(userId, SCENE_ASK, modelRegistry.modelName(), 0, 0, elapsed, false, e.getMessage());
            log.error("学习问答会话初始化失败，用户 {}：{}", userId, e.getMessage());
            sink.onError("会话初始化失败：" + e.getMessage());
            return;
        }
        sink.onConversation(conversation);

        DocumentReferenceRecorder recorder = new DocumentReferenceRecorder();
        ToolRegistry.ToolSet toolSet = toolRegistry.forScene(ToolRegistry.SCENE_LEARN, userId, recorder, conversation);
        Long runId = agentTraceService.begin(userId, SCENE_ASK, question);
        sink.onRun(runId);
        MentorAgent researchAgent;
        String notes;
        try {
            researchAgent = MentorAgent.forResearch(toolSet.callbacks(), chatModel);
            researchAgent.setStepListener(agentTraceService.listener(runId));
            notes = researchAgent.ask(withHistory(history, question));
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - begin;
            agentTraceService.finish(runId, 0, 0, 0, elapsed, false, e.getMessage());
            observeService.recordLlm(userId, SCENE_ASK, modelRegistry.modelName(), 0, 0, elapsed, false, e.getMessage());
            observeService.recordRetrieve(userId, SCENE_ASK, truncate(question), 0, 0, 0, elapsed, false, e.getMessage());
            log.error("学习问答检索阶段失败，用户 {}：{}", userId, e.getMessage());
            sink.onError("检索失败：" + e.getMessage());
            return;
        }

        List<KnowledgeDoc> readDocs = docsOf(recorder.readIds());
        List<KnowledgeDoc> matchedDocs = docsOf(recorder.matchedIds());
        List<KnowledgeDoc> references = readDocs.isEmpty() ? matchedDocs : readDocs;
        sink.onReferences(references, matchedDocs, recorder.searchCalls(), recorder.readCount());

        int promptTokens = researchAgent.getPromptTokens();
        int completionTokens = researchAgent.getCompletionTokens();
        StringBuilder answer = new StringBuilder();
        try {
            for (ChatResponse chunk : streamAnswer(chatModel, question, history, notes, references, preference)
                    .toIterable()) {
                if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) {
                    continue;
                }
                String text = chunk.getResult().getOutput().getText();
                if (text != null && !text.isEmpty()) {
                    sink.onDelta(text);
                    answer.append(text);
                }
                Usage usage = chunk.getMetadata() == null ? null : chunk.getMetadata().getUsage();
                if (usage != null) {
                    // 流式响应的用量只出现在个别分片里，取到就用
                    if (usage.getPromptTokens() != null && usage.getPromptTokens() > 0) {
                        promptTokens = researchAgent.getPromptTokens() + usage.getPromptTokens();
                    }
                    if (usage.getCompletionTokens() != null && usage.getCompletionTokens() > 0) {
                        completionTokens = researchAgent.getCompletionTokens() + usage.getCompletionTokens();
                    }
                }
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - begin;
            agentTraceService.finish(runId, researchAgent.getExecutedSteps(), promptTokens, completionTokens,
                    elapsed, false, e.getMessage());
            observeService.recordLlm(userId, SCENE_ASK, modelRegistry.modelName(), promptTokens, completionTokens,
                    elapsed, false, e.getMessage());
            log.error("学习问答生成阶段失败，用户 {}：{}", userId, e.getMessage());
            conversationService.appendAssistant(conversation, userId, answer.toString(),
                    docRefs(references), promptTokens, completionTokens, runId);
            sink.onError("生成答复失败：" + e.getMessage());
            return;
        }

        long elapsed = System.currentTimeMillis() - begin;
        agentTraceService.finish(runId, researchAgent.getExecutedSteps(), promptTokens, completionTokens,
                elapsed, true, null);
        observeService.recordLlm(userId, SCENE_ASK, modelRegistry.modelName(), promptTokens, completionTokens,
                elapsed, true, null);
        observeService.recordRetrieve(userId, SCENE_ASK, truncate(question), recorder.matchedCount(),
                recorder.readCount(), recorder.readChars(), elapsed, true, null);
        log.info("学习问答完成，用户 {}，工具检索 {} 次，命中 {} 篇，读取 {} 篇，token {}",
                userId, recorder.searchCalls(), recorder.matchedCount(), recorder.readCount(),
                promptTokens + completionTokens);
        conversationService.appendAssistant(conversation, userId, answer.toString(),
                docRefs(references), promptTokens, completionTokens, runId);
        sink.onDone(promptTokens, completionTokens, elapsed);
    }

    private Flux<ChatResponse> streamAnswer(ChatModel chatModel, String question, List<LearnMessage> history,
                                            String notes, List<KnowledgeDoc> references, UserPreference preference) {
        return ChatClient.builder(chatModel).build()
                .prompt()
                .system(ANSWER_SYSTEM_PROMPT + MentorAgent.styleHint(preference.learnAnswerStyle()))
                .user(buildAnswerPrompt(question, history, notes, references))
                .stream()
                .chatResponse();
    }

    /**
     * 把此前几轮对话拼在当前问题前面，使「那第二条呢」这类追问能落到具体对象上
     */
    private String withHistory(List<LearnMessage> history, String question) {
        if (history.isEmpty()) {
            return question;
        }
        return historyBlock(history) + "当前问题：" + question;
    }

    private String historyBlock(List<LearnMessage> history) {
        StringBuilder builder = new StringBuilder("此前对话：\n");
        for (LearnMessage message : history) {
            builder.append(ConversationService.ROLE_USER.equals(message.role()) ? "我：" : "助手：")
                    .append(clip(message.content())).append('\n');
        }
        return builder.append('\n').toString();
    }

    private String clip(String text) {
        if (text == null) {
            return "";
        }
        String plain = text.trim();
        return plain.length() > HISTORY_CHAR_LIMIT ? plain.substring(0, HISTORY_CHAR_LIMIT) + "…" : plain;
    }

    private List<KnowledgeDocRef> docRefs(List<KnowledgeDoc> docs) {
        List<KnowledgeDocRef> refs = new ArrayList<>();
        for (KnowledgeDoc doc : docs) {
            refs.add(new KnowledgeDocRef(doc.id(), doc.title(), doc.category()));
        }
        return refs;
    }

    /**
     * 拼装作答提示词：问题、检索要点与已读文档正文
     * 正文按总字数预算截断，避免把上下文撑爆
     */
    private String buildAnswerPrompt(String question, List<LearnMessage> history, String notes,
                                     List<KnowledgeDoc> references) {
        StringBuilder builder = new StringBuilder();
        if (!history.isEmpty()) {
            builder.append(historyBlock(history));
        }
        builder.append("问题：").append(question).append("\n\n");
        if (StrUtil.isNotBlank(notes)) {
            builder.append("检索阶段掌握的要点：\n").append(notes).append("\n\n");
        }
        if (references.isEmpty()) {
            builder.append("知识库中没有检索到相关文档。检索要点里如果有联网参考，")
                    .append("请说明知识库未覆盖并标注参考来源链接；没有的话直接说明没有找到依据，不要编造。\n\n");
            return builder.append("请作答。").toString();
        }
        builder.append("已读到的知识库文档：\n");
        int index = 1;
        int budget = MATERIAL_CHAR_LIMIT;
        for (KnowledgeDoc doc : references) {
            if (budget <= 0) {
                break;
            }
            String content = readQuietly(doc);
            if (content.isBlank()) {
                continue;
            }
            String body = content.length() > budget ? content.substring(0, budget) : content;
            budget -= body.length();
            builder.append("【").append(index++).append("】").append(doc.title())
                    .append("（分类：").append(doc.category()).append("）\n")
                    .append(body).append("\n\n");
        }
        return builder.append("请基于以上资料作答，知识库内容标注文档编号，")
                .append("来自联网的内容标注链接，两者分开说。").toString();
    }

    private String readQuietly(KnowledgeDoc doc) {
        try {
            return knowledgeService.readContent(doc, MATERIAL_PER_DOC_LIMIT);
        } catch (Exception e) {
            log.warn("拼装作答提示词时读取文档 {} 失败：{}", doc.contentRef(), e.getMessage());
            return "";
        }
    }

    /**
     * 收集式出口，供非流式接口把两段式结果拼回一个对象
     */
    private static final class CollectingSink implements LearnStreamSink {

        private final StringBuilder answer = new StringBuilder();
        private Long conversationId;
        private Long runId;
        private List<KnowledgeDoc> references = List.of();
        private List<KnowledgeDoc> matchedDocs = List.of();
        private int searchCalls;
        private int readCount;
        private int promptTokens;
        private int completionTokens;
        private long elapsedMs;
        private String error;

        @Override
        public void onConversation(Long conversationId) {
            this.conversationId = conversationId;
        }

        @Override
        public void onRun(Long runId) {
            this.runId = runId;
        }

        @Override
        public void onReferences(List<KnowledgeDoc> references, List<KnowledgeDoc> matchedDocs,
                                 int searchCalls, int readCount) {
            this.references = references;
            this.matchedDocs = matchedDocs;
            this.searchCalls = searchCalls;
            this.readCount = readCount;
        }

        @Override
        public void onDelta(String text) {
            answer.append(text);
        }

        @Override
        public void onDone(int promptTokens, int completionTokens, long elapsedMs) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.elapsedMs = elapsedMs;
        }

        @Override
        public void onError(String message) {
            this.error = message;
        }
    }

    /**
     * 按近期未完成任务与工作记录推荐学习资源，同时提示可能踩到的坑位
     */
    public LearnRecommend recommend(String userId) {
        long begin = System.currentTimeMillis();
        LocalDate today = LocalDate.now();
        List<WorkRecordEntity> records = reportService.recordsOf(userId, today.minusDays(RECOMMEND_LOOKBACK_DAYS), today);
        List<String> clues = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (WorkRecordEntity record : records) {
            if (record.getTitle() != null) {
                text.append(record.getTitle()).append(' ');
            }
            if (record.getContent() != null) {
                text.append(record.getContent()).append(' ');
            }
            if ("TASK".equals(record.getType()) && !isClosed(record.getStatus()) && clues.size() < RECOMMEND_LIMIT) {
                clues.add(record.getTitle());
            }
        }
        if (clues.isEmpty()) {
            for (WorkRecordEntity record : records) {
                if (record.getTitle() != null && clues.size() < RECOMMEND_LIMIT) {
                    clues.add(record.getTitle());
                }
            }
        }
        UserPreference preference = preferenceService.get(userId);
        String query = String.join(" ", clues);
        List<KnowledgeDoc> docs = knowledgeService.search(query, null, null, RECOMMEND_LIMIT);
        boolean fallback = false;
        // 线索匹配不到时，先用偏好标签再按偏好分类兜底，避免打开推荐页看到空列表
        if (docs.isEmpty() && !preference.learnTags().isEmpty()) {
            docs = knowledgeService.search(String.join(" ", preference.learnTags()), null, null, RECOMMEND_LIMIT);
            fallback = !docs.isEmpty();
        }
        if (docs.isEmpty()) {
            docs = byCategories(preference.learnCategories(), RECOMMEND_LIMIT);
            fallback = !docs.isEmpty();
        }
        // 偏好分类也为空时退回整个目录，保证推荐区不会是空的
        if (docs.isEmpty()) {
            docs = knowledgeService.search(null, null, null, RECOMMEND_LIMIT);
            fallback = !docs.isEmpty();
        }
        List<Pitfall> pitfalls = knowledgeService.matchPitfalls(text.toString());
        long elapsed = System.currentTimeMillis() - begin;
        observeService.recordRetrieve(userId, SCENE_RECOMMEND, truncate(query), docs.size(),
                0, 0, elapsed, true, null);
        log.info("学习资源推荐完成，用户 {}，线索 {} 条，推荐文档 {} 篇，命中坑位 {} 条，目录兜底 {}",
                userId, clues.size(), docs.size(), pitfalls.size(), fallback);
        return new LearnRecommend(userId, clues, fallback, docs, pitfalls);
    }

    /**
     * 按偏好分类依次取文档，去重后截断到条数上限
     */
    private List<KnowledgeDoc> byCategories(List<String> categories, int limit) {
        List<KnowledgeDoc> docs = new ArrayList<>();
        for (String category : categories) {
            for (KnowledgeDoc doc : knowledgeService.search(null, category, List.of(), limit)) {
                if (docs.size() >= limit) {
                    return docs;
                }
                if (docs.stream().noneMatch(item -> item.id().equals(doc.id()))) {
                    docs.add(doc);
                }
            }
        }
        return docs;
    }

    private List<KnowledgeDoc> docsOf(List<Long> ids) {
        List<KnowledgeDoc> docs = new ArrayList<>();
        for (Long id : ids) {
            KnowledgeDoc doc = knowledgeService.getDoc(id);
            if (doc != null) {
                docs.add(doc);
            }
        }
        return docs;
    }

    private boolean isClosed(String status) {
        return status != null && CLOSED_STATUS.contains(status.trim().toLowerCase());
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.length() > QUERY_TEXT_LIMIT ? trimmed.substring(0, QUERY_TEXT_LIMIT) : trimmed;
    }
}

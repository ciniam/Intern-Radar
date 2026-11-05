package com.ciniam.internradar.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ciniam.internradar.model.ConfigProposal;
import com.ciniam.internradar.model.KnowledgeDoc;
import com.ciniam.internradar.model.LearnAnswer;
import com.ciniam.internradar.model.LearnConversation;
import com.ciniam.internradar.model.LearnConversationView;
import com.ciniam.internradar.model.LearnRecommend;
import com.ciniam.internradar.model.LearnStreamSink;
import com.ciniam.internradar.model.Pitfall;
import com.ciniam.internradar.security.AuthGuard;
import com.ciniam.internradar.service.ConfigProposalService;
import com.ciniam.internradar.service.ConversationService;
import com.ciniam.internradar.service.KnowledgeService;
import com.ciniam.internradar.service.LearnService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 学习赋能接口：问答、资源推荐、文档库与坑位库检索
 */
@RestController
@RequestMapping("/learn")
public class LearnController {

    private static final Logger log = LoggerFactory.getLogger(LearnController.class);

    private final LearnService learnService;
    private final KnowledgeService knowledgeService;
    private final ConversationService conversationService;
    private final ConfigProposalService configProposalService;
    private final AuthGuard authGuard;
    private final ObjectMapper objectMapper;

    public LearnController(LearnService learnService, KnowledgeService knowledgeService,
                           ConversationService conversationService,
                           ConfigProposalService configProposalService,
                           AuthGuard authGuard, ObjectMapper objectMapper) {
        this.learnService = learnService;
        this.knowledgeService = knowledgeService;
        this.conversationService = conversationService;
        this.configProposalService = configProposalService;
        this.authGuard = authGuard;
        this.objectMapper = objectMapper;
    }

    /**
     * 提问，返回答案与引用到的文档清单
     */
    @PostMapping("/ask")
    public LearnAnswer ask(@RequestParam(required = false) String userId,
                           @RequestParam(required = false) Long conversationId,
                           @RequestParam String question) {
        if (question == null || question.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question 不能为空");
        }
        return learnService.ask(authGuard.resolveUserId(userId), conversationId, question);
    }

    /**
     * 流式提问。先推引用清单，再逐字推答复，最后推用量
     * 事件内容统一是 JSON，前端按 type 字段分发
     */
    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@RequestParam(required = false) String userId,
                                @RequestParam(required = false) Long conversationId,
                                @RequestParam String question) {
        if (question == null || question.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question 不能为空");
        }
        String target = authGuard.resolveUserId(userId);
        SseEmitter emitter = new SseEmitter(300_000L);
        // 检索阶段是阻塞的，放到独立线程，避免占住请求线程
        CompletableFuture.runAsync(() -> {
            try {
                learnService.askStream(target, conversationId, question, new SseLearnStreamSink(emitter, objectMapper));
                emitter.complete();
            } catch (Exception e) {
                new SseLearnStreamSink(emitter, objectMapper).onError("问答失败：" + e.getMessage());
                emitter.complete();
            }
        });
        emitter.onTimeout(emitter::complete);
        return emitter;
    }

    /**
     * 会话列表，只返回当前登录用户自己的会话，keyword 按标题模糊匹配
     */
    @GetMapping("/conversations")
    public List<LearnConversation> conversations(@RequestParam(required = false) String keyword) {
        return conversationService.list(authGuard.currentUserId(), keyword);
    }

    /**
     * 会话重命名
     */
    @PostMapping("/conversations/{id}/title")
    public LearnConversationView renameConversation(@PathVariable Long id, @RequestParam String title) {
        return conversationService.rename(authGuard.currentUserId(), id, title);
    }

    /**
     * 回退最后一轮，返回需要重新生成的问题
     */
    @PostMapping("/conversations/{id}/rewind")
    public Map<String, Object> rewindConversation(@PathVariable Long id) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("question", conversationService.rewind(authGuard.currentUserId(), id));
        return result;
    }

    /**
     * 会话详情，含全部历史消息与引用文档
     */
    @GetMapping("/conversations/{id}")
    public LearnConversationView conversation(@PathVariable Long id) {
        return conversationService.history(authGuard.currentUserId(), id);
    }

    /**
     * 该会话里待用户确认的配置变更提案
     */
    @GetMapping("/conversations/{id}/proposals")
    public List<ConfigProposal> proposals(@PathVariable Long id) {
        conversationService.history(authGuard.currentUserId(), id);
        return configProposalService.pendingOfConversation(authGuard.currentUserId(), id);
    }

    /**
     * 应用提案：确认后才写进个人配置
     */
    @PostMapping("/proposals/{id}/apply")
    public ConfigProposal applyProposal(@PathVariable Long id) {
        return configProposalService.apply(authGuard.currentUserId(), id);
    }

    @PostMapping("/proposals/{id}/reject")
    public ConfigProposal rejectProposal(@PathVariable Long id) {
        return configProposalService.reject(authGuard.currentUserId(), id);
    }

    @DeleteMapping("/conversations/{id}")
    public Map<String, Object> removeConversation(@PathVariable Long id) {
        conversationService.remove(authGuard.currentUserId(), id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        return result;
    }

    /**
     * 把流式回调转成 SSE 事件，序列化失败只记日志，不打断整条流
     */
    private record SseLearnStreamSink(SseEmitter emitter, ObjectMapper objectMapper) implements LearnStreamSink {

        @Override
        public void onConversation(Long conversationId) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "conversation");
            payload.put("conversationId", conversationId);
            send(payload);
        }

        @Override
        public void onRun(Long runId) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "run");
            payload.put("runId", runId);
            send(payload);
        }

        @Override
        public void onReferences(List<KnowledgeDoc> references, List<KnowledgeDoc> matchedDocs,
                                 int searchCalls, int readCount) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "references");
            payload.put("references", references);
            payload.put("matchedDocs", matchedDocs);
            payload.put("searchCalls", searchCalls);
            payload.put("readCount", readCount);
            send(payload);
        }

        @Override
        public void onDelta(String text) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "delta");
            payload.put("text", text);
            send(payload);
        }

        @Override
        public void onDone(int promptTokens, int completionTokens, long elapsedMs) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "done");
            payload.put("promptTokens", promptTokens);
            payload.put("completionTokens", completionTokens);
            payload.put("elapsedMs", elapsedMs);
            send(payload);
        }

        @Override
        public void onError(String message) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "error");
            payload.put("message", message);
            send(payload);
        }

        private void send(Map<String, Object> payload) {
            try {
                emitter.send(objectMapper.writeValueAsString(payload));
            } catch (Exception e) {
                log.warn("推送流式事件失败，类型 {}：{}", payload.get("type"), e.getMessage());
            }
        }
    }

    /**
     * 按近期任务推荐学习资源与需要规避的坑位
     */
    @GetMapping("/recommend")
    public LearnRecommend recommend(@RequestParam(required = false) String userId) {
        return learnService.recommend(authGuard.resolveUserId(userId));
    }

    /**
     * 文档库检索，供前端与智能体共用。关键词为空时返回全部目录，limit 不传表示不截断
     */
    @GetMapping("/docs")
    public List<KnowledgeDoc> docs(@RequestParam(required = false) String keyword,
                                   @RequestParam(required = false) String category,
                                   @RequestParam(required = false) String tags,
                                   @RequestParam(required = false) Integer limit) {
        List<String> tagList = tags == null || tags.isBlank() ? List.of() : List.of(tags.split("[,，、;；\\s]+"));
        // 浏览列表不按检索条数截断，否则文档库会少显示文档；调用方显式传 limit 时以传入值为准
        return knowledgeService.search(keyword, category, tagList, limit == null ? 0 : limit);
    }

    /**
     * 查看单篇文档，附带正文，便于前端直接展示
     */
    @GetMapping("/docs/{id}")
    public Map<String, Object> doc(@PathVariable Long id) {
        KnowledgeDoc doc = knowledgeService.getDoc(id);
        if (doc == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在：" + id);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("doc", doc);
        result.put("content", knowledgeService.readContent(doc, null));
        return result;
    }

    /**
     * 重新扫描知识目录并同步元数据，改动文档文件后调一次即可生效
     */
    @PostMapping("/docs/refresh")
    public Map<String, Object> refresh() {
        return knowledgeService.syncFromFiles();
    }

    /**
     * 上传文档。写入知识目录后立刻同步，登录用户均可上传，上传人取当前登录者
     */
    @PostMapping(value = "/docs/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file,
                                      @RequestParam(required = false) String title,
                                      @RequestParam(required = false) String category,
                                      @RequestParam(required = false) String tags,
                                      @RequestParam(required = false) String source,
                                      @RequestParam(required = false) String visibility,
                                      @RequestParam(required = false, defaultValue = "false") boolean overwrite) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未选择文件");
        }
        String content;
        try {
            content = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("文件读取失败：" + e.getMessage(), e);
        }
        return knowledgeService.saveUploaded(file.getOriginalFilename(), content,
                title, category, tags, source, visibility, authGuard.currentUserId(), overwrite);
    }

    /**
     * 上传前的同名检测，前端据此弹出「覆盖 / 另存」选择
     */
    @GetMapping("/docs/check")
    public Map<String, Object> checkDuplicate(@RequestParam String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fileName 不能为空");
        }
        return knowledgeService.checkDuplicate(fileName);
    }

    @GetMapping("/pitfalls")
    public List<Pitfall> pitfalls(@RequestParam(required = false) String keyword) {
        return knowledgeService.listPitfalls(keyword);
    }
}

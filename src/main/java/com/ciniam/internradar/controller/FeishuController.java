package com.ciniam.internradar.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ciniam.internradar.adapter.feishu.FeishuChatDataSource;
import com.ciniam.internradar.entity.ChatBindingEntity;
import com.ciniam.internradar.feishu.FeishuApiException;
import com.ciniam.internradar.model.ChatBindingView;
import com.ciniam.internradar.feishu.FeishuClient;
import com.ciniam.internradar.feishu.FeishuUserAuthService;
import com.ciniam.internradar.security.AuthGuard;
import com.ciniam.internradar.service.ChatBindingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 飞书授权与排障接口
 * 授权链接需要用户在浏览器打开，回调后令牌才会入库，采集聊天记录前必须先完成这一步
 */
@RestController
@RequestMapping("/feishu")
public class FeishuController {

    private static final String CHAT_LIST_PATH = "/im/v1/chats?page_size=100";
    private static final String CHAT_SCOPE_HINT =
            "查询会话列表需要用户身份权限 im:chat:readonly，请在开放平台申请并发布版本后重新授权";

    private final FeishuUserAuthService userAuthService;
    private final FeishuClient feishuClient;
    private final FeishuChatDataSource chatDataSource;
    private final ChatBindingService chatBindingService;
    private final ObjectMapper objectMapper;
    private final AuthGuard authGuard;

    public FeishuController(FeishuUserAuthService userAuthService,
                            FeishuClient feishuClient,
                            FeishuChatDataSource chatDataSource,
                            ChatBindingService chatBindingService,
                            ObjectMapper objectMapper,
                            AuthGuard authGuard) {
        this.userAuthService = userAuthService;
        this.feishuClient = feishuClient;
        this.chatDataSource = chatDataSource;
        this.chatBindingService = chatBindingService;
        this.objectMapper = objectMapper;
        this.authGuard = authGuard;
    }

    /**
     * 绑定一个会话到采集白名单。chat_id 从飞书客户端会话设置页复制
     * identity 填 USER 表示用该用户的授权令牌读他自己参与的会话，填 APP 表示读机器人所在的会话
     * 绑定前会先探测一次，把错的 id 或错的身份当场挡下来
     */
    @PostMapping("/chats/bind")
    public Map<String, Object> bindChat(@RequestParam(required = false) String userId,
                                        @RequestParam String chatId,
                                        @RequestParam(required = false) String name,
                                        @RequestParam(required = false, defaultValue = "USER") String identity) {
        String target = authGuard.resolveUserId(userId);
        return chatBindingService.bind(target, chatId, name, identity);
    }

    /**
     * 批量绑定，一次贴多个 chat_id
     */
    @PostMapping("/chats/bind-batch")
    public List<Map<String, Object>> bindChatBatch(@RequestBody List<Map<String, String>> items) {
        List<Map<String, String>> resolved = new ArrayList<>();
        if (items != null) {
            for (Map<String, String> item : items) {
                Map<String, String> copy = new LinkedHashMap<>(item);
                copy.put("userId", authGuard.resolveUserId(item.get("userId")));
                resolved.add(copy);
            }
        }
        return chatBindingService.bindBatch(resolved);
    }

    /**
     * 导师可以按人过滤或查看全部，实习生只能看到自己的绑定
     */
    @GetMapping("/chats/bindings")
    public List<ChatBindingView> listBindings(@RequestParam(required = false) String userId) {
        if (authGuard.current().canViewTeam()) {
            return chatBindingService.listWithSummary(userId);
        }
        return chatBindingService.listWithSummary(authGuard.currentUserId());
    }

    @DeleteMapping("/chats/bindings/{id}")
    public Map<String, Object> unbind(@PathVariable Long id) {
        ChatBindingEntity binding = chatBindingService.findById(id);
        if (binding == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "绑定记录不存在：" + id);
        }
        authGuard.requireSelfOrMentor(binding.getUserId(), "会话绑定");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("removed", chatBindingService.unbind(id));
        return result;
    }

    @GetMapping(value = "/oauth/url", produces = MediaType.TEXT_PLAIN_VALUE)
    public String authorizeUrl(@RequestParam(required = false) String userId) {
        String target = authGuard.resolveUserId(userId);
        return userAuthService.authorizeUrl(target);
    }

    /**
     * 授权回调由浏览器直接跳转，无法携带登录凭证，因此不做登录校验
     */
    @GetMapping(value = "/oauth/callback", produces = MediaType.TEXT_PLAIN_VALUE)
    public String callback(@RequestParam(required = false) String code,
                           @RequestParam(required = false) String state,
                           @RequestParam(required = false) String error) {
        if (error != null && !error.isBlank()) {
            return "授权被拒绝：" + error;
        }
        return userAuthService.handleCallback(code, state);
    }

    @GetMapping(value = "/auth", produces = MediaType.TEXT_PLAIN_VALUE)
    public String authStatus(@RequestParam(required = false) String userId) {
        return userAuthService.status(authGuard.resolveUserId(userId));
    }

    /**
     * 列出可见会话，用于填写聊天记录白名单
     * 用用户身份查询时会列出该用户参与的会话，应用身份只能列出机器人所在的会话
     * mode 参数可按 chat_mode 过滤，填 p2p 只看单聊，填 group 只看群
     */
    @GetMapping("/chats")
    public Map<String, Object> chats(@RequestParam(required = false) String userId,
                                    @RequestParam(required = false) String mode) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> hints = new ArrayList<>();
        String target = userId == null || userId.isBlank() ? null : authGuard.resolveUserId(userId);
        String userToken = null;
        if (target != null) {
            userToken = userAuthService.accessToken(target);
            if (userToken == null) {
                hints.add("用户 " + target + " 尚未完成飞书授权，已改用应用身份查询");
            } else {
                hints.add("当前令牌授权范围：" + userAuthService.grantedScope(target));
            }
        }
        Map<String, Object> response = queryChats(userToken, hints);
        JsonNode items = objectMapper.valueToTree(response.get("data")).path("items");
        List<Map<String, Object>> chats = new ArrayList<>();
        int p2pCount = 0;
        int groupCount = 0;
        for (JsonNode item : items) {
            String chatMode = item.path("chat_mode").asText();
            if ("p2p".equalsIgnoreCase(chatMode)) {
                p2pCount++;
            } else if ("group".equalsIgnoreCase(chatMode)) {
                groupCount++;
            }
            if (mode != null && !mode.isBlank() && !mode.equalsIgnoreCase(chatMode)) {
                continue;
            }
            Map<String, Object> chat = new LinkedHashMap<>();
            chat.put("chat_id", item.path("chat_id").asText());
            chat.put("name", item.path("name").asText());
            chat.put("chat_mode", chatMode);
            chats.add(chat);
        }
        result.put("count", chats.size());
        result.put("p2pCount", p2pCount);
        result.put("groupCount", groupCount);
        result.put("chats", chats);
        result.put("hints", hints);
        if (userToken == null) {
            result.put("tip", "当前是应用身份，只能列出机器人所在的群，不含单聊；"
                    + "想看你自己参与的全部会话含单聊，请先完成用户授权再查");
        } else {
            result.put("tip", "当前是用户身份，列出的会话以该用户的可见范围为准；"
                    + "chat_mode 为 p2p 的是单聊，拿到 chat_id 后绑定即可");
        }
        return result;
    }

    /**
     * 绑定机器人与某个用户的单聊：发一条消息，用响应里回带的 chat_id 作为采集目标
     * 群列表接口不返回单聊，这是官方说明的获取单聊 chat_id 的方式，且不需要用户授权
     */
    @PostMapping("/bind-p2p")
    public Map<String, Object> bindP2p(@RequestParam(required = false) String userId,
                                       @RequestParam String openId,
                                       @RequestParam(required = false) String text) {
        String target = authGuard.resolveUserId(userId);
        String content = text == null || text.isBlank()
                ? "实习雷达已启用，后续可在这里汇报当日进展。" : text;
        Map<String, Object> data = feishuClient.sendTextToUser(openId, content);
        String chatId = data.get("chat_id") == null ? "" : String.valueOf(data.get("chat_id"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", !chatId.isBlank());
        result.put("chat_id", chatId);
        result.put("message_id", data.get("message_id"));
        result.put("tip", "把 chat_id 填到看板的会话绑定页即可开始采集该单聊摘要");
        result.put("userId", target);
        return result;
    }

    /**
     * 会话探针：判断某个 chat_id 是单聊还是群，以及哪种身份能读到消息
     * identity 填 USER 需要带上已授权的 userId，填 APP 走应用身份
     */
    @GetMapping("/probe")
    public Map<String, Object> probe(@RequestParam String chatId,
                                     @RequestParam(defaultValue = "APP") String identity,
                                     @RequestParam(required = false) String userId,
                                     @RequestParam(defaultValue = "7") int days) {
        String target = "USER".equalsIgnoreCase(identity) ? authGuard.resolveUserId(userId) : userId;
        return chatDataSource.probe(chatId, identity, target, days);
    }

    private Map<String, Object> queryChats(String userToken, List<String> hints) {
        if (userToken == null) {
            return feishuClient.get(CHAT_LIST_PATH, null);
        }
        try {
            return feishuClient.get(CHAT_LIST_PATH, userToken);
        } catch (FeishuApiException e) {
            hints.add("以用户身份查询会话列表失败：" + e.getMessage());
            hints.add(CHAT_SCOPE_HINT);
            hints.add("已回退使用应用身份查询，这种方式只能看到机器人所在的群");
            return feishuClient.get(CHAT_LIST_PATH, null);
        }
    }
}

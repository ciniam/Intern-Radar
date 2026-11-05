package com.ciniam.internradar.adapter.zentao;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ciniam.internradar.adapter.WorkDataSource;
import com.ciniam.internradar.config.InternRadarProperties;
import com.ciniam.internradar.model.DataSourceCredential;
import com.ciniam.internradar.model.WorkRecord;
import com.ciniam.internradar.model.WorkRecordType;
import com.ciniam.internradar.service.PreferenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 禅道数据源，走老世代 JSON 端点
 * 已实测可用的接口是 my-work、my-todo、my-effort，my-task 与 my-bug 是权限拒绝页
 * 响应字段按常见命名做兼容取值，字段缺失不会导致采集失败
 */
@Component
public class ZentaoDataSource implements WorkDataSource {

    private static final Logger log = LoggerFactory.getLogger(ZentaoDataSource.class);

    private static final String SOURCE_NAME = "zentao";
    private static final Set<String> DENY_FIELDS = Set.of("denyPage", "denyType");
    private static final Set<String> DONE_STATUS = Set.of("done", "closed", "cancel", "resolved", "finished");

    private final ZentaoHttpClient httpClient;
    private final ZentaoSessionManager sessionManager;
    private final InternRadarProperties properties;
    private final ObjectMapper objectMapper;
    private final PreferenceService preferenceService;

    public ZentaoDataSource(ZentaoHttpClient httpClient,
                            ZentaoSessionManager sessionManager,
                            InternRadarProperties properties,
                            ObjectMapper objectMapper,
                            PreferenceService preferenceService) {
        this.httpClient = httpClient;
        this.sessionManager = sessionManager;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.preferenceService = preferenceService;
    }

    @Override
    public String getSourceName() {
        return SOURCE_NAME;
    }

    @Override
    public boolean healthCheck() {
        ZentaoHttpClient.Response response = httpClient.get("/");
        return response.reachable();
    }

    /**
     * 原始响应，用于确认字段映射
     * 会先确保会话有效，否则拿到的只是登录页
     */
    public String raw(String path, String userId) {
        String key = sessionKey(userId);
        DataSourceCredential credential = preferenceService.zentaoCredential(userId);
        boolean sessionReady = credential.usable() && sessionManager.ensureSession(key, credential);
        ZentaoHttpClient.Response response = sessionManager.client(key).get(path);
        StringBuilder builder = new StringBuilder();
        builder.append("会话状态：").append(sessionReady ? "已建立" : "未建立，返回内容大概率是登录页").append('\n');
        builder.append("凭据来源：").append(credential.origin())
                .append("，账号：").append(credential.account()).append('\n');
        builder.append("HTTP ").append(response.status()).append('\n');
        JsonNode root = read(response.body());
        JsonNode data = root == null ? null : root.path("data");
        if (data != null && data.isTextual()) {
            data = read(data.asText());
        }
        if (isLoginPage(data)) {
            builder.append("判定：这是禅道登录页，不是业务数据。请确认已开启登录闸门且账号可用").append('\n');
        }
        builder.append("原始内容：\n").append(response.body());
        return builder.toString();
    }

    public String sessionStatus(String userId) {
        return sessionManager.status(sessionKey(userId));
    }

    @Override
    public List<WorkRecord> fetch(String userId, LocalDate date) {
        String key = sessionKey(userId);
        DataSourceCredential credential = preferenceService.zentaoCredential(userId);
        if (!credential.usable()) {
            log.warn("用户 {} 没有可用的内部系统凭据，跳过采集", userId);
            return List.of();
        }
        if (!sessionManager.ensureSession(key, credential)) {
            log.warn("内部系统会话不可用，用户 {}，状态：{}", userId, sessionManager.status(key));
            return List.of();
        }
        ZentaoHttpClient client = sessionManager.client(key);
        List<WorkRecord> records = new ArrayList<>();
        records.addAll(fetchTasks(client, key, userId, date));
        records.addAll(fetchTodos(client, key, userId, date));
        records.addAll(fetchEfforts(client, key, userId, date));
        return records;
    }

    /**
     * 每个使用者一份会话，避免共用 Cookie 造成串号
     */
    private String sessionKey(String userId) {
        return userId == null || userId.isBlank() ? ZentaoSessionManager.defaultKey() : "user:" + userId;
    }

    private List<WorkRecord> fetchTasks(ZentaoHttpClient client, String sessionKey, String userId, LocalDate date) {
        JsonNode data = loadData(client, sessionKey, "/my-work.json");
        List<JsonNode> nodes = items(data, "tasks", "works");
        List<WorkRecord> records = new ArrayList<>();
        for (JsonNode node : nodes) {
            String taskId = text(node, "id", "task");
            if (taskId == null) {
                continue;
            }
            LocalDateTime lastEdited = dateTime(node, "lastEditedDate", "lastEdited", "editedDate", "openedDate");
            if (lastEdited == null) {
                lastEdited = LocalDateTime.now();
            }
            String rawStatus = text(node, "status");
            String status = normalizeStatus(rawStatus);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("kind", "task");
            metadata.put("taskId", taskId);
            metadata.put("rawStatus", rawStatus);
            // 禅道任务没有 progress 字段，进度由已消耗工时与预估工时推算，推不出来就留空
            metadata.put("progress", progressOf(node));
            metadata.put("estimate", text(node, "estimate"));
            metadata.put("consumed", text(node, "consumed"));
            metadata.put("left", text(node, "left"));
            metadata.put("deadline", text(node, "deadline"));
            metadata.put("project", text(node, "executionName", "projectName", "project"));
            metadata.put("owner", text(node, "assignedTo", "finishedBy"));
            String title = text(node, "name", "title");
            String content = "状态 " + safe(rawStatus) + "，进度 " + safe((String) metadata.get("progress"))
                    + "，截止 " + safe(text(node, "deadline"));
            records.add(new WorkRecord(userId, lastEdited, WorkRecordType.TASK, safe(title), content, status,
                    taskSnapshotKey(taskId, lastEdited, metadata), metadata));
        }
        log.info("禅道任务采集 {} 条", records.size());
        return records;
    }

    private List<WorkRecord> fetchTodos(ZentaoHttpClient client, String sessionKey, String userId, LocalDate date) {
        JsonNode data = loadData(client, sessionKey, "/my-todo.json");
        List<JsonNode> nodes = items(data, "todos", "tasks");
        List<WorkRecord> records = new ArrayList<>();
        for (JsonNode node : nodes) {
            String todoId = text(node, "id");
            if (todoId == null) {
                continue;
            }
            LocalDate begin = date(node, "date", "begin", "start");
            if (begin == null) {
                begin = date;
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("kind", "todo");
            metadata.put("todoId", todoId);
            metadata.put("deadline", text(node, "deadline", "end"));
            metadata.put("project", text(node, "project", "projectName"));
            String title = text(node, "name", "title", "content");
            records.add(new WorkRecord(userId, begin.atTime(9, 0), WorkRecordType.TASK, safe(title),
                    "待办事项", "wait", "todo:" + todoId + ":" + begin, metadata));
        }
        log.info("禅道待办采集 {} 条", records.size());
        return records;
    }

    private List<WorkRecord> fetchEfforts(ZentaoHttpClient client, String sessionKey, String userId, LocalDate date) {
        JsonNode data = loadData(client, sessionKey, "/my-effort.json");
        List<JsonNode> nodes = items(data, "efforts", "effort", "works");
        List<WorkRecord> records = new ArrayList<>();
        for (JsonNode node : nodes) {
            String effortId = text(node, "id");
            LocalDate workDate = date(node, "date", "workDate");
            if (effortId == null || workDate == null || workDate.isAfter(date)) {
                continue;
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("kind", "effort");
            metadata.put("effortId", effortId);
            metadata.put("consumed", text(node, "consumed"));
            metadata.put("project", text(node, "project", "projectName"));
            String work = text(node, "work", "content", "name");
            String content = "工时 " + safe(text(node, "consumed")) + "，内容 " + safe(work);
            records.add(new WorkRecord(userId, workDate.atTime(18, 0), WorkRecordType.DOC, safe(work), content,
                    "done", "effort:" + effortId, metadata));
        }
        log.info("禅道日志采集 {} 条", records.size());
        return records;
    }

    private JsonNode loadData(ZentaoHttpClient client, String sessionKey, String path) {
        if (!checkEnabled()) {
            return null;
        }
        ZentaoHttpClient.Response response = client.get(path);
        if (!response.reachable()) {
            log.warn("禅道接口 {} 不可达：{}", path, response.body());
            return null;
        }
        if (response.status() == 401 || response.status() == 403) {
            log.warn("禅道接口 {} 返回 {}，判定会话失效", path, response.status());
            sessionManager.invalidate(sessionKey);
            return null;
        }
        JsonNode root = read(response.body());
        if (root == null) {
            log.warn("禅道接口 {} 返回非 JSON，片段：{}", path, shorten(response.body()));
            return null;
        }
        if (!"success".equalsIgnoreCase(root.path("status").asText(""))) {
            log.warn("禅道接口 {} 返回失败，片段：{}", path, shorten(response.body()));
            return null;
        }
        JsonNode data = root.path("data");
        if (data.isTextual()) {
            data = read(data.asText());
        }
        if (data == null || data.isNull()) {
            return null;
        }
        for (String deny : DENY_FIELDS) {
            if (data.has(deny)) {
                log.warn("禅道接口 {} 返回权限拒绝页，该接口不可用", path);
                return null;
            }
        }
        if (isLoginPage(data)) {
            sessionManager.invalidate(sessionKey);
            throw new IllegalStateException("禅道会话已失效，接口 " + path + " 返回的是登录页而非业务数据");
        }
        return data;
    }

    /**
     * 识别伪成功：禅道会把登录页包成 status=success 返回
     * 判定特征是 loginExpired 为真、标题为登录页、只有会话字段而没有业务列表
     */
    private boolean isLoginPage(JsonNode data) {
        if (data == null || !data.isObject()) {
            return false;
        }
        if (data.path("loginExpired").asBoolean(false)) {
            return true;
        }
        if (data.path("title").asText("").contains("登录")) {
            return true;
        }
        return data.has("sn") && !data.has("tasks") && !data.has("todos") && !data.has("efforts");
    }

    private boolean checkEnabled() {
        String base = properties.getZentao().getBaseUrl();
        return base != null && !base.isBlank() && !base.contains("example.com");
    }

    /**
     * 兼容三种返回结构：数组、带列表键的对象、以 id 为键的对象
     */
    private List<JsonNode> items(JsonNode data, String... listKeys) {
        if (data == null || data.isNull()) {
            return List.of();
        }
        if (data.isArray()) {
            List<JsonNode> list = new ArrayList<>();
            data.forEach(list::add);
            return list;
        }
        for (String key : listKeys) {
            JsonNode node = data.path(key);
            if (node.isArray()) {
                List<JsonNode> list = new ArrayList<>();
                node.forEach(list::add);
                return list;
            }
            if (node.isObject()) {
                return objectValues(node);
            }
        }
        if (data.isObject()) {
            return objectValues(data);
        }
        return List.of();
    }

    private List<JsonNode> objectValues(JsonNode node) {
        List<JsonNode> list = new ArrayList<>();
        node.fields().forEachRemaining(entry -> {
            if (entry.getValue().isObject()) {
                list.add(entry.getValue());
            }
        });
        return list;
    }

    /**
     * 推算任务进度。优先用 progress 字段，取不到时按已消耗与预估工时折算
     * 禅道返回的进度可能是 56.99999999999999 这种浮点，统一取整
     */
    private String progressOf(JsonNode node) {
        String progress = text(node, "progress");
        if (progress != null) {
            return String.valueOf((int) Math.round(toNumber(progress)));
        }
        double estimate = toNumber(text(node, "estimate"));
        double consumed = toNumber(text(node, "consumed"));
        if (estimate <= 0) {
            return null;
        }
        return String.valueOf((int) Math.round(Math.min(consumed, estimate) / estimate * 100));
    }

    /**
     * 任务快照标识。带上状态与进度，任务发生变化时会留下新的快照，便于追踪状态变更
     */
    private String taskSnapshotKey(String taskId, LocalDateTime lastEdited, Map<String, Object> metadata) {
        return "task:" + taskId + ":" + lastEdited.toLocalDate()
                + ":" + safe(String.valueOf(metadata.get("rawStatus")))
                + ":" + safe(String.valueOf(metadata.get("progress")));
    }

    private double toNumber(String value) {
        if (value == null || value.isBlank()) {
            return 0d;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return 0d;
        }
    }

    private String normalizeStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return "unknown";
        }
        return DONE_STATUS.contains(rawStatus.trim().toLowerCase()) ? "done" : rawStatus.trim();
    }

    private String text(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            if (!value.isMissingNode() && !value.isNull() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private LocalDate date(JsonNode node, String... names) {
        String value = text(node, names);
        return parseDate(value);
    }

    private LocalDateTime dateTime(JsonNode node, String... names) {
        String value = text(node, names);
        if (value == null || value.length() < 10) {
            return null;
        }
        LocalDate day = parseDate(value);
        if (day == null) {
            return null;
        }
        if (value.length() >= 19) {
            try {
                return LocalDateTime.parse(value.substring(0, 19).replace(' ', 'T'));
            } catch (Exception e) {
                return day.atTime(12, 0);
            }
        }
        return day.atTime(12, 0);
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.length() < 10 || value.startsWith("0000")) {
            return null;
        }
        try {
            return LocalDate.parse(value.substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode read(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    private String safe(String value) {
        return value == null ? "未知" : value;
    }

    private String shorten(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 200 ? text.substring(0, 200) : text;
    }
}

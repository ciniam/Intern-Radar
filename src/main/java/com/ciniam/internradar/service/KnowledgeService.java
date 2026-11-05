package com.ciniam.internradar.service;

import com.ciniam.internradar.config.InternRadarProperties;
import com.ciniam.internradar.entity.KnowledgeDocEntity;
import com.ciniam.internradar.entity.PitfallEntity;
import com.ciniam.internradar.mapper.KnowledgeDocMapper;
import com.ciniam.internradar.mapper.PitfallMapper;
import com.ciniam.internradar.model.KnowledgeDoc;
import com.ciniam.internradar.model.Pitfall;
import com.ciniam.internradar.model.SysUser;
import com.ciniam.internradar.util.MarkdownDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 知识库读写：文件是唯一事实来源，库表只存元数据
 * 启动与手动刷新时按目录扫描同步，检索走标题、分类、标签与摘要，正文按需从文件读取
 */
@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^0-9A-Za-z\\u4e00-\\u9fa5]+");
    private static final Pattern FILE_SAFE = Pattern.compile("[^0-9A-Za-z\\u4e00-\\u9fa5._-]");
    private static final int MIN_TOKEN_LENGTH = 2;
    private static final int MAX_QUERY_LENGTH = 480;
    private static final int MAX_UPLOAD_CHARS = 1024 * 1024;
    private static final List<String> ALLOWED_EXTENSIONS = List.of("md", "txt");

    private final KnowledgeDocMapper docMapper;
    private final PitfallMapper pitfallMapper;
    private final InternRadarProperties properties;
    private final TeamSettingService teamSettingService;
    private final UserDirectory userDirectory;
    private final TeamScope teamScope;

    public KnowledgeService(KnowledgeDocMapper docMapper,
                            PitfallMapper pitfallMapper,
                            InternRadarProperties properties,
                            TeamSettingService teamSettingService,
                            UserDirectory userDirectory,
                            TeamScope teamScope) {
        this.docMapper = docMapper;
        this.pitfallMapper = pitfallMapper;
        this.properties = properties;
        this.teamSettingService = teamSettingService;
        this.userDirectory = userDirectory;
        this.teamScope = teamScope;
    }

    /**
     * 扫描知识目录同步元数据，返回本次同步的文档数、坑位数与清理数
     */
    public Map<String, Object> syncFromFiles() {
        return syncFromFiles(teamScope.current());
    }

    public Map<String, Object> syncFromFiles(long teamId) {
        Path root = rootPath(teamId);
        List<String> docRefs = new ArrayList<>();
        for (Path file : markdownFiles(root.resolve("docs"))) {
            MarkdownDoc.Parsed parsed = MarkdownDoc.parse(readQuietly(file));
            String ref = root.relativize(file).toString().replace('\\', '/');
            docMapper.upsert(KnowledgeDocEntity.builder()
                    .teamId(teamId)
                    .title(firstNonBlank(parsed.meta("title"), fileName(file)))
                    .category(firstNonBlank(parsed.meta("category"), "未分类"))
                    .tags(parsed.meta("tags"))
                    .source(firstNonBlank(parsed.meta("source"), "本地文档"))
                    .visibility(firstNonBlank(parsed.meta("visibility"), "TEAM"))
                    .contentRef(ref)
                    .summary(firstNonBlank(parsed.meta("summary"), summarize(parsed.body())))
                    .enabled(true)
                    .updatedAt(LocalDateTime.now())
                    .build());
            docRefs.add(ref);
        }
        int removedDocs = 0;
        for (KnowledgeDocEntity existing : docMapper.selectEnabled(teamId)) {
            if (!docRefs.contains(existing.getContentRef())) {
                docMapper.deleteByRef(teamId, existing.getContentRef());
                removedDocs++;
                log.info("文档文件已不存在，移除元数据 {}，团队 {}", existing.getContentRef(), teamId);
            }
        }

        List<String> pitfallTitles = new ArrayList<>();
        for (Path file : markdownFiles(root.resolve("pitfalls"))) {
            MarkdownDoc.Parsed parsed = MarkdownDoc.parse(readQuietly(file));
            String title = firstNonBlank(parsed.meta("title"), fileName(file));
            pitfallMapper.upsert(PitfallEntity.builder()
                    .teamId(teamId)
                    .title(title)
                    .tags(parsed.meta("tags"))
                    .symptom(MarkdownDoc.section(parsed.body(), "症状"))
                    .cause(MarkdownDoc.section(parsed.body(), "原因"))
                    .solution(MarkdownDoc.section(parsed.body(), "解法"))
                    .contentRef(root.relativize(file).toString().replace('\\', '/'))
                    .enabled(true)
                    .updatedAt(LocalDateTime.now())
                    .build());
            pitfallTitles.add(title);
        }
        int removedPitfalls = 0;
        for (PitfallEntity existing : pitfallMapper.selectEnabled(teamId)) {
            if (!pitfallTitles.contains(existing.getTitle())) {
                pitfallMapper.deleteByTitle(teamId, existing.getTitle());
                removedPitfalls++;
                log.info("坑位文件已不存在，移除元数据 {}，团队 {}", existing.getTitle(), teamId);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rootDir", root.toString());
        result.put("docs", docRefs.size());
        result.put("pitfalls", pitfallTitles.size());
        result.put("removedDocs", removedDocs);
        result.put("removedPitfalls", removedPitfalls);
        log.info("知识库同步完成，文档 {} 篇，坑位 {} 条，移除文档 {} 篇、坑位 {} 条",
                docRefs.size(), pitfallTitles.size(), removedDocs, removedPitfalls);
        return result;
    }

    public List<KnowledgeDoc> listAll() {
        return toDocs(docMapper.selectEnabled(teamScope.current()));
    }

    /**
     * 关键词、分类、标签三种维度检索，关键词为空时返回全部目录
     * limit 为空表示按团队设置里的检索条数截断，传 0 或负数表示不截断，文档库浏览用后者
     */
    public List<KnowledgeDoc> search(String keyword, String category, List<String> tags, Integer limit) {
        // 写成 if 而不是三元表达式，避免 int 与 null 混合触发拆箱空指针
        Integer size;
        if (limit == null) {
            size = teamSettingService.current().knowledgeSearchTopK();
        } else if (limit <= 0) {
            size = null;
        } else {
            size = limit;
        }
        List<String> tokens = tokenize(keyword);
        String normalized = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        List<KnowledgeDocEntity> matched = new ArrayList<>();
        for (KnowledgeDocEntity entity : docMapper.selectEnabled(teamScope.current())) {
            if (category != null && !category.isBlank() && !category.equals(entity.getCategory())) {
                continue;
            }
            if (!matchesTags(entity, tags)) {
                continue;
            }
            if (!tokens.isEmpty() && score(entity, tokens, normalized) <= 0) {
                continue;
            }
            matched.add(entity);
        }
        matched.sort(Comparator.comparingInt((KnowledgeDocEntity entity) -> score(entity, tokens, normalized)).reversed());
        List<KnowledgeDocEntity> page = size == null ? matched : matched.stream().limit(size).toList();
        return toDocs(page);
    }

    /**
     * 取单篇文档。跨团队的编号一律按不存在处理
     */
    public KnowledgeDoc getDoc(Long id) {
        KnowledgeDocEntity entity = docMapper.selectById(id);
        if (entity == null || entity.getTeamId() == null || entity.getTeamId() != teamScope.current()) {
            return null;
        }
        return toDoc(entity, uploaderNames());
    }

    /**
     * 读取正文。路径必须落在知识目录内，避免越权读取其他文件
     */
    public String readContent(KnowledgeDoc doc, Integer maxChars) {
        if (doc == null || doc.contentRef() == null || doc.contentRef().isBlank()) {
            return "";
        }
        int limit = maxChars == null || maxChars <= 0
                ? teamSettingService.current().knowledgeMaxReadChars() : maxChars;
        Path root = rootPath(teamScope.current());
        Path file = root.resolve(doc.contentRef()).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalStateException("文档路径越界：" + doc.contentRef());
        }
        try {
            String body = MarkdownDoc.parse(Files.readString(file, StandardCharsets.UTF_8)).body();
            return body.length() > limit ? body.substring(0, limit) + "\n...(正文超长已截断)" : body;
        } catch (IOException e) {
            throw new IllegalStateException("文档读取失败：" + doc.contentRef() + "，" + e.getMessage(), e);
        }
    }

    /**
     * 上传前的同名检测，供前端决定是覆盖还是另存
     */
    public Map<String, Object> checkDuplicate(String originalName) {
        long teamId = teamScope.current();
        String base = baseName(originalName);
        String ref = "docs/" + base + ".md";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fileName", base + ".md");
        result.put("exists", Files.exists(rootPath(teamId).resolve("docs").resolve(base + ".md")));
        KnowledgeDocEntity existing = docMapper.selectByRef(teamId, ref);
        result.put("title", existing == null ? null : existing.getTitle());
        result.put("category", existing == null ? null : existing.getCategory());
        result.put("docId", existing == null ? null : existing.getId());
        return result;
    }

    /**
     * 接收上传的文档：校验扩展名与大小，写入知识目录后立刻重新同步
     * 表单里的元数据是权威值，会重写成文件头，上传文件自带的文件头被覆盖
     * overwrite 为真时覆盖同名文件，否则另存为带序号的新文件
     */
    public Map<String, Object> saveUploaded(String originalName, String content, String title,
                                            String category, String tags, String source, String visibility,
                                            String uploaderUserId, boolean overwrite) {
        long teamId = teamScope.current();
        String extension = extensionOf(originalName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalStateException("只允许上传 " + String.join("、", ALLOWED_EXTENSIONS) + " 文件");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("上传内容为空");
        }
        if (content.length() > MAX_UPLOAD_CHARS) {
            throw new IllegalStateException("文档过大，请控制在 " + (MAX_UPLOAD_CHARS / 1024) + " KB 以内");
        }
        Path docsDir = rootPath(teamId).resolve("docs");
        try {
            Files.createDirectories(docsDir);
            MarkdownDoc.Parsed parsed = MarkdownDoc.parse(content);
            // firstNonBlank 全空时返回 null，这里的取值都会写进文件头并被判空，统一兜成空串
            String resolvedTitle = nullToEmpty(firstNonBlank(title, parsed.meta("title"), baseName(originalName)));
            String resolvedCategory = nullToEmpty(firstNonBlank(category, parsed.meta("category"), "未分类"));
            String resolvedTags = nullToEmpty(firstNonBlank(tags, parsed.meta("tags")));
            String resolvedSource = nullToEmpty(firstNonBlank(source, parsed.meta("source"), "上传"));
            String resolvedVisibility = nullToEmpty(firstNonBlank(visibility, parsed.meta("visibility"), "TEAM"));
            String base = baseName(originalName);
            Path target = docsDir.resolve(overwrite ? base + ".md" : uniqueFileName(docsDir, base));
            StringBuilder builder = new StringBuilder();
            builder.append("---\n");
            builder.append("title: ").append(resolvedTitle).append('\n');
            builder.append("category: ").append(resolvedCategory).append('\n');
            if (!resolvedTags.isBlank()) {
                builder.append("tags: ").append(resolvedTags).append('\n');
            }
            builder.append("source: ").append(resolvedSource).append('\n');
            builder.append("visibility: ").append(resolvedVisibility).append('\n');
            builder.append("---\n\n");
            builder.append(parsed.body()).append('\n');
            Files.writeString(target, builder.toString(), StandardCharsets.UTF_8);
            String ref = "docs/" + target.getFileName();
            log.info("文档已上传，文件 {}，标题 {}", target.getFileName(), resolvedTitle);

            Map<String, Object> sync = syncFromFiles(teamId);
            if (uploaderUserId != null && !uploaderUserId.isBlank()) {
                docMapper.updateUploader(teamId, ref, uploaderUserId, LocalDateTime.now());
            }
            Map<String, Object> result = new LinkedHashMap<>(sync);
            result.put("fileName", target.getFileName().toString());
            result.put("title", resolvedTitle);
            result.put("category", resolvedCategory);
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("文档写入失败：" + e.getMessage(), e);
        }
    }

    public List<Pitfall> listPitfalls(String keyword) {
        String normalized = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        List<PitfallEntity> matched = new ArrayList<>();
        for (PitfallEntity entity : pitfallMapper.selectEnabled(teamScope.current())) {
            if (normalized.isEmpty() || contains(entity.getTitle() + " " + entity.getTags()
                    + " " + entity.getSymptom(), normalized)) {
                matched.add(entity);
            }
        }
        return toPitfalls(matched);
    }

    /**
     * 用文本匹配坑位标签，供风险规则层调用。命中一条标签即算命中
     */
    public List<Pitfall> matchPitfalls(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String haystack = text.toLowerCase(Locale.ROOT);
        List<PitfallEntity> matched = new ArrayList<>();
        for (PitfallEntity entity : pitfallMapper.selectEnabled(teamScope.current())) {
            for (String tag : splitTags(entity.getTags())) {
                if (tag.length() >= MIN_TOKEN_LENGTH && haystack.contains(tag.toLowerCase(Locale.ROOT))) {
                    matched.add(entity);
                    break;
                }
            }
        }
        return toPitfalls(matched);
    }

    private int score(KnowledgeDocEntity entity, List<String> tokens, String normalizedKeyword) {
        String haystack = (nullToEmpty(entity.getTitle()) + " " + nullToEmpty(entity.getCategory()) + " "
                + nullToEmpty(entity.getTags()) + " " + nullToEmpty(entity.getSummary())).toLowerCase(Locale.ROOT);
        int score = 0;
        if (!normalizedKeyword.isEmpty() && haystack.contains(normalizedKeyword)) {
            score += 2;
        }
        for (String token : tokens) {
            if (haystack.contains(token)) {
                score++;
            }
        }
        return score;
    }

    private boolean matchesTags(KnowledgeDocEntity entity, List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return true;
        }
        List<String> owned = splitTags(entity.getTags());
        for (String tag : tags) {
            if (tag == null || tag.isBlank()) {
                continue;
            }
            for (String item : owned) {
                if (item.equalsIgnoreCase(tag.trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<String> tokenize(String keyword) {
        List<String> tokens = new ArrayList<>();
        if (keyword == null || keyword.isBlank()) {
            return tokens;
        }
        for (String token : TOKEN_SPLIT.split(keyword.trim().toLowerCase(Locale.ROOT))) {
            if (token.length() >= MIN_TOKEN_LENGTH && !tokens.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private List<String> splitTags(String tags) {
        List<String> result = new ArrayList<>();
        if (tags == null || tags.isBlank()) {
            return result;
        }
        for (String tag : tags.split("[,，、;；\\s]+")) {
            if (!tag.isBlank()) {
                result.add(tag.trim());
            }
        }
        return result;
    }

    private List<Path> markdownFiles(Path directory) {
        if (!Files.isDirectory(directory)) {
            log.warn("知识目录不存在，跳过同步：{}", directory);
            return List.of();
        }
        try (var stream = Files.list(directory)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("知识目录读取失败：{}，{}", directory, e.getMessage());
            return List.of();
        }
    }

    private Path rootPath(long teamId) {
        return Paths.get(teamSettingService.current(teamId).knowledgeRootDir()).toAbsolutePath().normalize();
    }

    private String readQuietly(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("知识文件读取失败：{}，{}", file, e.getMessage());
            return "";
        }
    }

    private String summarize(String body) {
        String plain = MarkdownDoc.plain(body);
        if (plain.length() <= MAX_QUERY_LENGTH * 2) {
            return plain;
        }
        return plain.substring(0, MAX_QUERY_LENGTH * 2);
    }

    private String fileName(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".md") ? name.substring(0, name.length() - 3) : name;
    }

    private String extensionOf(String name) {
        if (name == null || !name.contains(".")) {
            return "";
        }
        return name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 去掉目录分隔符与特殊字符，只保留中文、字母数字与常见符号，避免路径穿越
     */
    private String baseName(String originalName) {
        String name = originalName == null ? "" : originalName.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        String cleaned = FILE_SAFE.matcher(name).replaceAll("-").trim();
        if (cleaned.isBlank() || cleaned.equals(".") || cleaned.equals("..")) {
            cleaned = "doc-" + System.currentTimeMillis();
        }
        return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
    }

    /**
     * 同名文件不覆盖，追加序号
     */
    private String uniqueFileName(Path docsDir, String base) {
        String candidate = base + ".md";
        int index = 1;
        while (Files.exists(docsDir.resolve(candidate))) {
            candidate = base + "-" + index + ".md";
            index++;
        }
        return candidate;
    }

    /**
     * 取第一个非空值，都没有则返回 null
     */
    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    private boolean contains(String haystack, String needle) {
        if (haystack == null || haystack.isBlank()) {
            return false;
        }
        return haystack.toLowerCase(Locale.ROOT).contains(needle);
    }

    private List<KnowledgeDoc> toDocs(List<KnowledgeDocEntity> entities) {
        Map<String, String> names = uploaderNames();
        List<KnowledgeDoc> docs = new ArrayList<>();
        for (KnowledgeDocEntity entity : entities) {
            docs.add(toDoc(entity, names));
        }
        return docs;
    }

    /**
     * 上传人标识到姓名的映射，一次查询覆盖列表，避免逐篇查库
     */
    private Map<String, String> uploaderNames() {
        Map<String, String> names = new LinkedHashMap<>();
        for (SysUser user : userDirectory.all()) {
            names.put(user.userId(), firstNonBlank(user.name(), user.userId()));
        }
        return names;
    }

    private KnowledgeDoc toDoc(KnowledgeDocEntity entity, Map<String, String> uploaderNames) {
        return new KnowledgeDoc(entity.getId(), entity.getTitle(), entity.getCategory(),
                splitTags(entity.getTags()), entity.getSource(), entity.getVisibility(),
                entity.getContentRef(), entity.getSummary(), entity.getUploaderUserId(),
                uploaderNames.get(entity.getUploaderUserId()), entity.getUploadedAt(), entity.getUpdatedAt());
    }

    private List<Pitfall> toPitfalls(List<PitfallEntity> entities) {
        List<Pitfall> pitfalls = new ArrayList<>();
        for (PitfallEntity entity : entities) {
            pitfalls.add(new Pitfall(entity.getId(), entity.getTitle(), splitTags(entity.getTags()),
                    entity.getSymptom(), entity.getCause(), entity.getSolution()));
        }
        return pitfalls;
    }
}

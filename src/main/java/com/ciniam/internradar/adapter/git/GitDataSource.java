package com.ciniam.internradar.adapter.git;

import com.ciniam.internradar.adapter.WorkDataSource;
import com.ciniam.internradar.config.InternRadarProperties;
import com.ciniam.internradar.model.WorkRecord;
import com.ciniam.internradar.model.WorkRecordType;
import com.ciniam.internradar.service.PreferenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Git 数据源，用 git log 读取指定作者在指定日期的提交
 * 命令通过 ProcessBuilder 传参数数组执行，不经过 shell，参数由配置拼装
 */
@Component
public class GitDataSource implements WorkDataSource {

    private static final Logger log = LoggerFactory.getLogger(GitDataSource.class);

    private static final String SOURCE_NAME = "git";
    private static final String RECORD_MARKER = "__IR_COMMIT__";
    private static final String FIELD_SEPARATOR = "\u001f";
    private static final int FILE_SAMPLE_LIMIT = 10;
    private static final DateTimeFormatter COMMAND_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final InternRadarProperties properties;
    private final PreferenceService preferenceService;

    public GitDataSource(InternRadarProperties properties, PreferenceService preferenceService) {
        this.properties = properties;
        this.preferenceService = preferenceService;
    }

    @Override
    public String getSourceName() {
        return SOURCE_NAME;
    }

    @Override
    public boolean healthCheck() {
        ProcessResult result;
        try {
            result = execute(List.of(properties.getGit().getExecutable(), "--version"));
        } catch (Exception e) {
            log.warn("git 不可用，原因：{}", e.getMessage());
            return false;
        }
        return result.exitCode() == 0;
    }

    @Override
    public List<WorkRecord> fetch(String userId, LocalDate date) {
        List<String> authors = resolveAuthors(userId);
        if (authors.isEmpty()) {
            log.warn("用户 {} 未配置 git 作者，跳过 git 采集", userId);
            return List.of();
        }
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();
        List<WorkRecord> records = new ArrayList<>();
        for (String repository : resolveRepositories(userId)) {
            records.addAll(fetchFromRepository(repository, userId, authors, start, end));
        }
        records.sort(Comparator.comparing(WorkRecord::time));
        return records;
    }

    private List<WorkRecord> fetchFromRepository(String repository, String userId,
                                                 List<String> authors, LocalDateTime start, LocalDateTime end) {
        ProcessResult result;
        try {
            result = execute(buildCommand(repository, authors, start, end));
        } catch (Exception e) {
            log.error("仓库 {} 采集失败，原因：{}", repository, e.getMessage());
            return List.of();
        }
        if (result.exitCode() != 0) {
            log.error("仓库 {} 采集失败，git 退出码 {}，输出：{}", repository, result.exitCode(), shorten(result.output()));
            return List.of();
        }
        List<WorkRecord> records = parse(result.output(), userId, repository);
        log.info("仓库 {} 采集到 {} 条提交", repository, records.size());
        return records;
    }

    /**
     * 配置页的仓库探针：校验路径可用性、是否 git 仓库、是否浅克隆、作者的提交数与最后一次提交时间
     * 只读本地 .git，不联网、不使用任何凭据
     */
    public Map<String, Object> probe(String path, List<String> authors) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", path);
        if (path == null || path.isBlank()) {
            result.put("ok", false);
            result.put("message", "未填写仓库路径");
            return result;
        }
        java.nio.file.Path directory = Paths.get(path);
        if (!Files.isDirectory(directory)) {
            result.put("ok", false);
            result.put("message", "路径不存在或不是目录");
            return result;
        }
        ProcessResult inside = runQuietly(List.of(properties.getGit().getExecutable(), "-C", path,
                "rev-parse", "--is-inside-work-tree"));
        if (inside == null || inside.exitCode() != 0 || !inside.output().trim().equals("true")) {
            result.put("ok", false);
            result.put("message", "该目录不是 git 仓库");
            return result;
        }
        boolean shallow = Files.exists(directory.resolve(".git").resolve("shallow"));
        result.put("shallow", shallow);
        result.put("lastCommitAt", lastCommitAt(path));
        result.put("authorCommits", countAuthorCommits(path, authors));
        result.put("ok", true);
        result.put("message", shallow ? "仓库可用，但这是浅克隆，历史被截断，统计会偏少" : "仓库可用");
        return result;
    }

    private String lastCommitAt(String path) {
        ProcessResult result = runQuietly(List.of(properties.getGit().getExecutable(), "-C", path,
                "log", "--all", "-1", "--date=iso-strict", "--pretty=format:%ad"));
        if (result == null || result.exitCode() != 0) {
            return null;
        }
        String value = result.output().trim();
        return value.isEmpty() ? null : value;
    }

    private int countAuthorCommits(String path, List<String> authors) {
        if (authors == null || authors.isEmpty()) {
            return 0;
        }
        List<String> command = new ArrayList<>(List.of(properties.getGit().getExecutable(), "-C", path,
                "log", "--all", "--no-merges", "--pretty=format:%H"));
        for (String author : authors) {
            command.add("--author=" + author);
        }
        ProcessResult result = runQuietly(command);
        if (result == null || result.exitCode() != 0 || result.output().isBlank()) {
            return 0;
        }
        return (int) result.output().lines().filter(line -> !line.isBlank()).count();
    }

    private ProcessResult runQuietly(List<String> command) {
        try {
            return execute(command);
        } catch (Exception e) {
            log.warn("git 探针执行失败：{}", e.getMessage());
            return null;
        }
    }

    private List<String> buildCommand(String repository, List<String> authors,
                                      LocalDateTime start, LocalDateTime end) {
        InternRadarProperties.Git git = properties.getGit();
        List<String> command = new ArrayList<>();
        command.add(git.getExecutable());
        command.add("-c");
        command.add("core.quotepath=false");
        command.add("-C");
        command.add(repository);
        command.add("log");
        command.add("--all");
        command.add("--no-merges");
        command.add("--encoding=UTF-8");
        command.add("--date=iso-strict");
        command.add("--since=" + start.format(COMMAND_TIME));
        command.add("--until=" + end.format(COMMAND_TIME));
        command.add("--max-count=" + git.getMaxCommits());
        command.add("--pretty=format:" + RECORD_MARKER + "%H" + FIELD_SEPARATOR + "%an" + FIELD_SEPARATOR
                + "%ae" + FIELD_SEPARATOR + "%ad" + FIELD_SEPARATOR + "%s");
        command.add("--numstat");
        for (String author : authors) {
            command.add("--author=" + author);
        }
        return command;
    }

    private List<WorkRecord> parse(String output, String userId, String repository) {
        List<WorkRecord> records = new ArrayList<>();
        CommitAccumulator current = null;
        for (String line : output.split("\n")) {
            String trimmed = line.replace("\r", "");
            if (trimmed.startsWith(RECORD_MARKER)) {
                if (current != null) {
                    records.add(current.toRecord(userId, repository));
                }
                current = new CommitAccumulator(trimmed.substring(RECORD_MARKER.length()));
            } else if (current != null) {
                current.acceptStat(trimmed);
            }
        }
        if (current != null) {
            records.add(current.toRecord(userId, repository));
        }
        return records;
    }

    /**
     * 作者标识来自用户偏好，未配置时回退到配置文件里的清单
     */
    private List<String> resolveAuthors(String userId) {
        return preferenceService.gitAuthors(userId);
    }

    /**
     * 仓库列表来自个人配置，未配置时回退服务端兜底列表
     * 只读本地 .git，不执行 fetch，也不需要任何凭据
     */
    private List<String> resolveRepositories(String userId) {
        return preferenceService.gitRepositories(userId);
    }

    private ProcessResult execute(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }
        if (!process.waitFor(properties.getGit().getTimeoutSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("git 命令执行超时");
        }
        return new ProcessResult(process.exitValue(), output);
    }

    private String shorten(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    private static String field(String[] fields, int index) {
        return fields.length > index ? fields[index] : "";
    }

    private static int number(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private record ProcessResult(int exitCode, String output) {
    }

    /**
     * 累积单个提交的头信息与文件变更统计
     */
    private static final class CommitAccumulator {

        private final String hash;
        private final String author;
        private final String email;
        private final String occurredAt;
        private final String subject;
        private final List<String> fileSamples = new ArrayList<>();
        private int filesChanged;
        private int insertions;
        private int deletions;

        private CommitAccumulator(String payload) {
            String[] fields = payload.split(FIELD_SEPARATOR, -1);
            this.hash = field(fields, 0);
            this.author = field(fields, 1);
            this.email = field(fields, 2);
            this.occurredAt = field(fields, 3);
            this.subject = field(fields, 4);
        }

        private void acceptStat(String line) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                return;
            }
            String[] columns = trimmed.split("\t");
            if (columns.length < 3) {
                return;
            }
            filesChanged++;
            insertions += number(columns[0]);
            deletions += number(columns[1]);
            if (fileSamples.size() < FILE_SAMPLE_LIMIT) {
                fileSamples.add(columns[2]);
            }
        }

        private WorkRecord toRecord(String userId, String repository) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("hash", hash);
            metadata.put("shortHash", shortHash());
            metadata.put("author", author);
            metadata.put("email", email);
            metadata.put("repository", repository);
            metadata.put("filesChanged", filesChanged);
            metadata.put("insertions", insertions);
            metadata.put("deletions", deletions);
            metadata.put("fileSamples", fileSamples);
            String title = subject == null || subject.isBlank() ? "无提交说明" : subject;
            String content = title + "，涉及 " + filesChanged + " 个文件，新增 " + insertions
                    + " 行，删除 " + deletions + " 行";
            LocalDateTime time = parseTime(occurredAt);
            return new WorkRecord(userId, time, WorkRecordType.COMMIT, title, content, "done", hash, metadata);
        }

        private String shortHash() {
            return hash.length() > 7 ? hash.substring(0, 7) : hash;
        }

        private LocalDateTime parseTime(String value) {
            try {
                return OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
            } catch (Exception e) {
                log.warn("提交时间解析失败，原文：{}", value);
                return LocalDateTime.now();
            }
        }
    }
}

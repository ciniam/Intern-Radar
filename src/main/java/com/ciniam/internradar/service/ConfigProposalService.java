package com.ciniam.internradar.service;

import com.ciniam.internradar.entity.ConfigProposalEntity;
import com.ciniam.internradar.mapper.ConfigProposalMapper;
import com.ciniam.internradar.model.ConfigProposal;
import com.ciniam.internradar.model.UserPreference;
import com.ciniam.internradar.security.ForbiddenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 个人配置变更提案。问答助手只能提提案，落库必须经过用户在本人的界面确认
 * 支持的字段是白名单，值在提出与确认两个环节都会再校验一次
 */
@Service
public class ConfigProposalService {

    private static final Logger log = LoggerFactory.getLogger(ConfigProposalService.class);

    private static final int LIST_LIMIT = 50;
    private static final int VALUE_LIMIT = 1000;
    private static final int REASON_LIMIT = 480;

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPLIED = "APPLIED";
    public static final String STATUS_REJECTED = "REJECTED";

    private static final Pattern TIME = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
    private static final List<String> CATEGORIES = List.of("规范", "环境搭建", "业务背景", "排障");

    private final ConfigProposalMapper proposalMapper;
    private final PreferenceService preferenceService;

    private final TeamScope teamScope;

    public ConfigProposalService(ConfigProposalMapper proposalMapper, PreferenceService preferenceService,
                                 TeamScope teamScope) {
        this.proposalMapper = proposalMapper;
        this.preferenceService = preferenceService;
        this.teamScope = teamScope;
    }

    /**
     * 生成一条待确认提案，不写用户偏好
     */
    public ConfigProposal propose(String userId, Long conversationId, String fieldKey,
                                  String value, String reason) {
        String key = normalizeKey(fieldKey);
        String normalized = validate(key, value);
        ConfigProposalEntity entity = ConfigProposalEntity.builder()
                .teamId(teamScope.of(userId))
                .userId(userId)
                .conversationId(conversationId)
                .fieldKey(key)
                .fieldLabel(labelOf(key))
                .fieldValue(normalized)
                .reason(clip(reason, REASON_LIMIT))
                .status(STATUS_PENDING)
                .createdAt(LocalDateTime.now())
                .build();
        proposalMapper.insert(entity);
        log.info("已生成配置变更提案 {}，用户 {}，字段 {}，值 {}",
                entity.getId(), userId, key, normalized);
        return toView(entity);
    }

    public List<ConfigProposal> pending(String userId) {
        return toViews(proposalMapper.selectPending(userId, LIST_LIMIT));
    }

    public List<ConfigProposal> pendingOfConversation(String userId, Long conversationId) {
        if (conversationId == null) {
            return List.of();
        }
        return toViews(proposalMapper.selectPendingByConversation(conversationId, userId));
    }

    /**
     * 用户确认后写入个人偏好。写入走的是配置页同一条保存链路，避免两套写法
     */
    public ConfigProposal apply(String userId, Long id) {
        ConfigProposalEntity entity = require(userId, id);
        String value = validate(entity.getFieldKey(), entity.getFieldValue());
        UserPreference current = preferenceService.get(userId);
        preferenceService.save(applyField(current, entity.getFieldKey(), value));
        proposalMapper.updateStatus(id, STATUS_APPLIED, LocalDateTime.now());
        log.info("配置变更提案 {} 已应用，用户 {}，字段 {}，值 {}",
                id, userId, entity.getFieldKey(), value);
        return toView(proposalMapper.selectById(id));
    }

    public ConfigProposal reject(String userId, Long id) {
        require(userId, id);
        proposalMapper.updateStatus(id, STATUS_REJECTED, LocalDateTime.now());
        return toView(proposalMapper.selectById(id));
    }

    private ConfigProposalEntity require(String userId, Long id) {
        ConfigProposalEntity entity = id == null ? null : proposalMapper.selectById(id);
        if (entity == null) {
            throw new IllegalStateException("提案不存在：" + id);
        }
        if (!userId.equals(entity.getUserId())) {
            throw new ForbiddenException("只能处理自己的配置提案");
        }
        if (!STATUS_PENDING.equals(entity.getStatus())) {
            throw new IllegalStateException("该提案已经处理过了");
        }
        return entity;
    }

    /**
     * 字段白名单。不在这里的字段一律拒绝，模型不能借提案改别的配置
     */
    private String normalizeKey(String fieldKey) {
        if (fieldKey == null || fieldKey.isBlank()) {
            throw new IllegalArgumentException("缺少配置字段名");
        }
        String key = fieldKey.trim();
        return switch (key) {
            case "displayName", "feishuOpenId", "gitAuthors", "gitRepositories",
                 "morningPushTime", "eveningPushTime", "learnCategories", "learnTags" -> key;
            default -> throw new IllegalArgumentException("不支持的配置字段：" + key
                    + "。可改的字段只有显示名、飞书标识、提交作者标识、关联仓库、早晚推送时间、学习分类与标签");
        };
    }

    private String labelOf(String key) {
        return switch (key) {
            case "displayName" -> "显示名";
            case "feishuOpenId" -> "飞书用户标识";
            case "gitAuthors" -> "提交作者标识";
            case "gitRepositories" -> "关联的本地仓库";
            case "morningPushTime" -> "早推送时间";
            case "eveningPushTime" -> "晚推送时间";
            case "learnCategories" -> "关注的知识分类";
            case "learnTags" -> "关注的知识标签";
            default -> key;
        };
    }

    /**
     * 校验并规范化取值，列表类字段统一成逗号分隔
     */
    private String validate(String key, String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new IllegalArgumentException("配置值不能为空");
        }
        if (rawValue.length() > VALUE_LIMIT) {
            throw new IllegalArgumentException("配置值过长");
        }
        return switch (key) {
            case "displayName" -> {
                String value = rawValue.trim();
                if (value.length() > 32) {
                    throw new IllegalArgumentException("显示名请控制在 32 个字符以内");
                }
                yield value;
            }
            case "feishuOpenId" -> {
                String value = rawValue.trim();
                if (!value.startsWith("ou_") || value.length() > 64) {
                    throw new IllegalArgumentException("飞书用户标识应以 ou_ 开头");
                }
                yield value;
            }
            case "morningPushTime", "eveningPushTime" -> {
                String value = rawValue.trim();
                if (!TIME.matcher(value).matches()) {
                    throw new IllegalArgumentException("推送时间要用 HH:mm 格式，例如 09:00");
                }
                yield value;
            }
            case "gitAuthors" -> String.join(",", requireTokens(rawValue, 128, null));
            case "learnTags" -> String.join(",", requireTokens(rawValue, 64, null));
            case "learnCategories" -> String.join(",", requireTokens(rawValue, 64, CATEGORIES));
            case "gitRepositories" -> String.join(",", requireRepositories(rawValue));
            default -> throw new IllegalArgumentException("不支持的配置字段：" + key);
        };
    }

    private List<String> requireTokens(String rawValue, int maxLength, List<String> allowed) {
        List<String> tokens = new ArrayList<>();
        for (String item : split(rawValue)) {
            if (item.length() > maxLength) {
                throw new IllegalArgumentException("取值过长：" + item);
            }
            if (allowed != null && !allowed.contains(item)) {
                throw new IllegalArgumentException("取值不在允许范围内：" + item);
            }
            if (!tokens.contains(item)) {
                tokens.add(item);
            }
        }
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("至少填一个值");
        }
        return tokens;
    }

    /**
     * 仓库必须真实存在且是 git 仓库，避免把无效路径写进配置
     */
    private List<String> requireRepositories(String rawValue) {
        List<String> repositories = new ArrayList<>();
        for (String item : split(rawValue)) {
            Path path = Paths.get(item).toAbsolutePath().normalize();
            if (!Files.isDirectory(path)) {
                throw new IllegalArgumentException("目录不存在：" + item);
            }
            if (!Files.isDirectory(path.resolve(".git"))) {
                throw new IllegalArgumentException("该目录不是 git 仓库：" + item);
            }
            String value = path.toString();
            if (!repositories.contains(value)) {
                repositories.add(value);
            }
        }
        if (repositories.isEmpty()) {
            throw new IllegalArgumentException("至少填一个仓库路径");
        }
        return repositories;
    }

    private List<String> split(String rawValue) {
        List<String> items = new ArrayList<>();
        for (String item : rawValue.split("[,，、;；\\n\\s]+")) {
            if (!item.isBlank()) {
                items.add(item.trim());
            }
        }
        return items;
    }

    private UserPreference applyField(UserPreference current, String key, String value) {
        return switch (key) {
            case "displayName" -> current.withDisplayName(value);
            case "feishuOpenId" -> current.withFeishuOpenId(value);
            case "gitAuthors" -> current.withGitAuthors(split(value));
            case "gitRepositories" -> current.withGitRepositories(split(value));
            case "morningPushTime" -> current.withMorningPushTime(value);
            case "eveningPushTime" -> current.withEveningPushTime(value);
            case "learnCategories" -> current.withLearnCategories(split(value));
            case "learnTags" -> current.withLearnTags(split(value));
            default -> current;
        };
    }

    private List<ConfigProposal> toViews(List<ConfigProposalEntity> entities) {
        List<ConfigProposal> proposals = new ArrayList<>();
        for (ConfigProposalEntity entity : entities) {
            proposals.add(toView(entity));
        }
        return proposals;
    }

    private ConfigProposal toView(ConfigProposalEntity entity) {
        if (entity == null) {
            return null;
        }
        return new ConfigProposal(entity.getId(), entity.getFieldKey(), entity.getFieldLabel(),
                entity.getFieldValue(), entity.getReason(), entity.getStatus(),
                entity.getCreatedAt(), entity.getHandledAt());
    }

    private String clip(String text, int limit) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.length() > limit ? trimmed.substring(0, limit) : trimmed;
    }
}

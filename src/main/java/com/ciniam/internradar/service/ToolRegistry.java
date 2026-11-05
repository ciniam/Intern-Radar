package com.ciniam.internradar.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ciniam.internradar.config.InternRadarProperties;
import com.ciniam.internradar.mapper.WorkRecordMapper;
import com.ciniam.internradar.model.SysUser;
import com.ciniam.internradar.model.UserRole;
import com.ciniam.internradar.model.UserStatus;
import com.ciniam.internradar.tools.ConfigProposalTool;
import com.ciniam.internradar.tools.DocumentReadTool;
import com.ciniam.internradar.tools.DocumentReferenceRecorder;
import com.ciniam.internradar.tools.DocumentSearchTool;
import com.ciniam.internradar.tools.FirecrawlSearchTool;
import com.ciniam.internradar.tools.PitfallQueryTool;
import com.ciniam.internradar.tools.ReportQueryTool;
import com.ciniam.internradar.tools.TaskQueryTool;
import com.ciniam.internradar.tools.ToolScope;
import com.ciniam.internradar.tools.WorkRecordQueryTool;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具注册中心。工具按场景装配，数据范围按角色授权
 * 实习生只能查自己的数据，导师与管理员可以查名下人员
 */
@Service
public class ToolRegistry {

    public static final String SCENE_LEARN = "LEARN";
    public static final String SCENE_REPORT = "REPORT";
    public static final String SCENE_RISK = "RISK";

    private final KnowledgeService knowledgeService;
    private final ReportService reportService;
    private final RiskService riskService;
    private final WorkRecordMapper workRecordMapper;
    private final WorkRecordVisibility visibility;
    private final ObjectMapper objectMapper;
    private final UserDirectory userDirectory;
    private final SystemConfigService systemConfigService;
    private final InternRadarProperties properties;
    private final ConfigProposalService configProposalService;

    public ToolRegistry(KnowledgeService knowledgeService,
                        ReportService reportService,
                        RiskService riskService,
                        WorkRecordMapper workRecordMapper,
                        WorkRecordVisibility visibility,
                        ObjectMapper objectMapper,
                        UserDirectory userDirectory,
                        SystemConfigService systemConfigService,
                        InternRadarProperties properties,
                        ConfigProposalService configProposalService) {
        this.knowledgeService = knowledgeService;
        this.reportService = reportService;
        this.riskService = riskService;
        this.workRecordMapper = workRecordMapper;
        this.visibility = visibility;
        this.objectMapper = objectMapper;
        this.userDirectory = userDirectory;
        this.systemConfigService = systemConfigService;
        this.properties = properties;
        this.configProposalService = configProposalService;
    }

    /**
     * 一次运行用到的工具与授权范围，范围随工具一起交给业务层留档
     */
    public record ToolSet(ToolCallback[] callbacks, ToolScope scope, DocumentReferenceRecorder recorder) {
    }

    public ToolSet forScene(String scene, String ownerUserId) {
        return forScene(scene, ownerUserId, null);
    }

    /**
     * 按场景装配工具。数据范围以运行归属人的角色为准，导师与管理员可查他人
     * conversationId 只有问答场景用得上，配置提案要靠它挂到当前会话上
     */
    public ToolSet forScene(String scene, String ownerUserId, DocumentReferenceRecorder recorder) {
        return forScene(scene, ownerUserId, recorder, null);
    }

    public ToolSet forScene(String scene, String ownerUserId, DocumentReferenceRecorder recorder,
                            Long conversationId) {
        SysUser viewer = viewer(ownerUserId);
        ToolScope scope = ToolScope.of(viewer.userId(), viewer.canViewTeam());
        DocumentReferenceRecorder referenceRecorder =
                recorder == null ? new DocumentReferenceRecorder() : recorder;
        ToolCallback[] callbacks = switch (scene) {
            case SCENE_LEARN -> learnTools(viewer.userId(), conversationId, referenceRecorder);
            case SCENE_REPORT -> ToolCallbacks.from(
                    new WorkRecordQueryTool(workRecordMapper, visibility, scope),
                    new TaskQueryTool(riskService, objectMapper, scope),
                    new ReportQueryTool(reportService, scope));
            case SCENE_RISK -> ToolCallbacks.from(
                    new WorkRecordQueryTool(workRecordMapper, visibility, scope),
                    new TaskQueryTool(riskService, objectMapper, scope),
                    new PitfallQueryTool(knowledgeService));
            default -> new ToolCallback[0];
        };
        return new ToolSet(callbacks, scope, referenceRecorder);
    }

    /**
     * 学习场景：知识库检索、读取与配置提案常驻，联网搜索只在配了密钥时才给模型
     */
    private ToolCallback[] learnTools(String ownerUserId, Long conversationId, DocumentReferenceRecorder recorder) {
        List<Object> tools = new ArrayList<>();
        tools.add(new DocumentSearchTool(knowledgeService, recorder));
        tools.add(new DocumentReadTool(knowledgeService, recorder));
        tools.add(new ConfigProposalTool(configProposalService, ownerUserId, conversationId));
        if (systemConfigService.searchConfigured()) {
            InternRadarProperties.Search search = properties.getSearch();
            tools.add(new FirecrawlSearchTool(systemConfigService.searchApiKey(), search.getBaseUrl(),
                    search.getTimeoutSeconds(), search.getLimit(), objectMapper));
        }
        return ToolCallbacks.from(tools.toArray());
    }

    /**
     * 用户目录里查不到时按实习生兜底，保证定时任务也能跑起来
     */
    private SysUser viewer(String ownerUserId) {
        SysUser user = userDirectory.byUserId(ownerUserId);
        if (user != null) {
            return user;
        }
        return new SysUser(ownerUserId, TeamScope.FALLBACK_TEAM_ID, ownerUserId, UserRole.INTERN,
                null, null, null, UserStatus.ACTIVE);
    }
}

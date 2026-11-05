package com.ciniam.internradar.tools;

import com.ciniam.internradar.model.ConfigProposal;
import com.ciniam.internradar.service.ConfigProposalService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 配置变更提案工具。只生成待确认提案，绝不直接写库，用户在自己的界面上点确认才生效
 */
public class ConfigProposalTool {

    private static final String FIELDS = "displayName（显示名）、feishuOpenId（飞书标识）、"
            + "gitAuthors（提交作者标识）、gitRepositories（关联的本地仓库路径）、"
            + "morningPushTime 与 eveningPushTime（推送时间，HH:mm）、"
            + "learnCategories（知识分类，只允许 规范、环境搭建、业务背景、排障）、learnTags（知识标签）";

    private final ConfigProposalService proposalService;
    private final String ownerUserId;
    private final Long conversationId;

    public ConfigProposalTool(ConfigProposalService proposalService, String ownerUserId, Long conversationId) {
        this.proposalService = proposalService;
        this.ownerUserId = ownerUserId;
        this.conversationId = conversationId;
    }

    @Tool(description = "为当前用户整理一条个人配置变更提案，用户在界面上确认后才会真正写入配置。"
            + "当用户让你帮他配置仓库、作者标识、推送时间、关注分类等信息时使用这个工具，"
            + "不要在没调用它的情况下声称已经改好配置")
    public String proposeConfigChange(
            @ToolParam(description = "配置字段名，可取值：" + FIELDS)
            String fieldKey,
            @ToolParam(description = "要写入的值。列表类字段用逗号分隔，例如 d:/mycode/project-a,d:/mycode/project-b")
            String value,
            @ToolParam(description = "为什么建议这样改，一句话说明依据")
            String reason) {
        try {
            ConfigProposal proposal = proposalService.propose(ownerUserId, conversationId, fieldKey, value, reason);
            return "已生成待确认提案（编号 " + proposal.id() + "）：" + proposal.fieldLabel()
                    + " 将改为 " + proposal.fieldValue()
                    + "。请告诉用户在对话界面上点「应用」后才会真正生效，现在还没有写入配置。";
        } catch (IllegalArgumentException e) {
            return "提案没有生成：" + e.getMessage() + "。请确认取值后重试。";
        } catch (Exception e) {
            return "提案生成失败：" + e.getMessage();
        }
    }
}

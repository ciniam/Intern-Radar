package com.ciniam.internradar.tools;

import com.ciniam.internradar.model.KnowledgeDoc;
import com.ciniam.internradar.service.KnowledgeService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

/**
 * 知识库检索工具。只返回元数据，正文由读取工具按编号获取
 */
public class DocumentSearchTool {

    private static final int MAX_KEYWORD_LENGTH = 120;

    private final KnowledgeService knowledgeService;
    private final DocumentReferenceRecorder recorder;

    public DocumentSearchTool(KnowledgeService knowledgeService, DocumentReferenceRecorder recorder) {
        this.knowledgeService = knowledgeService;
        this.recorder = recorder;
    }

    @Tool(description = "检索团队知识库，返回文档编号、标题、分类、标签与摘要。"
            + "关键词留空时返回全部文档目录，适合先看看知识库里有哪些文档")
    public String searchDocuments(
            @ToolParam(description = "检索关键词，多个关键词用空格分隔，例如：端口占用 启动失败。留空表示列出全部文档")
            String keyword,
            @ToolParam(description = "分类过滤，例如：规范、环境搭建、业务背景、排障。留空表示不过滤")
            String category,
            @ToolParam(description = "标签过滤，多个标签用逗号分隔。留空表示不过滤")
            String tags) {
        String safeKeyword = truncate(keyword);
        List<KnowledgeDoc> docs = knowledgeService.search(safeKeyword, category, splitTags(tags), null);
        // 只统计带关键词的检索，浏览目录不算命中，否则命中率会被刷成满值
        boolean keywordSearch = safeKeyword != null && !safeKeyword.isBlank();
        if (keywordSearch) {
            recorder.recordSearch(docs);
        }
        if (docs.isEmpty()) {
            return "没有命中任何文档。可以换更短的关键词再试，或不带关键词查看全部文档目录。";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("命中 ").append(docs.size()).append(" 篇文档，编号可用于读取正文：\n");
        for (KnowledgeDoc doc : docs) {
            builder.append('[').append(doc.id()).append("] ").append(doc.title()).append('\n');
            builder.append("    分类：").append(doc.category())
                    .append("｜标签：").append(String.join("、", doc.tags()))
                    .append("｜来源：").append(doc.source() == null ? "" : doc.source()).append('\n');
            builder.append("    摘要：").append(doc.summary() == null ? "" : doc.summary()).append('\n');
        }
        return builder.toString();
    }

    private String truncate(String keyword) {
        if (keyword == null) {
            return null;
        }
        String trimmed = keyword.trim();
        return trimmed.length() > MAX_KEYWORD_LENGTH ? trimmed.substring(0, MAX_KEYWORD_LENGTH) : trimmed;
    }

    private List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return List.of(tags.split("[,，、;；\\s]+"));
    }
}

package com.ciniam.internradar.tools;

import com.ciniam.internradar.model.Pitfall;
import com.ciniam.internradar.service.KnowledgeService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

/**
 * 坑位查询工具。按关键词或直接用一段工作记录文本去匹配已知坑位
 */
public class PitfallQueryTool {

    private static final int MAX_LINES = 10;

    private final KnowledgeService knowledgeService;

    public PitfallQueryTool(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @Tool(description = "检索团队已记录的坑位，返回症状、原因与解法。关键词留空时返回全部坑位")
    public String queryPitfalls(
            @ToolParam(description = "检索关键词，可以是一段工作描述或报错特征，留空表示列出全部坑位")
            String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return list(knowledgeService.listPitfalls(null));
        }
        List<Pitfall> matched = knowledgeService.matchPitfalls(keyword);
        if (matched.isEmpty()) {
            return list(knowledgeService.listPitfalls(keyword));
        }
        return list(matched);
    }

    private String list(List<Pitfall> pitfalls) {
        if (pitfalls.isEmpty()) {
            return "没有匹配到已记录的坑位。";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("匹配到 ").append(pitfalls.size()).append(" 条坑位：\n");
        int index = 0;
        for (Pitfall pitfall : pitfalls) {
            if (index++ >= MAX_LINES) {
                break;
            }
            builder.append("- [").append(pitfall.id()).append("] ").append(pitfall.title())
                    .append("｜标签 ").append(String.join("、", pitfall.tags())).append('\n');
            if (pitfall.symptom() != null && !pitfall.symptom().isBlank()) {
                builder.append("  症状：").append(clip(pitfall.symptom())).append('\n');
            }
            if (pitfall.solution() != null && !pitfall.solution().isBlank()) {
                builder.append("  解法：").append(clip(pitfall.solution())).append('\n');
            }
        }
        return builder.toString();
    }

    private String clip(String text) {
        String plain = text.replaceAll("\\s+", " ").trim();
        return plain.length() > 240 ? plain.substring(0, 240) + "…" : plain;
    }
}

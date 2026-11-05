package com.ciniam.internradar.tools;

import com.ciniam.internradar.model.KnowledgeDoc;
import com.ciniam.internradar.service.KnowledgeService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 知识文档读取工具。编号来自检索结果，正文按需从文件读取
 */
public class DocumentReadTool {

    private final KnowledgeService knowledgeService;
    private final DocumentReferenceRecorder recorder;

    public DocumentReadTool(KnowledgeService knowledgeService, DocumentReferenceRecorder recorder) {
        this.knowledgeService = knowledgeService;
        this.recorder = recorder;
    }

    @Tool(description = "按编号读取知识文档的完整正文。编号来自检索结果里的方括号数字")
    public String readDocument(
            @ToolParam(description = "文档编号，例如 12") Long docId) {
        if (docId == null) {
            return "请提供文档编号，编号来自检索结果。";
        }
        KnowledgeDoc doc = knowledgeService.getDoc(docId);
        if (doc == null) {
            return "没有找到编号为 " + docId + " 的文档，请重新检索。";
        }
        String body = knowledgeService.readContent(doc, null);
        recorder.recordRead(docId, body.length());
        return "文档 [" + doc.id() + "] " + doc.title()
                + "\n分类：" + doc.category()
                + "｜标签：" + String.join("、", doc.tags())
                + "\n--- 正文开始 ---\n" + body + "\n--- 正文结束 ---";
    }
}

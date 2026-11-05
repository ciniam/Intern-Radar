package com.ciniam.internradar.tools;

import com.ciniam.internradar.model.KnowledgeDoc;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 记录一次问答里智能体检索到与读取过的文档，用于返回引用列表与统计检索命中率
 */
public class DocumentReferenceRecorder {

    private final Set<Long> matched = new LinkedHashSet<>();
    private final Set<Long> read = new LinkedHashSet<>();
    private int readChars;
    private int searchCalls;

    public void recordSearch(List<KnowledgeDoc> docs) {
        searchCalls++;
        for (KnowledgeDoc doc : docs) {
            if (doc != null && doc.id() != null) {
                matched.add(doc.id());
            }
        }
    }

    public void recordRead(Long docId, int chars) {
        if (docId != null) {
            read.add(docId);
        }
        readChars += Math.max(chars, 0);
    }

    public List<Long> matchedIds() {
        return new ArrayList<>(matched);
    }

    public List<Long> readIds() {
        return new ArrayList<>(read);
    }

    public int matchedCount() {
        return matched.size();
    }

    public int readCount() {
        return read.size();
    }

    public int readChars() {
        return readChars;
    }

    public int searchCalls() {
        return searchCalls;
    }
}

package com.ciniam.internradar.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 极简 Markdown 解析：读取文件头部的 key: value 元数据与「## 小节」正文
 * 知识文档以文件为唯一事实来源，这里只做约定格式的解析，不引入额外依赖
 */
public final class MarkdownDoc {

    private static final String DELIMITER = "---";
    private static final String HEADING = "## ";

    private MarkdownDoc() {
    }

    public record Parsed(Map<String, String> meta, String body) {

        public String meta(String key) {
            String value = meta.get(key);
            return value == null ? "" : value;
        }
    }

    public static Parsed parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Parsed(Map.of(), "");
        }
        // 带 BOM 的文件直接解析会让首个标题或字段失效，先去掉
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        if (!text.startsWith(DELIMITER)) {
            return new Parsed(Map.of(), text.trim());
        }
        int headEnd = text.indexOf("\n" + DELIMITER, DELIMITER.length());
        if (headEnd < 0) {
            return new Parsed(Map.of(), text.trim());
        }
        int firstLineEnd = text.indexOf('\n');
        String head = text.substring(firstLineEnd + 1, headEnd);
        Map<String, String> meta = new LinkedHashMap<>();
        for (String line : head.split("\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (!key.isEmpty() && !value.isEmpty()) {
                meta.put(key, value);
            }
        }
        String body = text.substring(headEnd + DELIMITER.length() + 1).trim();
        return new Parsed(meta, body);
    }

    /**
     * 取出某个二级小节的内容，遇到下一个二级标题即结束
     */
    public static String section(String body, String heading) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String marker = HEADING + heading;
        StringBuilder builder = new StringBuilder();
        boolean inside = false;
        for (String line : body.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(HEADING)) {
                inside = trimmed.equals(marker);
                continue;
            }
            if (inside) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString().trim();
    }

    /**
     * 去掉常见 Markdown 标记，用于生成摘要
     */
    public static String plain(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String line : body.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("```")) {
                continue;
            }
            builder.append(trimmed.replaceAll("[*`>|]", "").trim()).append(' ');
        }
        return builder.toString().trim();
    }
}

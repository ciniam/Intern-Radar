package com.ciniam.internradar.util;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 修复模型返回的近似 JSON
 * 常见缺陷是数组或对象漏写闭合符号、字符串外带解释性文字、尾随逗号
 * 只做结构性修补，不猜测缺失的内容
 */
public final class JsonTextRepair {

    private JsonTextRepair() {
    }

    public static String repair(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String cleaned = stripFence(text);
        int start = cleaned.indexOf('{');
        if (start < 0) {
            return cleaned;
        }
        StringBuilder builder = new StringBuilder();
        Deque<Character> closers = new ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < cleaned.length(); i++) {
            char current = cleaned.charAt(i);
            if (inString) {
                builder.append(current);
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
                builder.append(current);
                continue;
            }
            if (current == '{' || current == '[') {
                closers.push(current == '{' ? '}' : ']');
                builder.append(current);
                continue;
            }
            if (current == '}' || current == ']') {
                while (!closers.isEmpty() && closers.peek() != current) {
                    builder.append(closers.pop());
                }
                if (!closers.isEmpty()) {
                    closers.pop();
                }
                builder.append(current);
                if (closers.isEmpty()) {
                    return removeTrailingCommas(builder.toString());
                }
                continue;
            }
            builder.append(current);
        }
        if (inString) {
            builder.append('"');
        }
        while (!closers.isEmpty()) {
            builder.append(closers.pop());
        }
        return removeTrailingCommas(builder.toString());
    }

    private static String stripFence(String text) {
        String cleaned = text.trim();
        if (!cleaned.startsWith("```")) {
            return cleaned;
        }
        int firstLineEnd = cleaned.indexOf('\n');
        if (firstLineEnd < 0) {
            return cleaned;
        }
        String body = cleaned.substring(firstLineEnd + 1);
        int fenceEnd = body.lastIndexOf("```");
        return fenceEnd < 0 ? body : body.substring(0, fenceEnd);
    }

    private static String removeTrailingCommas(String text) {
        return text.replaceAll(",\\s*([}\\]])", "$1");
    }
}

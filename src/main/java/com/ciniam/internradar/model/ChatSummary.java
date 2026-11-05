package com.ciniam.internradar.model;

import java.util.List;

/**
 * 聊天记录摘要。原始消息只用于本次生成，不落库
 */
public record ChatSummary(
        String summary,
        List<String> keyPoints,
        List<String> blockers
) {
}

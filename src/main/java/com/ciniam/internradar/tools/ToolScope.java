package com.ciniam.internradar.tools;

/**
 * 工具的数据范围。代理只拿到被授权的查询范围，模型传了范围外的标识会被工具直接挡回
 */
public record ToolScope(String ownerUserId, boolean crossUser) {

    public static ToolScope of(String ownerUserId, boolean crossUser) {
        return new ToolScope(ownerUserId, crossUser);
    }

    /**
     * 目标留空表示查自己，越权返回 null
     */
    public String resolve(String requested) {
        if (requested == null || requested.isBlank()) {
            return ownerUserId;
        }
        String trimmed = requested.trim();
        if (trimmed.equals(ownerUserId) || crossUser) {
            return trimmed;
        }
        return null;
    }

    public static String denied() {
        return "无权查询该人员的数据，只能查询你自己的数据，请把人员标识改为自己。";
    }
}

package com.ciniam.internradar.model;

import java.util.Locale;

/**
 * 登录设备。浏览器标识归纳成一句可读的设备描述，列表里直接展示
 */
public record LoginDevice(String device, String ip) {

    public static LoginDevice of(String userAgent, String ip) {
        return new LoginDevice(summarize(userAgent), normalizeIp(ip));
    }

    public static String summarize(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "未知设备";
        }
        String lower = userAgent.toLowerCase(Locale.ROOT);
        if (lower.contains("postman") || lower.contains("curl") || lower.contains("httpclient")) {
            return "接口工具";
        }
        StringBuilder builder = new StringBuilder();
        if (lower.contains("edg/")) {
            builder.append("Edge");
        } else if (lower.contains("chrome/")) {
            builder.append("Chrome");
        } else if (lower.contains("firefox/")) {
            builder.append("Firefox");
        } else if (lower.contains("safari/")) {
            builder.append("Safari");
        } else {
            builder.append("浏览器");
        }
        if (lower.contains("windows")) {
            builder.append(" · Windows");
        } else if (lower.contains("mac os")) {
            builder.append(" · macOS");
        } else if (lower.contains("android")) {
            builder.append(" · Android");
        } else if (lower.contains("iphone") || lower.contains("ipad")) {
            builder.append(" · iOS");
        } else if (lower.contains("linux")) {
            builder.append(" · Linux");
        }
        return builder.toString();
    }

    private static String normalizeIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        String value = ip.trim();
        return "0:0:0:0:0:0:0:1".equals(value) ? "127.0.0.1" : value;
    }
}

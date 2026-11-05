package com.ciniam.internradar.model;

import java.util.List;
import java.util.Map;

/**
 * 风险告警，规则层直接产出，语义层补充后可再次产出
 */
public record RiskAlert(
        String userId,
        RiskLevel level,
        String type,
        String fingerprint,
        String title,
        List<String> evidence,
        String suggestion,
        Map<String, Object> extra
) {
}

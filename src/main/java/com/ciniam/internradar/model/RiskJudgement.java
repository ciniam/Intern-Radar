package com.ciniam.internradar.model;

import java.util.List;

/**
 * 智能体对规则候选告警的复核结论。keep 为假表示这条属于误报，直接丢弃
 */
public record RiskJudgement(
        List<Item> items
) {

    public record Item(
            String fingerprint,
            Boolean keep,
            String reason,
            String suggestion
    ) {
    }
}

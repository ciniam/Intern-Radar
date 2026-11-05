package com.ciniam.internradar.model;

import java.util.List;

/**
 * 坑位记录，标签用于规则命中
 */
public record Pitfall(
        Long id,
        String title,
        List<String> tags,
        String symptom,
        String cause,
        String solution
) {
}

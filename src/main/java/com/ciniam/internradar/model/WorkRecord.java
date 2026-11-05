package com.ciniam.internradar.model;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 统一的工作记录，所有数据源采集结果都归一到这个结构
 * externalId 为数据源内的唯一标识，用于落库去重
 */
public record WorkRecord(
        String userId,
        LocalDateTime time,
        WorkRecordType type,
        String title,
        String content,
        String status,
        String externalId,
        Map<String, Object> metadata
) {
}

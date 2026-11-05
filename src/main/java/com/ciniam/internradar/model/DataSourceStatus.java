package com.ciniam.internradar.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 数据源状态，只读展示用
 */
public record DataSourceStatus(
        String source,
        String label,
        boolean configured,
        String detail,
        LocalDate lastStatDate,
        LocalDateTime lastCollectAt,
        Integer lastFetched,
        Integer lastInserted,
        Boolean lastSuccess,
        String lastError
) {
}

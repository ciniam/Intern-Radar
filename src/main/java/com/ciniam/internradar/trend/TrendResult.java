package com.ciniam.internradar.trend;

import java.util.List;

/**
 * 趋势分析结果
 */
public record TrendResult(
        int count,
        double mean,
        double slope,
        String trend,
        double firstHalfAvg,
        double secondHalfAvg,
        List<AnomalyPoint> anomalies,
        String summary
) {

    public record AnomalyPoint(String date, double value, double zscore) {
    }

    public boolean configured() {
        return count > 0;
    }
}

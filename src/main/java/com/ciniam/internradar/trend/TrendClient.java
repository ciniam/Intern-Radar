package com.ciniam.internradar.trend;

import com.ciniam.internradar.config.InternRadarProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 趋势统计服务的客户端。未配置地址时直接返回空结果，不影响主流程
 */
@Component
public class TrendClient {

    private static final Logger log = LoggerFactory.getLogger(TrendClient.class);

    private final InternRadarProperties properties;
    private final RestClient restClient;

    public TrendClient(InternRadarProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(properties.getTrend().getTimeoutSeconds());
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public boolean configured() {
        String baseUrl = properties.getTrend().getBaseUrl();
        return baseUrl != null && !baseUrl.isBlank();
    }

    public TrendResult analyze(List<Map<String, Object>> series, double zThreshold) {
        if (!configured() || series.isEmpty()) {
            return new TrendResult(0, 0, 0, "unknown", 0, 0, List.of(), "未启用趋势统计或序列为空");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("series", series);
        body.put("z_threshold", zThreshold);
        try {
            Map<?, ?> response = restClient.post()
                    .uri(properties.getTrend().getBaseUrl() + "/analyze/trend")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            return convert(response);
        } catch (Exception e) {
            log.warn("趋势统计服务调用失败，原因：{}", e.getMessage());
            return new TrendResult(0, 0, 0, "unknown", 0, 0, List.of(), "趋势统计服务不可用：" + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private TrendResult convert(Map<?, ?> response) {
        if (response == null) {
            return new TrendResult(0, 0, 0, "unknown", 0, 0, List.of(), "趋势统计服务返回空响应");
        }
        List<TrendResult.AnomalyPoint> anomalies = new ArrayList<>();
        Object rawAnomalies = response.get("anomalies");
        if (rawAnomalies instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    anomalies.add(new TrendResult.AnomalyPoint(
                            String.valueOf(map.get("date")),
                            toDouble(map.get("value")),
                            toDouble(map.get("zscore"))));
                }
            }
        }
        return new TrendResult(
                toInt(response.get("count")),
                toDouble(response.get("mean")),
                toDouble(response.get("slope")),
                text(response.get("trend"), "unknown"),
                toDouble(response.get("firstHalfAvg")),
                toDouble(response.get("secondHalfAvg")),
                anomalies,
                text(response.get("summary"), ""));
    }

    private String text(Object value, String defaultValue) {
        return value == null ? defaultValue : String.valueOf(value);
    }

    private double toDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0d;
    }

    private int toInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}

package com.ciniam.internradar.service;

import com.ciniam.internradar.entity.CollectLogEntity;
import com.ciniam.internradar.entity.DocRetrieveLogEntity;
import com.ciniam.internradar.entity.LlmCallLogEntity;
import com.ciniam.internradar.mapper.CollectLogMapper;
import com.ciniam.internradar.mapper.DocRetrieveLogMapper;
import com.ciniam.internradar.mapper.LlmCallLogMapper;
import com.ciniam.internradar.mapper.PushLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 可观测性埋点：大模型调用、数据采集、推送成功率
 * 写埋点失败不影响主流程
 */
@Service
public class ObserveService {

    private static final Logger log = LoggerFactory.getLogger(ObserveService.class);

    private final LlmCallLogMapper llmCallLogMapper;
    private final CollectLogMapper collectLogMapper;
    private final PushLogMapper pushLogMapper;
    private final DocRetrieveLogMapper docRetrieveLogMapper;

    private final TeamScope teamScope;

    public ObserveService(LlmCallLogMapper llmCallLogMapper,
                          CollectLogMapper collectLogMapper,
                          PushLogMapper pushLogMapper,
                          DocRetrieveLogMapper docRetrieveLogMapper,
                          TeamScope teamScope) {
        this.llmCallLogMapper = llmCallLogMapper;
        this.collectLogMapper = collectLogMapper;
        this.pushLogMapper = pushLogMapper;
        this.docRetrieveLogMapper = docRetrieveLogMapper;
        this.teamScope = teamScope;
    }

    public void recordLlm(String userId, String scene, String model, int promptTokens, int completionTokens,
                          long durationMs, boolean success, String errorMsg) {
        try {
            llmCallLogMapper.insert(LlmCallLogEntity.builder()
                    .teamId(teamScope.of(userId))
                    .userId(userId)
                    .scene(scene)
                    .model(model)
                    .promptTokens(promptTokens)
                    .completionTokens(completionTokens)
                    .durationMs(durationMs)
                    .success(success)
                    .errorMsg(truncate(errorMsg))
                    .build());
        } catch (Exception e) {
            log.warn("大模型埋点写入失败，场景 {}", scene, e);
        }
    }

    public void recordCollect(String userId, String source, LocalDate statDate, int fetched, int inserted,
                              long durationMs, boolean success, String errorMsg) {
        try {
            collectLogMapper.insert(CollectLogEntity.builder()
                    .teamId(teamScope.of(userId))
                    .userId(userId)
                    .source(source)
                    .statDate(statDate)
                    .fetched(fetched)
                    .inserted(inserted)
                    .durationMs(durationMs)
                    .success(success)
                    .errorMsg(truncate(errorMsg))
                    .build());
        } catch (Exception e) {
            log.warn("采集埋点写入失败，数据源 {}", source, e);
        }
    }

    public void recordRetrieve(String userId, String scene, String queryText, int hitCount, int readCount,
                               int readChars, long durationMs, boolean success, String errorMsg) {
        try {
            docRetrieveLogMapper.insert(DocRetrieveLogEntity.builder()
                    .teamId(teamScope.of(userId))
                    .userId(userId)
                    .scene(scene)
                    .queryText(truncate(queryText))
                    .hitCount(hitCount)
                    .readCount(readCount)
                    .readChars(readChars)
                    .durationMs(durationMs)
                    .success(success)
                    .errorMsg(truncate(errorMsg))
                    .build());
        } catch (Exception e) {
            log.warn("检索埋点写入失败，场景 {}", scene, e);
        }
    }

    public Map<String, Object> summary(int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("since", since);
        result.put("llm", llmCallLogMapper.statByScene(since));
        result.put("collect", collectLogMapper.statBySource(since));
        result.put("push", pushLogMapper.statByChannel(since));
        result.put("doc", docRetrieveLogMapper.statByScene(since));
        return result;
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > 480 ? text.substring(0, 480) : text;
    }
}

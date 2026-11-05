package com.ciniam.internradar.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ciniam.internradar.adapter.WorkDataSource;
import com.ciniam.internradar.entity.WorkRecordEntity;
import com.ciniam.internradar.mapper.WorkRecordMapper;
import com.ciniam.internradar.model.WorkRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 采集编排：遍历所有数据源，每个数据源独立采、独立落库、独立埋点
 * 单个数据源失败不影响其他数据源，失败原因一并返回
 */
@Service
public class WorkCollectService {

    private static final Logger log = LoggerFactory.getLogger(WorkCollectService.class);

    private final TeamScope teamScope;

    private final List<WorkDataSource> dataSources;
    private final WorkRecordMapper workRecordMapper;
    private final ObserveService observeService;
    private final ObjectMapper objectMapper;

    public WorkCollectService(List<WorkDataSource> dataSources,
                             WorkRecordMapper workRecordMapper,
                             ObserveService observeService,
                             ObjectMapper objectMapper,
                             TeamScope teamScope) {
        this.dataSources = dataSources;
        this.workRecordMapper = workRecordMapper;
        this.observeService = observeService;
        this.objectMapper = objectMapper;
        this.teamScope = teamScope;
    }

    public CollectResult collect(String userId, LocalDate date) {
        List<String> failures = new ArrayList<>();
        int totalFetched = 0;
        int totalInserted = 0;
        for (WorkDataSource dataSource : dataSources) {
            long begin = System.currentTimeMillis();
            int fetched = 0;
            int inserted = 0;
            String error = null;
            try {
                List<WorkRecord> records = dataSource.fetch(userId, date);
                fetched = records.size();
                if (!records.isEmpty()) {
                    List<WorkRecordEntity> entities = new ArrayList<>();
                    for (WorkRecord record : records) {
                        entities.add(toEntity(dataSource.getSourceName(), record));
                    }
                    inserted = workRecordMapper.insertBatch(entities);
                }
                log.info("采集完成，数据源 {}，用户 {}，日期 {}，记录数 {}，新增 {}",
                        dataSource.getSourceName(), userId, date, fetched, inserted);
            } catch (Exception e) {
                error = e.getMessage();
                failures.add(dataSource.getSourceName() + "：" + error);
                log.error("采集失败，数据源 {}，用户 {}，日期 {}", dataSource.getSourceName(), userId, date, e);
            }
            observeService.recordCollect(userId, dataSource.getSourceName(), date, fetched, inserted,
                    System.currentTimeMillis() - begin, error == null, error);
            totalFetched += fetched;
            totalInserted += inserted;
        }
        return new CollectResult(totalFetched, totalInserted, Math.max(0, totalFetched - totalInserted), failures);
    }

    private WorkRecordEntity toEntity(String sourceName, WorkRecord record) {
        return WorkRecordEntity.builder()
                .teamId(teamScope.of(record.userId()))
                .userId(record.userId())
                .source(sourceName)
                .type(record.type().name())
                .title(record.title())
                .content(record.content())
                .status(record.status())
                .occurredAt(record.time())
                .externalId(record.externalId())
                .metadata(toJson(record.metadata()))
                .build();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("元数据序列化失败，原因：{}", e.getMessage());
            return "{}";
        }
    }

    /**
     * 采集结果。fetched 为本次拉到的条数，inserted 为实际入库条数
     * duplicated 表示因已存在而去重的条数，避免看到 inserted 为 0 时误判为没生效
     */
    public record CollectResult(int fetched, int inserted, int duplicated, List<String> failures) {
    }
}

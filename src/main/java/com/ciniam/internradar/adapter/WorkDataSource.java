package com.ciniam.internradar.adapter;

import com.ciniam.internradar.model.WorkRecord;

import java.time.LocalDate;
import java.util.List;

/**
 * 数据源统一接口，新增数据源只需实现本接口
 */
public interface WorkDataSource {

    String getSourceName();

    List<WorkRecord> fetch(String userId, LocalDate date);

    boolean healthCheck();
}

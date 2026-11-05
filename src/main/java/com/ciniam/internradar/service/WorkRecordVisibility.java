package com.ciniam.internradar.service;

import com.ciniam.internradar.entity.WorkRecordEntity;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 工作记录的可见性规则。本人看全部，导师与管理员看不到聊天采集的摘要
 */
@Service
public class WorkRecordVisibility {

    public static final String CHAT_SOURCE = "feishu-chat";

    public List<WorkRecordEntity> visibleTo(boolean viewerIsOwner, List<WorkRecordEntity> records) {
        if (viewerIsOwner || records == null || records.isEmpty()) {
            return records;
        }
        return records.stream().filter(record -> !isChat(record)).toList();
    }

    public boolean isChat(WorkRecordEntity record) {
        return record != null && record.getSource() != null
                && CHAT_SOURCE.equalsIgnoreCase(record.getSource().trim());
    }

    public int countVisible(boolean viewerIsOwner, List<WorkRecordEntity> records) {
        return visibleTo(viewerIsOwner, records).size();
    }
}

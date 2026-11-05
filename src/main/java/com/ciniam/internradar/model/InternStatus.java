package com.ciniam.internradar.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 导师视角下的单个实习生状态。
 * daily 是这个窗口内每天的记录条数（不含聊天摘要），看板热力图直接用它，省掉按人再请求一轮
 */
public record InternStatus(
        String userId,
        String name,
        String mentorUserId,
        int recordCount,
        int openTaskCount,
        int openAlertCount,
        String highestRisk,
        LocalDateTime lastActiveAt,
        Map<String, Integer> alertCounts,
        List<String> alertTitles,
        List<DayCount> daily
) {

    public record DayCount(String date, int count) {
    }
}

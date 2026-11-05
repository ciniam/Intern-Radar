package com.ciniam.internradar.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * push_log 表映射对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PushLogEntity {

    private Long id;
    private Long teamId;
    private String userId;
    private String channel;
    private String type;
    private String content;
    private String status;
    private String errorMsg;
    private LocalDateTime sentAt;
}

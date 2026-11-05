package com.ciniam.internradar.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * team_setting 表映射对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeamSettingEntity {

    private Long id;
    private Long teamId;
    private String settingKey;
    private String settingValue;
    private LocalDateTime updatedAt;
}

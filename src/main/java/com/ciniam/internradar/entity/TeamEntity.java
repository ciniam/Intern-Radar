package com.ciniam.internradar.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * team 表映射对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeamEntity {

    private Long id;
    private String name;
    private String inviteCode;
    private String ownerUserId;
    private String description;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

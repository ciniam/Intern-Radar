package com.ciniam.internradar.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * team_join_request 表映射对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeamJoinRequestEntity {

    private Long id;
    private Long teamId;
    private String userId;
    private String status;
    private String message;
    private String role;
    private String handledBy;
    private LocalDateTime createdAt;
    private LocalDateTime handledAt;
}

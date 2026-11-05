package com.ciniam.internradar.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * sys_user 表映射对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SysUserEntity {

    private Long id;
    private Long teamId;
    private String userId;
    private String name;
    private String role;
    private String mentorUserId;
    private String feishuOpenId;
    private String email;
    private String passwordHash;
    private LocalDateTime passwordUpdatedAt;
    private Boolean passwordResetRequired;
    private Integer failedAttempts;
    private LocalDateTime lockedUntil;
    private LocalDateTime lastLoginAt;
    private LocalDateTime joinedAt;
    private String status;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

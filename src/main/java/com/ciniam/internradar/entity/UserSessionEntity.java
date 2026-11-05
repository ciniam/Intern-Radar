package com.ciniam.internradar.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * user_session 表映射对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserSessionEntity {

    private Long id;
    private Long teamId;
    private String token;
    private String userId;
    private String role;
    private String status;
    private String device;
    private String ip;
    private LocalDateTime createdAt;
    private LocalDateTime expireAt;
}

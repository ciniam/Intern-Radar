package com.ciniam.internradar.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeishuAuthEntity {

    private Long id;
    private Long teamId;
    private String userId;
    private String openId;
    private String accessToken;
    private String refreshToken;
    private String scope;
    private LocalDateTime expireAt;
    private LocalDateTime updatedAt;
}

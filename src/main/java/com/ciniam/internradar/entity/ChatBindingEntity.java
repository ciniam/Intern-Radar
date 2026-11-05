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
public class ChatBindingEntity {

    private Long id;
    private Long teamId;
    private String userId;
    private String chatId;
    private String name;
    private String chatType;
    private String identity;
    private Boolean enabled;
    private Boolean verified;
    private String verifyMsg;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

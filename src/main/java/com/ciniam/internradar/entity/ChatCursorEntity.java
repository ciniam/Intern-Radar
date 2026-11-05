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
public class ChatCursorEntity {

    private Long id;
    private Long teamId;
    private String chatId;
    private String userId;
    private LocalDateTime lastMessageTime;
    private LocalDateTime updatedAt;
}

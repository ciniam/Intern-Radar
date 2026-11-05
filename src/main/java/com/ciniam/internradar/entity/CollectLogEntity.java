package com.ciniam.internradar.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CollectLogEntity {

    private Long id;
    private Long teamId;
    private String userId;
    private String source;
    private LocalDate statDate;
    private Integer fetched;
    private Integer inserted;
    private Long durationMs;
    private Boolean success;
    private String errorMsg;
    private LocalDateTime createdAt;
}

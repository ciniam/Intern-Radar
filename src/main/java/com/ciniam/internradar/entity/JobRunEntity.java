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
public class JobRunEntity {

    private Long id;
    private Long teamId;
    private String userId;
    private String jobType;
    private LocalDate bizDate;
    private String status;
    private Long durationMs;
    private String errorMsg;
    private LocalDateTime createdAt;
}

package com.ciniam.internradar.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * pitfall 表映射对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PitfallEntity {

    private Long id;
    private Long teamId;
    private String title;
    private String tags;
    private String symptom;
    private String cause;
    private String solution;
    private String contentRef;
    private Boolean enabled;
    private LocalDateTime updatedAt;
}

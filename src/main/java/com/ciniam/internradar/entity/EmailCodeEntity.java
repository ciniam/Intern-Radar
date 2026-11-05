package com.ciniam.internradar.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * email_code 表映射对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailCodeEntity {

    private Long id;
    private String email;
    private String purpose;
    private String codeHash;
    private LocalDateTime expiresAt;
    private Boolean used;
    private Integer attempts;
    private LocalDateTime createdAt;
}

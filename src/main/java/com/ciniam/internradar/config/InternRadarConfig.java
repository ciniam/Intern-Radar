package com.ciniam.internradar.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 实习雷达模块装配：开启定时任务、扫描 Mapper、绑定业务配置
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(InternRadarProperties.class)
@MapperScan("com.ciniam.internradar.mapper")
public class InternRadarConfig {
}

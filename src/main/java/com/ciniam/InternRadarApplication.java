package com.ciniam;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// 启动类在根包下，内核与业务子包都会被扫描到
@SpringBootApplication
public class InternRadarApplication {

    public static void main(String[] args) {
        SpringApplication.run(InternRadarApplication.class, args);
    }

}

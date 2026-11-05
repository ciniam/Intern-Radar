package com.ciniam.internradar.model;

import java.util.List;

/**
 * 个人配置引导。只统计需要本人填写的三项，服务端兜底能覆盖的情况在说明里讲清
 */
public record OnboardingView(List<Item> items, int done, int total) {

    public record Item(String key, String label, boolean configured, String why, String how) {
    }
}

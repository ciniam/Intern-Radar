<script setup>
import { computed } from 'vue'

/**
 * 实习雷达图标。经典蓝用于标签页，浅蓝用于侧边栏与登录页
 * 图形为定稿设计：中心点、扫描臂、三道同心弧与一个扇形
 */
const props = defineProps({
    variant: { type: String, default: 'soft' },
    size: { type: Number, default: 34 }
})

const solid = computed(() => props.variant === 'solid')
// logo 是图形资产，色值来自令牌的 --asset-logo / --asset-logo-weak，不参与界面配色
const background = computed(() => (solid.value ? 'var(--asset-logo)' : 'var(--asset-logo-weak)'))
const glyph = computed(() => (solid.value ? 'var(--text-inverse)' : 'var(--asset-logo)'))
const arcStrong = computed(() => (solid.value
    ? 'color-mix(in srgb, var(--text-inverse) 50%, transparent)'
    : 'color-mix(in srgb, var(--asset-logo) 40%, transparent)'))
const arcWeak = computed(() => (solid.value
    ? 'color-mix(in srgb, var(--text-inverse) 25%, transparent)'
    : 'color-mix(in srgb, var(--asset-logo) 20%, transparent)'))
</script>

<template>
    <svg :width="size" :height="size" viewBox="0 0 120 120" fill="none" aria-label="实习雷达">
        <rect width="120" height="120" rx="23.2" :fill="background" />
        <path d="M 24 62 A 36 36 0 0 0 96 62" :stroke="arcStrong" stroke-width="6" stroke-linecap="round" />
        <path d="M 34.8 66 A 25.2 25.2 0 0 0 85.2 66" :stroke="arcWeak" stroke-width="5" stroke-linecap="round" />
        <path
            d="M 45.6 70 A 14.4 14.4 0 0 0 74.4 70"
            :stroke="arcWeak"
            stroke-width="4"
            stroke-linecap="round"
            opacity="0.7"
        />
        <path d="M 60 60 L 90.6 34.8 A 30.6 30.6 0 0 1 70.8 88.8 Z" :fill="glyph" opacity="0.12" />
        <line x1="60" y1="60" x2="90.6" y2="34.8" :stroke="glyph" stroke-width="6" stroke-linecap="round" />
        <circle cx="60" cy="60" r="10" :fill="glyph" />
    </svg>
</template>

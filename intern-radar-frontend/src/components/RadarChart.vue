<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { use, init } from 'echarts/core'
import { BarChart, HeatmapChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, VisualMapComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

use([BarChart, PieChart, LineChart, HeatmapChart, GridComponent, TooltipComponent, VisualMapComponent, CanvasRenderer])

const props = defineProps({
    option: { type: Object, required: true },
    height: { type: Number, default: 160 },
    label: { type: String, default: '' }
})

const host = ref(null)
let chart = null
let observer = null
let frame = 0
let lastWidth = 0
let lastHeight = 0

function render() {
    chart.setOption(props.option, true)
}

function resize() {
    if (!chart || !host.value) {
        return
    }
    const width = host.value.clientWidth
    const height = host.value.clientHeight
    // 首次观察到的尺寸和初始化时一致，跳过才不会打断入场动画
    if (width === lastWidth && height === lastHeight) {
        return
    }
    lastWidth = width
    lastHeight = height
    chart.resize()
}

onMounted(() => {
    // 等一帧再做，容器完成布局后再初始化，动画从真实尺寸开始
    frame = requestAnimationFrame(() => {
        if (!host.value) {
            return
        }
        chart = init(host.value, null, { renderer: 'canvas' })
        lastWidth = host.value.clientWidth
        lastHeight = host.value.clientHeight
        render()
        observer = new ResizeObserver(resize)
        observer.observe(host.value)
    })
})

watch(() => props.option, () => chart && render(), { deep: true })

onBeforeUnmount(() => {
    cancelAnimationFrame(frame)
    if (observer) {
        observer.disconnect()
        observer = null
    }
    if (chart) {
        chart.dispose()
        chart = null
    }
})
</script>

<template>
    <div ref="host" class="chart-host" :style="{ height: `${height}px` }" role="img" :aria-label="label"></div>
</template>

<style scoped>
.chart-host {
    width: 100%;
}
</style>

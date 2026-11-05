<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { errorText, fetchAgentRun } from '../api'

// 轨迹按需加载：展开时才请求，避免一屏消息把接口打满
const props = defineProps({
    runId: { type: Number, default: null },
    title: { type: String, default: '执行轨迹' },
    defaultOpen: { type: Boolean, default: false }
})

const run = ref(null)
const loading = ref(false)
const failed = ref('')
const expanded = ref(props.defaultOpen)

const hint = computed(() => {
    if (loading.value) {
        return '加载中…'
    }
    if (failed.value) {
        return '加载失败'
    }
    if (!run.value) {
        return '点击展开'
    }
    return `${run.value.stepCount} 步 · ${(run.value.durationMs / 1000).toFixed(1)} 秒`
})

watch(() => props.runId, () => {
    run.value = null
    failed.value = ''
    if (props.defaultOpen) {
        expanded.value = true
        load()
    }
})

onMounted(() => {
    if (props.defaultOpen) {
        load()
    }
})

function onToggle() {
    expanded.value = !expanded.value
    if (expanded.value && !run.value && !loading.value) {
        load()
    }
}

async function load() {
    if (!props.runId) {
        return
    }
    loading.value = true
    failed.value = ''
    try {
        run.value = await fetchAgentRun(props.runId)
    } catch (e) {
        failed.value = errorText(e)
    } finally {
        loading.value = false
    }
}

defineExpose({ load })
</script>

<template>
    <div v-if="runId" class="trace">
        <button class="trace-head" type="button" :aria-expanded="expanded" @click="onToggle">
            <span class="trace-title">{{ title }}</span>
            <span class="trace-hint">{{ hint }}</span>
            <span class="chev">{{ expanded ? '▾' : '▸' }}</span>
        </button>
        <div v-show="expanded" class="trace-body">
            <div v-if="failed" class="trace-note">{{ failed }}</div>
            <template v-else-if="run">
                <div v-if="run.status !== 'SUCCESS'" class="trace-note warn">
                    本次运行状态：{{ run.status }}<span v-if="run.errorMsg"> · {{ run.errorMsg }}</span>
                </div>
                <div v-if="!run.steps.length" class="trace-note">没有记录到步骤</div>
                <div v-for="(step, index) in run.steps" :key="step.id || index" class="trace-step">
                    <div class="trace-meta">
                        <span class="trace-no">第 {{ step.stepNo }} 步</span>
                        <span class="tiny muted num">{{ (step.durationMs / 1000).toFixed(1) }} 秒</span>
                    </div>
                    <div v-if="step.thought" class="trace-thought">{{ step.thought }}</div>
                    <template v-if="step.toolName">
                        <div class="trace-tool">调用工具 {{ step.toolName }}</div>
                        <pre v-if="step.toolArgs" class="trace-pre">{{ step.toolArgs }}</pre>
                        <pre v-if="step.toolResult" class="trace-pre">{{ step.toolResult }}</pre>
                    </template>
                </div>
            </template>
        </div>
    </div>
</template>

<style scoped>
.trace-head {
    width: 100%;
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: 0;
    border: 0;
    background: transparent;
    text-align: left;
    cursor: pointer;
    font-size: var(--font-size-12);
}

.trace-title {
    font-weight: var(--fw-medium);
    color: var(--text-1);
}

.trace-hint {
    color: var(--text-3);
}

.chev {
    margin-left: auto;
    color: var(--text-4);
    font-size: var(--font-size-11);
}

.trace-body {
    margin-top: var(--space-2);
}

.trace-step {
    padding: var(--space-2) 0;
    border-bottom: 1px dashed var(--line-default);
}

.trace-step:last-child {
    border-bottom: none;
}

.trace-meta {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    font-size: var(--font-size-12);
}

.trace-no {
    font-weight: var(--fw-semibold);
    color: var(--text-3);
}

.trace-thought {
    font-size: var(--font-size-12);
    line-height: var(--line-height-base);
    margin-top: var(--space-1);
    white-space: pre-wrap;
    word-break: break-word;
}

.trace-tool {
    font-size: var(--font-size-12);
    color: var(--color-brand-hover);
    margin-top: var(--space-2);
}

.trace-pre {
    margin: var(--space-1) 0 0;
    padding: var(--space-2) var(--space-3);
    background: var(--surface-1);
    border-radius: var(--radius-1);
    font-family: var(--font-mono);
    font-size: var(--font-size-11);
    line-height: var(--line-height-base);
    max-height: 14em;
    overflow: auto;
    white-space: pre-wrap;
    word-break: break-word;
}

.trace-note {
    font-size: var(--font-size-12);
    padding: var(--space-1) 0;
    color: var(--text-3);
}

.trace-note.warn {
    color: var(--color-warning);
}
</style>

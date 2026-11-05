<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { errorText, fetchMentorInterns, fetchReports, generateDaily } from '../api'
import { useAuth } from '../store/auth'
import AgentTrace from '../components/AgentTrace.vue'
import {
    formatDateTime,
    reportStatusLabel,
    reportStatusTag,
    todayISO,
    weekAgoISO
} from '../utils/format'

const route = useRoute()
const router = useRouter()
const auth = useAuth()

const isMentor = auth.isMentor
const interns = ref([])
const userId = ref('')
const loading = ref(false)
const generating = ref(false)
const loadError = ref('')

const filterType = ref('')
const range = ref([weekAgoISO(), todayISO()])
const reports = ref([])
const selectedId = ref(null)
const traceOpen = ref(false)

const TYPE_OPTIONS = [
    { label: '全部', value: '' },
    { label: '日报', value: 'DAILY' },
    { label: '计划', value: 'PLAN' },
    { label: '周报', value: 'WEEKLY' }
]
const TYPE_TITLE = { DAILY: '日报', PLAN: '计划', WEEKLY: '周报' }
const TYPE_EYEBROW = { DAILY: 'Daily', PLAN: 'Plan', WEEKLY: 'Weekly' }

const now = new Date()
const monthLabel = `${now.getFullYear()} 年 ${now.getMonth() + 1} 月`

const selected = computed(() => reports.value.find(item => item.id === selectedId.value) || null)
const summary = computed(() => ((selected.value && selected.value.content) || {}).summary || '')
const cardTitle = computed(() => headline(selected.value))
const cardEyebrow = computed(() => {
    const item = selected.value
    if (!item) {
        return ''
    }
    return `${TYPE_EYEBROW[item.type] || TYPE_TITLE[item.type] || ''} · ${item.periodStart || ''}`
})
const docSections = computed(() => sectionsOf(selected.value))
const canTrace = computed(() => !!(selected.value && selected.value.agentRunId))

function listOf(value) {
    return Array.isArray(value) ? value : []
}

function normalize(entry) {
    if (entry && typeof entry === 'object') {
        return { text: entry.content || entry.text || '', evidence: listOf(entry.evidence) }
    }
    return { text: entry === undefined || entry === null ? '' : String(entry), evidence: [] }
}

function sectionsOf(item) {
    if (!item) {
        return []
    }
    const content = item.content || {}
    let sections
    if (item.type === 'PLAN') {
        sections = [
            { key: 'focusItems', title: '今日重点', items: listOf(content.focusItems) },
            { key: 'todoItems', title: '待办', items: listOf(content.todoItems) },
            { key: 'riskNotes', title: '风险提示', items: listOf(content.riskNotes), warn: true }
        ]
    } else if (item.type === 'WEEKLY') {
        sections = [
            { key: 'outputs', title: '本周产出', items: listOf(content.outputs) },
            { key: 'trendNotes', title: '趋势变化', items: listOf(content.trendNotes) },
            { key: 'nextWeekFocus', title: '下周重点', items: listOf(content.nextWeekFocus) },
            { key: 'risks', title: '风险提示', items: listOf(content.risks), warn: true }
        ]
    } else {
        sections = [
            { key: 'completed', title: '今日完成', items: listOf(content.completed) },
            { key: 'problems', title: '遇到问题', items: listOf(content.problems) },
            { key: 'tomorrowPlan', title: '明日计划', items: listOf(content.tomorrowPlan) },
            { key: 'needHelp', title: '需要协助', items: listOf(content.needHelp) }
        ]
    }
    return sections
        .map(section => ({ ...section, items: section.items.map(normalize).filter(entry => entry.text) }))
        .filter(section => section.items.length)
}

function headline(item) {
    if (!item) {
        return ''
    }
    const source = String((item.content || {}).summary || '').trim()
    if (!source) {
        return `${TYPE_TITLE[item.type] || '报告'} · ${mmdd(item.periodStart)}`
    }
    const first = (source.split(/[。；;！!？?\n]/)[0] || source).trim()
    return first.length > 24 ? `${first.slice(0, 24)}…` : first
}

function mmdd(value) {
    return String(value || '').slice(5)
}

function timeOf(value) {
    return formatDateTime(value).slice(11, 16)
}

function recordCount(item) {
    const content = item.content || {}
    if (item.type === 'PLAN') {
        return listOf(content.focusItems).length
    }
    if (item.type === 'WEEKLY') {
        return listOf(content.outputs).length
    }
    return listOf(content.completed).length
}

function itemMeta(item) {
    const unit = item.type === 'PLAN' ? '条重点' : item.type === 'WEEKLY' ? '条产出' : '条记录'
    return `${timeOf(item.generatedAt)} · ${recordCount(item)} ${unit}`
}

function typeTitle(item) {
    return `${TYPE_TITLE[item.type] || item.type || '报告'} · ${mmdd(item.periodStart)}`
}

onMounted(init)

async function init() {
    if (isMentor.value && !interns.value.length) {
        try {
            interns.value = (await fetchMentorInterns(7)) || []
        } catch (e) {
            loadError.value = errorText(e)
            return
        }
        const fromQuery = route.query.userId
        userId.value = fromQuery && interns.value.some(item => item.userId === fromQuery)
            ? String(fromQuery)
            : (interns.value.length ? interns.value[0].userId : '')
    } else if (!isMentor.value) {
        userId.value = auth.userId.value
    }
    await load()
}

async function load() {
    const [start, end] = range.value || []
    if (!userId.value || !start || !end) {
        reports.value = []
        selectedId.value = null
        return
    }
    loading.value = true
    loadError.value = ''
    try {
        const data = await fetchReports({
            userId: userId.value,
            start,
            end,
            type: filterType.value || undefined
        })
        reports.value = data || []
        const keep = reports.value.some(item => item.id === selectedId.value) ? selectedId.value : null
        selectedId.value = keep || (reports.value.length ? reports.value[0].id : null)
    } catch (e) {
        reports.value = []
        selectedId.value = null
        loadError.value = errorText(e)
    } finally {
        loading.value = false
    }
}

async function generate() {
    if (!userId.value) {
        return
    }
    generating.value = true
    try {
        await generateDaily(userId.value, todayISO(), false)
        ElMessage.success('当日总结已生成')
        await load()
        // 生成后直接打开刚出的那份，不然右侧还停在旧报告上
        const fresh = reports.value.find(item =>
            item.type === 'DAILY' && String(item.periodStart || '').slice(0, 10) === todayISO()
        )
        if (fresh) {
            selectedId.value = fresh.id
        }
    } catch (e) {
        ElMessage.error(errorText(e))
    } finally {
        generating.value = false
    }
}

function onTypeChange(value) {
    filterType.value = value
    load()
}

function onPersonChange(value) {
    if (!value) {
        return
    }
    router.replace({ path: '/reports', query: { userId: value } })
    load()
}

function plainText(item) {
    const lines = [`${TYPE_TITLE[item.type] || '报告'} · ${item.periodStart || ''}`]
    const text = ((item.content || {}).summary || '').trim()
    if (text) {
        lines.push('', text)
    }
    sectionsOf(item).forEach(section => {
        lines.push('', section.title)
        section.items.forEach(entry => {
            const evidence = entry.evidence.length ? ` (commit ${entry.evidence.join('、')})` : ''
            lines.push(`- ${entry.text}${evidence}`)
        })
    })
    return lines.join('\n')
}

async function copyContent() {
    if (!selected.value) {
        return
    }
    try {
        await navigator.clipboard.writeText(plainText(selected.value))
        ElMessage.success('内容已复制')
    } catch (e) {
        ElMessage.warning('当前浏览器不允许写入剪贴板')
    }
}

function onCommand(command) {
    if (command === 'copy') {
        copyContent()
        return
    }
    if (command === 'trace' && canTrace.value) {
        traceOpen.value = true
    }
}
</script>

<template>
    <div v-loading="loading" class="page">
        <header class="page-head">
            <div>
                <div class="eyebrow">{{ monthLabel }}</div>
                <h1 class="h1">报告</h1>
            </div>
            <div class="page-actions">
                <button class="btn btn-primary" type="button" :disabled="generating" @click="generate">
                    {{ generating ? '正在生成…' : '生成当日总结' }}
                </button>
            </div>
        </header>
        <div class="divider-full"></div>

        <div class="filters">
            <div class="seg">
                <button
                    v-for="option in TYPE_OPTIONS"
                    :key="option.value"
                    type="button"
                    :class="{ active: filterType === option.value }"
                    @click="onTypeChange(option.value)"
                >{{ option.label }}</button>
            </div>
            <el-date-picker
                v-model="range"
                class="w-240"
                type="daterange"
                value-format="YYYY-MM-DD"
                start-placeholder="开始日期"
                end-placeholder="结束日期"
                @change="load"
            />
            <span class="tiny muted num">{{ reports.length }} 份</span>
            <div v-if="isMentor" class="filters-person">
                <span class="tiny muted">查看对象</span>
                <el-select v-model="userId" class="w-160" @change="onPersonChange">
                    <el-option
                        v-for="intern in interns"
                        :key="intern.userId"
                        :label="intern.name || intern.userId"
                        :value="intern.userId"
                    />
                </el-select>
            </div>
        </div>

        <div v-if="loadError" class="notice">
            <div class="callout rose">
                <span class="dot rose"></span>
                <span>{{ loadError }}</span>
                <span class="co-actions">
                    <button class="btn btn-sm" type="button" @click="init">重试</button>
                </span>
            </div>
        </div>

        <div v-if="!userId" class="list-empty">还没有可查看的实习生，先在团队里补充成员</div>
        <div v-else-if="!reports.length" class="list-empty">
            <span>该区间内还没有报告</span>
            <button class="btn btn-sm" type="button" :disabled="generating" @click="generate">生成当日总结</button>
        </div>
        <div v-else class="split split-340">
            <div class="pane-left">
                <div class="rows rows-divided report-list">
                    <button
                        v-for="item in reports"
                        :key="item.id"
                        class="row-item report-row"
                        type="button"
                        :class="{ active: item.id === selectedId }"
                        @click="selectedId = item.id"
                    >
                        <div class="row-main">
                            <span class="row-title">{{ typeTitle(item) }}</span>
                            <span class="row-sub num">{{ itemMeta(item) }}</span>
                        </div>
                        <span class="row-side">
                            <el-tag :type="reportStatusTag(item.status)" size="small" effect="light">
                                {{ reportStatusLabel(item.status) }}
                            </el-tag>
                        </span>
                    </button>
                </div>
            </div>

            <div class="pane-right">
                <div v-if="selected" class="card">
                    <div class="card-head">
                        <div class="card-head-main">
                            <div class="eyebrow">{{ cardEyebrow }}</div>
                            <div class="card-title">{{ cardTitle }}</div>
                        </div>
                        <el-dropdown trigger="click" @command="onCommand">
                            <button class="btn btn-sm btn-ghost dots" type="button">···</button>
                            <template #dropdown>
                                <el-dropdown-menu>
                                    <el-dropdown-item command="copy">复制内容</el-dropdown-item>
                                    <el-dropdown-item v-if="canTrace" command="trace">执行轨迹</el-dropdown-item>
                                </el-dropdown-menu>
                            </template>
                        </el-dropdown>
                    </div>

                    <div class="meta card-meta">
                        <span>生成于 {{ timeOf(selected.generatedAt) }}</span>
                    </div>
                    <div class="divider-inset card-divider"></div>

                    <div v-if="docSections.length" class="doc">
                        <p v-if="summary" class="lead">{{ summary }}</p>
                        <template v-for="section in docSections" :key="section.key">
                            <div class="sub">
                                <div class="sub-title">{{ section.title }}</div>
                                <div v-if="section.warn">
                                    <div v-for="(entry, index) in section.items" :key="index" class="risk-note">
                                        <span class="dot amber"></span>
                                        <span>{{ entry.text }}</span>
                                    </div>
                                </div>
                                <ul v-else>
                                    <li v-for="(entry, index) in section.items" :key="index">
                                        {{ entry.text }}
                                        <span v-if="entry.evidence.length" class="tiny muted">
                                            commit {{ entry.evidence.join('、') }}
                                        </span>
                                    </li>
                                </ul>
                            </div>
                        </template>
                    </div>
                    <div v-else class="list-empty">这份报告没有正文内容</div>
                </div>
                <div v-else class="list-empty">从左侧选一份报告查看</div>
            </div>
        </div>

        <el-dialog v-model="traceOpen" title="执行轨迹" width="640">
            <AgentTrace
                v-if="canTrace"
                :run-id="selected.agentRunId"
                title="生成过程"
                dense
                default-open
            />
            <template #footer>
                <button class="btn" type="button" @click="traceOpen = false">关闭</button>
            </template>
        </el-dialog>
    </div>
</template>

<style scoped>
.filters {
    display: flex;
    align-items: center;
    gap: var(--space-3);
    padding: var(--space-5) 0 var(--space-4);
    flex-wrap: wrap;
}

.filters-person {
    margin-left: auto;
    display: flex;
    align-items: center;
    gap: var(--space-2);
}

/* Element Plus 给这两个控件写了 flex:1，会把筛选行撑满，这里改回定宽 */
.filters :deep(.el-date-editor) {
    flex: none;
    width: 240px;
}

.filters :deep(.el-select) {
    flex: none;
    width: 160px;
}

.notice {
    padding: var(--space-5) 0 var(--space-1);
}

.report-row {
    width: 100%;
    border: 0;
    background: transparent;
    font: inherit;
    text-align: left;
    border-radius: var(--radius-2);
}

.report-row.active {
    background: var(--color-brand-weak);
}

.report-row.active .row-title {
    color: var(--color-brand-hover);
}

.list-empty {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: var(--space-3);
    padding: var(--space-8) 0;
    font-size: var(--font-size-12);
    color: var(--text-3);
}

.card-head {
    display: flex;
    align-items: flex-start;
    gap: var(--space-3);
}

.card-head-main {
    flex: 1;
    min-width: 0;
}

.card-title {
    font-size: var(--font-size-16);
    font-weight: var(--fw-semibold);
    line-height: var(--line-height-tight);
    color: var(--text-1);
}

.card-meta {
    margin-top: var(--space-2);
}

.card-divider {
    margin: var(--space-5) 0;
}

.risk-note {
    display: flex;
    align-items: baseline;
    gap: var(--space-2);
    font-size: var(--font-size-12);
    color: var(--text-2);
    margin-bottom: var(--space-1);
}

@media (max-width: 1180px) {
    .report-list {
        max-width: none;
    }
}
</style>
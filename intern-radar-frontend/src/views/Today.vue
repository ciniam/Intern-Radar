<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import {
    ackAlert,
    errorText,
    fetchAlerts,
    fetchOnboarding,
    fetchPreferences,
    fetchReportStats,
    fetchReports,
    fetchTasks,
    generateDaily,
    learnRecommend
} from '../api'
import { useAuth } from '../store/auth'
import RadarChart from '../components/RadarChart.vue'
import { barOption, donutOption, heatHeight, heatOption, heatWidth } from '../utils/chartOption'
import {
    formatDateTime,
    mondayISO,
    parseMetadata,
    relativeFromNow,
    riskTypeLabel,
    todayISO
} from '../utils/format'

const auth = useAuth()
const router = useRouter()

const loading = ref(false)
const generating = ref(false)
const loadError = ref('')
const tab = ref('plan')
const remindOpen = ref(false)
const readOpen = ref(false)

const reports = ref([])
const alerts = ref([])
const recommend = ref(null)
const tasks = ref([])
const onboarding = ref(null)
const stats = ref(null)
const preference = ref(null)

const today = todayISO()
const WEEK_COLS = ['一', '二', '三', '四', '五', '六', '日']
const SOURCE_LABEL = { git: '代码提交', zentao: '内部任务', 'feishu-chat': '会话摘要' }
// 热力图固定五行，顺序与原型一致；后两类来自阅读与问答埋点
const HEAT_SOURCES = [
    { key: 'git', label: '代码提交' },
    { key: 'zentao', label: '任务更新' },
    { key: 'feishu-chat', label: '会话摘要' },
    { key: 'doc-read', label: '文档阅读' },
    { key: 'ask', label: '问答' }
]

const planReport = computed(() => pickReport('PLAN'))
const dailyReport = computed(() => pickReport('DAILY'))

function pickReport(type) {
    return reports.value.find(item => item.type === type) || null
}

/* ---------- 日期与数字 ---------- */

const parsedToday = new Date(`${today}T00:00:00`)
const dateLabel = `${parsedToday.getMonth() + 1} 月 ${parsedToday.getDate()} 日 · 周${WEEK_COLS[(parsedToday.getDay() + 6) % 7]}`

function isoOf(date) {
    const month = `${date.getMonth() + 1}`.padStart(2, '0')
    const day = `${date.getDate()}`.padStart(2, '0')
    return `${date.getFullYear()}-${month}-${day}`
}

const statDays = computed(() => (stats.value && stats.value.days) || [])
const dayIndex = computed(() => {
    const map = new Map()
    statDays.value.forEach(day => map.set(day.date, day))
    return map
})

const todayStat = computed(() => dayIndex.value.get(today) || null)
const todayRecords = computed(() => (todayStat.value ? todayStat.value.total : 0))
const rangeTotal = computed(() => statDays.value.reduce((sum, day) => sum + day.total, 0))
const activityTotal = computed(() => statDays.value.reduce((sum, day) => sum
    + Object.values(day.bySource || {}).reduce((inner, value) => inner + value, 0), 0))
const hasCharts = computed(() => rangeTotal.value > 0 || activityTotal.value > 0)
const focusItems = computed(() => (planReport.value && planReport.value.content ? planReport.value.content.focusItems || [] : []))
const generatedLabel = computed(() => {
    // 报告列表是本周区间，只认当天那份，否则会把周一的日报时间标到今天
    const source = [dailyReport.value, planReport.value]
        .filter(report => report && report.generatedAt && report.periodStart === today)
        .sort((left, right) => String(right.generatedAt).localeCompare(String(left.generatedAt)))[0]
    if (!source) {
        return ''
    }
    const time = formatDateTime(source.generatedAt).slice(11, 16)
    return time ? `${time} 生成` : ''
})

/**
 * 推送时间落在数字行右侧，和「生成时间」并列，不占页头那一行
 */
const pushLabel = computed(() => {
    const value = preference.value
    if (!value) {
        return ''
    }
    const parts = []
    if (value.morningPushEnabled) {
        parts.push(`计划 ${value.morningPushTime}`)
    }
    if (value.eveningPushEnabled) {
        parts.push(`总结 ${value.eveningPushTime}`)
    }
    return parts.length ? `${parts.join(' / ')} 推送` : ''
})

const metaLine = computed(() => [generatedLabel.value, pushLabel.value].filter(Boolean).join(' · '))

const missingItems = computed(() => {
    if (!onboarding.value || !Array.isArray(onboarding.value.items)) {
        return []
    }
    return onboarding.value.items.filter(item => !item.configured)
})
const missingNames = computed(() => missingItems.value.map(item => item.label).join('、'))

/* ---------- 图表 ---------- */

const lastSeven = computed(() => statDays.value.slice(-7))

const barChartOption = computed(() => barOption({
    values: lastSeven.value.map(day => day.total),
    names: lastSeven.value.map(day => `${Number(day.date.slice(8, 10))}`),
    unit: ' 条'
}))

const barChartLabel = computed(() => `近 7 天记录：${lastSeven.value
    .map(day => `${Number(day.date.slice(5, 7))}-${Number(day.date.slice(8, 10))} ${day.total} 条`)
    .join('，')}`)

const todaySegments = computed(() => {
    const bySource = (todayStat.value && todayStat.value.bySource) || {}
    return Object.keys(SOURCE_LABEL)
        .map((key, index) => ({
            key,
            label: SOURCE_LABEL[key],
            value: bySource[key] || 0,
            colorToken: `--chart-${index + 1}`
        }))
        .filter(item => item.value > 0)
})

const donutChartOption = computed(() => donutOption({
    segments: todaySegments.value,
    totalLabel: '条记录'
}))

const donutChartLabel = computed(() => `今日来源构成：${todaySegments.value
    .map(item => `${item.label} ${item.value}`)
    .join('，') || '暂无记录'}`)

/**
 * 热力图与柱状图共用「近 7 天」这一段，列标签取每天真实星期，
 * 周一打开时不会只剩一格，也不会把还没到的日子画成零产出
 */
const heatCols = computed(() => lastSeven.value.map(day => {
    const date = new Date(`${day.date}T00:00:00`)
    return WEEK_COLS[(date.getDay() + 6) % 7]
}))

const heatRows = computed(() => HEAT_SOURCES.map(source => ({
    name: source.label,
    values: lastSeven.value.map(day => (day.bySource || {})[source.key] || 0)
})))

const heatMax = computed(() => heatRows.value.reduce(
    (max, row) => row.values.reduce((inner, value) => Math.max(inner, value), max),
    0
))

const heatChartOption = computed(() => heatOption({
    cols: heatCols.value,
    rows: heatRows.value,
    max: heatMax.value
}))

const heatChartHeight = computed(() => heatHeight(heatRows.value.length))
const heatChartWidth = computed(() => heatWidth(heatCols.value.length))

const heatChartLabel = computed(() => `近 7 天活跃：${heatRows.value
    .map(row => `${row.name} ${row.values.join('/')}`)
    .join('；')}`)

/* ---------- 数据加载 ---------- */

onMounted(load)

async function load() {
    const userId = auth.userId.value
    loading.value = true
    loadError.value = ''
    try {
        const [weekReports, openAlerts, reco, taskList, guide, statsView, prefView] = await Promise.all([
            fetchReports({ userId, start: mondayISO(), end: today }),
            fetchAlerts(userId, 'OPEN'),
            learnRecommend(userId),
            fetchTasks(userId),
            fetchOnboarding(),
            fetchReportStats(userId, addDaysISO(today, -6), today),
            fetchPreferences(userId)
        ])
        reports.value = weekReports || []
        alerts.value = openAlerts || []
        recommend.value = reco
        tasks.value = taskList || []
        onboarding.value = guide
        stats.value = statsView
        preference.value = prefView.preference || prefView
    } catch (e) {
        loadError.value = errorText(e)
    } finally {
        loading.value = false
    }
}

function addDaysISO(iso, offset) {
    const date = new Date(`${iso}T00:00:00`)
    date.setDate(date.getDate() + offset)
    return isoOf(date)
}

async function generate() {
    generating.value = true
    try {
        await generateDaily(auth.userId.value, today, false)
        ElMessage.success('当日总结已生成')
        await load()
        tab.value = 'summary'
    } catch (e) {
        ElMessage.error(errorText(e))
    } finally {
        generating.value = false
    }
}

async function acknowledge(id) {
    try {
        await ackAlert(id)
        alerts.value = await fetchAlerts(auth.userId.value, 'OPEN')
        ElMessage.success('已标记处理')
    } catch (e) {
        ElMessage.error(errorText(e))
    }
}

async function acknowledgeAll() {
    if (!alerts.value.length) {
        return
    }
    try {
        for (const alert of alerts.value) {
            await ackAlert(alert.id)
        }
        alerts.value = await fetchAlerts(auth.userId.value, 'OPEN')
        ElMessage.success('已全部标记处理')
    } catch (e) {
        ElMessage.error(errorText(e))
    }
}

function goConfig() {
    router.push({ path: '/settings', query: { open: missingItems.value[0] ? missingItems.value[0].key : undefined } })
}

function askAi() {
    router.push({ path: '/learn', query: { ask: '帮我配置实习雷达的个人设置，先看看我还缺哪些没配' } })
}

function openDoc(id) {
    readOpen.value = false
    router.push({ path: '/docs', query: { docId: id } })
}

/* ---------- 提醒与任务 ---------- */

function alertTitle(alert) {
    return alert.title || riskTypeLabel(alert.type)
}

function alertEvidence(alert) {
    const text = (alert.evidence || '').split('｜').filter(Boolean).join(' · ')
    return text.length > 40 ? `${text.slice(0, 40)}…` : text
}

function taskProgress(task) {
    const value = parseMetadata(task.metadata).progress
    const number = value === undefined || value === null || value === '' ? null : Number(value)
    return Number.isFinite(number) ? Math.min(Math.max(number, 0), 100) : null
}

function taskDeadline(task) {
    return parseMetadata(task.metadata).deadline || ''
}

const recommendDocs = computed(() => (recommend.value && recommend.value.docs) || [])
</script>

<template>
    <div v-loading="loading" class="page">
        <header class="page-head">
            <div>
                <div class="eyebrow">{{ dateLabel }}</div>
                <h1 class="h1">我的今日</h1>
            </div>
            <div class="page-actions">
                <button class="btn btn-primary" type="button" :disabled="generating" @click="generate">
                    {{ generating ? '正在生成…' : '生成当日总结' }}
                </button>
            </div>
        </header>
        <div class="divider-full"></div>

        <div v-if="loadError" class="notice">
            <div class="callout rose">
                <span class="dot rose"></span>
                <span>{{ loadError }}</span>
                <span class="co-actions">
                    <button class="btn btn-sm" type="button" @click="load">重试</button>
                </span>
            </div>
        </div>

        <template v-else>
            <div v-if="missingItems.length" class="notice">
                <div class="callout" data-surface="1">
                    <span class="dot amber"></span>
                    <span><b class="strong">配置还差 {{ missingItems.length }} 项</b> · {{ missingNames }}</span>
                    <span class="co-actions">
                        <button class="btn btn-sm btn-ghost" type="button" @click="askAi">让 AI 帮我配</button>
                        <button class="btn btn-sm" type="button" @click="goConfig">去配置</button>
                    </span>
                </div>
            </div>

            <div class="nums-row">
                <div class="nums">
                    <div class="num-item">
                        <span class="num-label">今日记录</span>
                        <span class="num-value num">{{ todayRecords }}</span>
                    </div>
                    <div class="num-item">
                        <span class="num-label">未完成任务</span>
                        <span class="num-value num">{{ tasks.length }}</span>
                    </div>
                    <div class="num-item danger">
                        <span class="num-label">待处理提醒</span>
                        <span class="num-value num">{{ alerts.length }}</span>
                    </div>
                    <div class="num-item accent">
                        <span class="num-label">今日重点</span>
                        <span class="num-value num">{{ focusItems.length }}<span class="unit">条</span></span>
                    </div>
                </div>
                <div v-if="metaLine" class="meta">
                    <span>{{ metaLine }}</span>
                </div>
            </div>
            <div class="divider-full"></div>

            <section class="section">
                <div v-if="!hasCharts" class="empty-line">
                    近 7 天还没有采集到工作记录
                    <button class="btn btn-sm btn-ghost" type="button" @click="goConfig">去配置采集</button>
                </div>
                <div v-else class="chart-grid">
                    <div>
                        <div class="sec-head">
                            <span class="sec-title">近 7 天记录</span>
                            <span class="sec-hint">条 / 天</span>
                        </div>
                        <RadarChart :option="barChartOption" :height="160" :label="barChartLabel" />
                    </div>
                    <div>
                        <div class="sec-head">
                            <span class="sec-title">来源构成</span>
                            <span class="sec-hint">{{ todayRecords }} 条</span>
                        </div>
                        <div v-if="todaySegments.length" class="donut-wrap">
                            <RadarChart :option="donutChartOption" :height="136" :label="donutChartLabel" />
                            <div class="legend legend-col">
                                <span v-for="item in todaySegments" :key="item.key">
                                    <i :style="{ background: `var(${item.colorToken})` }"></i>{{ item.label }}
                                    <b class="num legend-num">{{ item.value }}</b>
                                </span>
                            </div>
                        </div>
                        <div v-else class="chart-empty">今天还没有工作记录</div>
                    </div>
                    <div>
                        <div class="sec-head">
                            <span class="sec-title">近 7 天活跃</span>
                            <span class="sec-hint">来源 × 天</span>
                        </div>
                        <div class="heat-box" :style="{ maxWidth: `${heatChartWidth}px` }">
                            <RadarChart :option="heatChartOption" :height="heatChartHeight" :label="heatChartLabel" />
                        </div>
                    </div>
                </div>
            </section>
            <div class="divider-full"></div>

            <section class="section">
                <div class="split split-340">
                    <div class="pane-left">
                        <div class="tabs" role="tablist" aria-label="今日计划与总结">
                            <button
                                class="tab"
                                type="button"
                                role="tab"
                                :aria-selected="tab === 'plan'"
                                :class="{ active: tab === 'plan' }"
                                @click="tab = 'plan'"
                            >今日计划</button>
                            <button
                                class="tab"
                                type="button"
                                role="tab"
                                :aria-selected="tab === 'summary'"
                                :class="{ active: tab === 'summary' }"
                                @click="tab = 'summary'"
                            >当日总结</button>
                        </div>

                        <div v-show="tab === 'plan'" role="tabpanel">
                            <div v-if="planReport && planReport.content" class="doc">
                                <p class="lead">{{ planReport.content.summary }}</p>
                                <div v-if="focusItems.length" class="sub">
                                    <div class="sub-title">今日重点</div>
                                    <div class="focus-list">
                                        <div v-for="(item, index) in focusItems" :key="index" class="focus-item">
                                            <span class="mono focus-index">{{ `${index + 1}`.padStart(2, '0') }}</span>
                                            <span>{{ item }}</span>
                                        </div>
                                    </div>
                                </div>
                                <div v-if="(planReport.content.todoItems || []).length" class="sub">
                                    <div class="sub-title">待办</div>
                                    <ul>
                                        <li v-for="(item, index) in planReport.content.todoItems" :key="index">{{ item }}</li>
                                    </ul>
                                </div>
                                <div v-if="(planReport.content.riskNotes || []).length" class="sub">
                                    <div class="sub-title">风险提示</div>
                                    <div v-for="(item, index) in planReport.content.riskNotes" :key="index" class="risk-note">
                                        <span class="dot amber"></span>
                                        <span>{{ item }}</span>
                                    </div>
                                </div>
                            </div>
                            <div v-else class="empty-line">今日计划尚未生成，早间会按配置的时间推送</div>
                        </div>

                        <div v-show="tab === 'summary'" role="tabpanel">
                            <div v-if="dailyReport && dailyReport.content" class="doc">
                                <p class="lead">{{ dailyReport.content.summary }}</p>
                                <div v-if="(dailyReport.content.completed || []).length" class="sub">
                                    <div class="sub-title">今日完成</div>
                                    <ul>
                                        <li v-for="(item, index) in dailyReport.content.completed" :key="index">
                                            {{ item.content }}
                                            <span v-if="(item.evidence || []).length" class="tiny muted">
                                                commit {{ (item.evidence || []).join('、') }}
                                            </span>
                                        </li>
                                    </ul>
                                </div>
                                <div v-if="(dailyReport.content.problems || []).length" class="sub">
                                    <div class="sub-title">遇到问题</div>
                                    <p v-for="(item, index) in dailyReport.content.problems" :key="index">{{ item }}</p>
                                </div>
                                <div v-if="(dailyReport.content.tomorrowPlan || []).length" class="sub">
                                    <div class="sub-title">明日计划</div>
                                    <ul>
                                        <li v-for="(item, index) in dailyReport.content.tomorrowPlan" :key="index">{{ item }}</li>
                                    </ul>
                                </div>
                                <div v-if="(dailyReport.content.needHelp || []).length" class="sub">
                                    <div class="sub-title">需要协助</div>
                                    <p v-for="(item, index) in dailyReport.content.needHelp" :key="index">{{ item }}</p>
                                </div>
                            </div>
                            <div v-else class="empty-line">当日总结尚未生成</div>
                        </div>
                    </div>

                    <div class="pane-right">
                        <div class="sec-head">
                            <span class="sec-title">需要处理</span>
                            <button
                                v-if="alerts.length"
                                class="btn btn-sm btn-ghost sec-actions"
                                type="button"
                                @click="remindOpen = true"
                            >全部 {{ alerts.length }} 条</button>
                        </div>

                        <div v-if="alerts.length" class="callout rose alert-top">
                            <div class="alert-head">
                                <span class="dot rose"></span>
                                <b class="alert-title">{{ alertTitle(alerts[0]) }}</b>
                            </div>
                            <div class="small muted alert-body">{{ alertEvidence(alerts[0]) }}</div>
                            <div class="alert-foot">
                                <span class="tiny muted">{{ relativeFromNow(alerts[0].createdAt) }}</span>
                                <button
                                    class="btn btn-sm"
                                    type="button"
                                    style="margin-left: auto"
                                    @click="acknowledge(alerts[0].id)"
                                >标记已处理</button>
                            </div>
                        </div>
                        <div v-else class="empty-line">当前没有待处理的提醒</div>

                        <div class="sec-head task-head">
                            <span class="sec-title">进行中的任务</span>
                            <span class="sec-hint">{{ tasks.length }}</span>
                        </div>
                        <div v-if="tasks.length" class="rows rows-divided">
                            <div v-for="task in tasks" :key="task.id" class="row-item task-row">
                                <div class="task-line">
                                    <span class="row-title">{{ task.title }}</span>
                                    <span class="tiny muted num task-date">{{ taskDeadline(task) }}</span>
                                </div>
                                <div class="task-line">
                                    <span class="bar bar-cap">
                                        <i :style="{ width: `${taskProgress(task) || 0}%` }"></i>
                                    </span>
                                    <span class="tiny muted num">
                                        {{ taskProgress(task) === null ? '未开始' : `${taskProgress(task)}%` }}
                                    </span>
                                </div>
                            </div>
                        </div>
                        <div v-else class="empty-line">没有未关闭的任务</div>

                        <div class="divider-inset recommend-line"></div>
                        <button class="btn btn-ghost btn-sm recommend-entry" type="button" @click="readOpen = true">
                            为你推荐 {{ recommendDocs.length }} 篇 <span class="chev">›</span>
                        </button>
                    </div>
                </div>
            </section>
        </template>

        <el-dialog v-model="remindOpen" title="待处理提醒" width="520">
            <div v-if="alerts.length" class="rows rows-divided">
                <div v-for="alert in alerts" :key="alert.id" class="row-item dialog-row">
                    <span class="dot" :class="alert.level === 'HIGH' ? 'rose' : alert.level === 'MEDIUM' ? 'amber' : 'muted'"></span>
                    <div class="row-main">
                        <span class="row-title">{{ alertTitle(alert) }}</span>
                        <span class="row-sub">{{ alertEvidence(alert) }} · {{ relativeFromNow(alert.createdAt) }}</span>
                    </div>
                    <div class="row-side">
                        <button class="btn btn-sm btn-ghost" type="button" @click="acknowledge(alert.id)">处理</button>
                    </div>
                </div>
            </div>
            <div v-else class="empty-line">没有待处理的提醒</div>
            <template #footer>
                <button class="btn" type="button" @click="remindOpen = false">关闭</button>
                <button class="btn btn-primary" type="button" :disabled="!alerts.length" @click="acknowledgeAll">全部处理</button>
            </template>
        </el-dialog>

        <el-dialog v-model="readOpen" title="为你推荐" width="420">
            <div v-if="recommendDocs.length" class="rows rows-divided">
                <div
                    v-for="doc in recommendDocs"
                    :key="doc.id"
                    class="row-item dialog-row clickable"
                    role="button"
                    tabindex="0"
                    @click="openDoc(doc.id)"
                    @keydown.enter="openDoc(doc.id)"
                >
                    <div class="row-main">
                        <span class="row-title">{{ doc.title }}</span>
                        <span class="row-sub">{{ doc.category }}<template v-if="(doc.tags || []).length"> · {{ (doc.tags || []).join(' · ') }}</template></span>
                    </div>
                    <span class="chev">›</span>
                </div>
            </div>
            <div v-else class="empty-line">暂时没有匹配到相关文档</div>
            <template #footer>
                <button class="btn" type="button" @click="readOpen = false">关闭</button>
            </template>
        </el-dialog>
    </div>
</template>

<style scoped>
.strong {
    font-weight: var(--fw-medium);
}

.notice {
    padding: var(--space-5) 0 var(--space-1);
}

.nums-row {
    display: flex;
    align-items: flex-end;
    padding: var(--space-5) 0 var(--space-6);
}

.nums-row .meta {
    margin-left: auto;
}

.chart-grid {
    display: grid;
    grid-template-columns: minmax(0, 1.1fr) minmax(0, 0.82fr) minmax(0, 1fr);
    gap: var(--space-8);
    align-items: start;
}

.donut-wrap {
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
}

.chart-empty {
    height: 136px;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: var(--font-size-12);
    color: var(--text-3);
}

.heat-box {
    width: 100%;
}

.legend-col {
    flex-direction: column;
    align-items: flex-start;
    gap: var(--space-2);
}

.legend-num {
    margin-left: var(--space-1);
    color: var(--text-1);
}

.empty-line {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-7) 0;
    font-size: var(--font-size-12);
    color: var(--text-3);
}

.focus-list {
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
}

.focus-item {
    display: flex;
    gap: var(--space-4);
    align-items: baseline;
}

.focus-index {
    color: var(--text-4);
}

.risk-note {
    display: flex;
    align-items: baseline;
    gap: var(--space-2);
    font-size: var(--font-size-12);
    color: var(--text-2);
    margin-bottom: var(--space-1);
}

.alert-top {
    display: block;
    padding: var(--space-4);
}

.alert-head {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    margin-bottom: var(--space-1);
}

.alert-title {
    font-size: var(--font-size-12);
    font-weight: var(--fw-semibold);
    color: var(--text-1);
}

.alert-body {
    line-height: var(--line-height-base);
}

.alert-foot {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    margin-top: var(--space-3);
}

.task-head {
    margin-top: var(--space-6);
}

.task-row {
    display: block;
    padding: var(--space-3) 0;
}

.bar-cap {
    max-width: 10em;
}

.task-line {
    display: flex;
    align-items: center;
    gap: var(--space-2);
}

.task-line + .task-line {
    margin-top: var(--space-2);
}

.task-date {
    margin-left: auto;
}

.recommend-line {
    margin: var(--space-6) 0 var(--space-3);
}

.recommend-entry {
    padding-left: 0;
}

.dialog-row {
    padding: var(--space-3) 0;
    align-items: flex-start;
}

.dialog-row .dot {
    margin-top: var(--space-2);
}

.clickable {
    cursor: pointer;
}

@media (max-width: 1180px) {
    .chart-grid {
        grid-template-columns: 1fr;
        gap: var(--space-6);
    }
}
</style>

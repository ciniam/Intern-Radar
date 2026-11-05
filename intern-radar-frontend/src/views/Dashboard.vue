<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
    ackAlert,
    checkRisk,
    errorText,
    fetchMentorAlerts,
    fetchMentorOverview,
    fetchMentorTrend,
    fetchReports,
    fetchTeam
} from '../api'
import RadarChart from '../components/RadarChart.vue'
import { donutOption, heatHeight, heatOption, heatWidth, lineOption } from '../utils/chartOption'
import {
    formatDateTime,
    parseMetadata,
    relativeFromNow,
    riskLevelLabel,
    riskTypeLabel,
    todayISO
} from '../utils/format'

const PERIODS = [7, 14, 30]
const LEVELS = [
    { key: 'HIGH', label: '高风险', colorToken: '--chart-4' },
    { key: 'MEDIUM', label: '中风险', colorToken: '--chart-3' },
    { key: 'LOW', label: '提示', colorToken: '--chart-5' }
]
const heatSteps = ['--chart-heat-0', '--chart-heat-1', '--chart-heat-2', '--chart-heat-3', '--chart-heat-4']

const loading = ref(false)
const checking = ref(false)
const loadError = ref('')
const days = ref(7)
const teamName = ref('')
const overview = ref(null)
const trend = ref([])
const alerts = ref([])

const riskOpen = ref(false)
const personOpen = ref(false)
const person = ref(null)
const personSummary = ref('')
const personLoading = ref(false)

const interns = computed(() => (overview.value && overview.value.interns) || [])
const alertByLevel = computed(() => (overview.value && overview.value.alertByLevel) || {})

onMounted(load)

async function load() {
    loading.value = true
    loadError.value = ''
    try {
        const [overviewData, trendData, alertData, team] = await Promise.all([
            fetchMentorOverview(days.value),
            fetchMentorTrend(days.value),
            fetchMentorAlerts(),
            fetchTeam().catch(() => null)
        ])
        overview.value = overviewData
        trend.value = trendData || []
        alerts.value = alertData || []
        teamName.value = (team && team.name) || ''
        heatPage.value = Math.min(heatPage.value, heatPageCount.value - 1)
    } catch (e) {
        loadError.value = errorText(e)
    } finally {
        loading.value = false
    }
}

function metric(key) {
    return (overview.value && overview.value[key]) || 0
}

const timeLabel = computed(() => {
    const value = overview.value && overview.value.generatedAt
    return value ? formatDateTime(value).slice(11, 16) : ''
})

const eyebrow = computed(() => {
    const parts = [teamName.value, timeLabel.value ? `截至 ${timeLabel.value}` : ''].filter(Boolean)
    return parts.join(' · ')
})

function isoOf(date) {
    const month = String(date.getMonth() + 1).padStart(2, '0')
    const day = String(date.getDate()).padStart(2, '0')
    return `${date.getFullYear()}-${month}-${day}`
}

const dayAxis = computed(() => {
    const result = []
    const base = new Date(`${todayISO()}T00:00:00`)
    for (let offset = days.value - 1; offset >= 0; offset -= 1) {
        const date = new Date(base)
        date.setDate(date.getDate() - offset)
        result.push(isoOf(date))
    }
    return result
})

/* ---------- 图表 ---------- */

const trendChartOption = computed(() => lineOption({
    values: trend.value.map(item => item.value),
    names: trend.value.map(item => String(item.date || '').slice(5)),
    unit: ' 条'
}))

const trendChartLabel = computed(() => `近 ${days.value} 天产出：${trend.value
    .map(item => `${String(item.date || '').slice(5)} ${item.value} 条`)
    .join('，') || '暂无数据'}`)

const HEAT_PAGE_SIZE = 5
const heatPage = ref(0)

function shortName(value) {
    const text = String(value || '')
    const at = text.indexOf('@')
    const base = at > 0 ? text.slice(0, at) : text
    return base.length > 6 ? `${base.slice(0, 6)}…` : base
}

const memberRows = computed(() => interns.value.map(intern => {
    const byDate = new Map((intern.daily || []).map(item => [String(item.date || '').slice(0, 10), item.count || 0]))
    return {
        name: shortName(intern.name || intern.userId),
        values: dayAxis.value.map(date => byDate.get(date) || 0)
    }
}))

const heatPageCount = computed(() => Math.max(1, Math.ceil(memberRows.value.length / HEAT_PAGE_SIZE)))

const heatRows = computed(() => {
    const start = heatPage.value * HEAT_PAGE_SIZE
    const rows = memberRows.value.slice(start, start + HEAT_PAGE_SIZE)
    // 不足五行时补空行，格子尺寸保持稳定
    while (rows.length < HEAT_PAGE_SIZE) {
        rows.push({ name: '', values: dayAxis.value.map(() => 0) })
    }
    return rows
})

const heatMax = computed(() => heatRows.value.reduce(
    (max, row) => row.values.reduce((inner, value) => Math.max(value, inner), max),
    0
))

const heatChartOption = computed(() => heatOption({
    cols: dayAxis.value.map(date => date.slice(5)),
    rows: heatRows.value,
    max: heatMax.value
}))

const heatChartHeight = heatHeight(HEAT_PAGE_SIZE)
const heatChartWidth = computed(() => heatWidth(dayAxis.value.length))

const heatChartLabel = computed(() => `近 ${days.value} 天活跃：${heatRows.value
    .filter(row => row.name)
    .map(row => `${row.name} ${row.values.join('/')}`)
    .join('；') || '暂无数据'}`)

function heatPageStep(step) {
    const next = heatPage.value + step
    if (next < 0 || next >= heatPageCount.value) {
        return
    }
    heatPage.value = next
}

const levelSegments = computed(() => LEVELS.map(item => ({
    key: item.key,
    label: item.label,
    colorToken: item.colorToken,
    value: alertByLevel.value[item.key] || 0
})))

const alertTotal = computed(() => levelSegments.value.reduce((sum, item) => sum + item.value, 0))

const donutChartOption = computed(() => donutOption({
    segments: levelSegments.value.filter(item => item.value > 0),
    totalLabel: '未处理'
}))

const donutChartLabel = computed(() => `未处理告警：${levelSegments.value
    .map(item => `${item.label} ${item.value}`)
    .join('，')}`)

/* ---------- 需要关注 ---------- */

function alertTail(title) {
    const text = String(title || '').trim()
    const index = text.search(/[：:]/)
    return index >= 0 ? text.slice(index + 1).trim() : text
}

function levelDot(level) {
    if (level === 'HIGH') {
        return 'rose'
    }
    return level === 'MEDIUM' ? 'amber' : 'muted'
}

const attention = computed(() => interns.value
    .filter(intern => intern.highestRisk && intern.highestRisk !== 'NONE')
    .map(intern => ({
        userId: intern.userId,
        level: intern.highestRisk,
        name: intern.name || intern.userId,
        text: alertTail((intern.alertTitles || [])[0]) || riskLevelLabel(intern.highestRisk),
        intern
    })))

/* ---------- 弹窗 ---------- */

const personName = computed(() => (person.value ? person.value.name || person.value.userId : ''))

function memberName(userId) {
    const found = interns.value.find(intern => intern.userId === userId)
    return found ? found.name || found.userId : userId
}

function alertType(alert) {
    return riskTypeLabel(alert.type) !== '-' ? riskTypeLabel(alert.type) : alert.title || '-'
}

async function openPerson(intern) {
    person.value = intern
    personSummary.value = ''
    personOpen.value = true
    personLoading.value = true
    try {
        const list = await fetchReports({
            userId: intern.userId,
            start: todayISO(),
            end: todayISO(),
            type: 'DAILY'
        })
        const report = (list || []).find(item => item.type === 'DAILY') || (list || [])[0]
        const content = report ? parseMetadata(report.content) : {}
        personSummary.value = content.summary || ''
    } catch (e) {
        personSummary.value = ''
    } finally {
        personLoading.value = false
    }
}

/* ---------- 动作 ---------- */

async function switchDays(value) {
    if (days.value === value) {
        return
    }
    days.value = value
    await load()
}

async function onCommand(command) {
    if (command === 'recheck') {
        await recheck()
    }
}

async function recheck() {
    checking.value = true
    try {
        let created = 0
        for (const intern of interns.value) {
            const result = await checkRisk(intern.userId, todayISO())
            created += (result || []).length
        }
        ElMessage.success(`风险检查完成，新增 ${created} 条`)
        await load()
    } catch (e) {
        ElMessage.error(errorText(e))
    } finally {
        checking.value = false
    }
}

async function acknowledge(id) {
    try {
        await ackAlert(id)
        ElMessage.success('已标记处理')
        alerts.value = await fetchMentorAlerts()
        await load()
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
        ElMessage.success('已全部标记处理')
        alerts.value = await fetchMentorAlerts()
        await load()
    } catch (e) {
        ElMessage.error(errorText(e))
    }
}
</script>

<template>
    <div v-loading="loading" class="page">
        <header class="page-head">
            <div>
                <div v-if="eyebrow" class="eyebrow">{{ eyebrow }}</div>
                <h1 class="h1">团队产出</h1>
            </div>
            <div class="page-actions">
                <div class="seg">
                    <button
                        v-for="item in PERIODS"
                        :key="item"
                        type="button"
                        :class="{ active: days === item }"
                        @click="switchDays(item)"
                    >{{ item }} 天</button>
                </div>
                <el-dropdown trigger="click" @command="onCommand">
                    <button class="btn btn-icon btn-ghost dots" type="button" :disabled="checking" aria-label="更多操作">···</button>
                    <template #dropdown>
                        <el-dropdown-menu>
                            <el-dropdown-item command="recheck" :disabled="checking">
                                {{ checking ? '正在检查…' : '重新检查风险' }}
                            </el-dropdown-item>
                        </el-dropdown-menu>
                    </template>
                </el-dropdown>
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
            <div class="nums-row">
                <div class="nums">
                    <div class="num-item">
                        <span class="num-label">实习生</span>
                        <span class="num-value num">{{ metric('internCount') }}</span>
                    </div>
                    <div class="num-item">
                        <span class="num-label">工作记录</span>
                        <span class="num-value num">{{ metric('recordCount') }}<span class="unit">条</span></span>
                    </div>
                    <div class="num-item danger">
                        <span class="num-label">未处理告警</span>
                        <span class="num-value num">{{ metric('openAlertCount') }}</span>
                    </div>
                    <div class="num-item">
                        <span class="num-label">未完成任务</span>
                        <span class="num-value num">{{ metric('openTaskCount') }}</span>
                    </div>
                </div>
                <button class="btn btn-sm btn-ghost nums-action" type="button" @click="riskOpen = true">查看风险</button>
            </div>
            <div class="divider-full"></div>

            <div v-if="!interns.length" class="empty-line">还没有实习生加入团队，暂时看不到产出</div>

            <template v-else>
                <section class="section">
                    <div class="sec-head">
                        <span class="sec-title">产出趋势</span>
                        <span class="sec-hint">近 {{ days }} 天 · 条/天</span>
                        <span class="sec-actions legend">
                            <span><i :style="{ background: 'var(--chart-1)' }"></i>团队合计</span>
                        </span>
                    </div>
                    <RadarChart :option="trendChartOption" :height="300" :label="trendChartLabel" />
                </section>
                <div class="divider-full"></div>

                <section class="section grid-2-1">
                    <div>
                        <div class="sec-head">
                            <span class="sec-title">活跃分布</span>
                            <span class="sec-hint">近 {{ days }} 天</span>
                            <span v-if="heatPageCount > 1" class="sec-actions heat-pager">
                                <button
                                    class="btn btn-sm btn-ghost"
                                    type="button"
                                    :disabled="heatPage === 0"
                                    @click="heatPageStep(-1)"
                                >上一页</button>
                                <span class="tiny muted num">{{ heatPage + 1 }} / {{ heatPageCount }}</span>
                                <button
                                    class="btn btn-sm btn-ghost"
                                    type="button"
                                    :disabled="heatPage + 1 >= heatPageCount"
                                    @click="heatPageStep(1)"
                                >下一页</button>
                            </span>
                        </div>
                        <div class="heat-box" :style="{ maxWidth: `${heatChartWidth}px` }">
                            <RadarChart :option="heatChartOption" :height="heatChartHeight" :label="heatChartLabel" />
                        </div>
                        <div class="heat-legend">
                            <span class="tiny muted">少</span>
                            <i v-for="step in heatSteps" :key="step" :style="{ background: `var(${step})` }"></i>
                            <span class="tiny muted">多</span>
                        </div>
                    </div>

                    <div class="donut-col">
                        <div class="sec-head">
                            <span class="sec-title">告警结构</span>
                            <span class="sec-hint">仅未处理</span>
                        </div>
                        <template v-if="alertTotal">
                            <RadarChart :option="donutChartOption" :height="160" :label="donutChartLabel" />
                            <div class="legend legend-col">
                                <span v-for="item in levelSegments" :key="item.key">
                                    <i :style="{ background: `var(${item.colorToken})` }"></i>{{ item.label }}
                                    <b class="num legend-num">{{ item.value }}</b>
                                </span>
                            </div>
                        </template>
                        <div v-else class="chart-empty">当前没有未处理告警</div>
                    </div>
                </section>
                <div class="divider-full"></div>

                <section class="section">
                    <div class="sec-head">
                        <span class="sec-title">需要关注</span>
                    </div>
                    <div v-if="attention.length" class="chip-row">
                        <button
                            v-for="item in attention"
                            :key="item.userId"
                            class="chip"
                            type="button"
                            @click="openPerson(item.intern)"
                        >
                            <span class="dot" :class="levelDot(item.level)"></span>{{ item.name }} · {{ item.text }}
                        </button>
                    </div>
                    <div v-else class="empty-line">目前没有需要关注的实习生</div>
                </section>
            </template>
        </template>

        <el-dialog v-model="riskOpen" title="未处理告警" width="520">
            <div v-if="timeLabel" class="tiny muted risk-sub">
                共 {{ alerts.length }} 条 · 数据截至 {{ timeLabel }}
            </div>
            <div v-if="alerts.length" class="rows rows-divided">
                <div v-for="alert in alerts" :key="alert.id" class="row-item dialog-row">
                    <span class="dot" :class="levelDot(alert.level)"></span>
                    <div class="row-main">
                        <span class="row-title">{{ memberName(alert.userId) }} · {{ alertType(alert) }}</span>
                        <span class="row-sub">{{ alert.evidence }}</span>
                    </div>
                    <div class="row-side">
                        <button class="btn btn-sm btn-ghost" type="button" @click="acknowledge(alert.id)">处理</button>
                    </div>
                </div>
            </div>
            <div v-else class="empty-line">当前没有未处理的告警</div>
            <template #footer>
                <button class="btn" type="button" @click="riskOpen = false">关闭</button>
                <button class="btn btn-primary" type="button" :disabled="!alerts.length" @click="acknowledgeAll">
                    全部标记已处理
                </button>
            </template>
        </el-dialog>

        <el-dialog v-model="personOpen" :title="personName" width="640">
            <template v-if="person">
                <div class="person-head">
                    <span class="avatar">{{ (person.name || person.userId).slice(0, 1) }}</span>
                    <div>
                        <div class="h2">{{ person.name || person.userId }}</div>
                        <div class="tiny muted">实习生 · 最近活跃 {{ relativeFromNow(person.lastActiveAt) }}</div>
                    </div>
                </div>
                <dl class="kv kv-block">
                    <div>
                        <dt>近 {{ days }} 天记录</dt>
                        <dd class="num">{{ person.recordCount || 0 }}</dd>
                    </div>
                    <div>
                        <dt>未完成任务</dt>
                        <dd class="num">{{ person.openTaskCount || 0 }}</dd>
                    </div>
                    <div>
                        <dt>未处理告警</dt>
                        <dd class="num alert-count">{{ person.openAlertCount || 0 }}</dd>
                    </div>
                </dl>
                <div class="divider-inset kv-gap"></div>
                <div v-if="personLoading" class="tiny muted">正在读取当日日报…</div>
                <div v-else-if="personSummary" class="doc">
                    <p class="lead">{{ personSummary }}</p>
                </div>
                <div v-else class="empty-line">当日日报还没有生成</div>
            </template>
            <template #footer>
                <button class="btn" type="button" @click="personOpen = false">关闭</button>
            </template>
        </el-dialog>
    </div>
</template>

<style scoped>
.notice {
    padding: var(--space-5) 0 var(--space-1);
}

.nums-row {
    display: flex;
    align-items: flex-end;
    gap: var(--space-4);
    flex-wrap: wrap;
    padding: var(--space-5) 0 var(--space-6);
}

.nums-action {
    margin-left: auto;
}

.donut-col {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: var(--space-4);
    padding-top: var(--space-1);
    min-width: 0;
}

.heat-legend {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    margin-top: var(--space-4);
}

.heat-box {
    width: 100%;
}

.heat-pager {
    margin-left: auto;
    display: flex;
    align-items: center;
    gap: var(--space-2);
}

.chart-empty {
    height: 160px;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: var(--font-size-12);
    color: var(--text-3);
}

.heat-legend i {
    width: 12px;
    height: 12px;
    border-radius: var(--radius-0);
    display: inline-block;
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

.chip-row {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
    min-width: 0;
}

.chip-row .chip {
    max-width: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
}

.dialog-row {
    padding: var(--space-3) 0;
    align-items: flex-start;
}

.dialog-row .dot {
    margin-top: var(--space-2);
}

.risk-sub {
    margin-bottom: var(--space-2);
}

.person-head {
    display: flex;
    align-items: center;
    gap: var(--space-3);
    margin-bottom: var(--space-4);
}

.kv-block {
    margin-bottom: var(--space-4);
}

.kv-gap {
    margin-bottom: var(--space-4);
}

.alert-count {
    color: var(--color-danger);
}

@media (max-width: 1180px) {
    .chart-grid {
        grid-template-columns: 1fr;
        gap: var(--space-6);
    }
}
</style>
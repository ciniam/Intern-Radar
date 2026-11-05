<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
    bindChat,
    errorText,
    fetchAuthStatus,
    fetchBindings,
    fetchOauthUrl,
    fetchRecords,
    fetchReportStats,
    probeChat,
    unbindChat
} from '../api'
import { useAuth } from '../store/auth'
import { formatDateTime, parseMetadata, todayISO } from '../utils/format'

const auth = useAuth()

const loading = ref(false)
const loadError = ref('')
const bindings = ref([])
const chatRecords = ref([])
const authStatus = ref('')
const weekTotal = ref(0)

const bindOpen = ref(false)
const binding = ref(false)
const probing = ref(false)
const probeOk = ref(null)
const probeRaw = ref('')
const showRaw = ref(false)
const form = ref({ chatId: '', name: '', identity: 'USER' })

const summaryOpen = ref(false)
const activeRecord = ref(null)

const userId = computed(() => auth.userId.value)
const today = todayISO()

const authorized = computed(() => {
    const text = authStatus.value || ''
    if (text.includes('未授权')) {
        return false
    }
    return text.includes('已授权')
})
const authLabel = computed(() => (authorized.value ? '飞书 · 已授权' : '飞书 · 未授权'))

const summaries = computed(() => (chatRecords.value || [])
    .slice()
    .sort((left, right) => String(right.occurredAt).localeCompare(String(left.occurredAt)))
    .map(record => {
        const meta = parseMetadata(record.metadata)
        return {
            record,
            chatName: meta.chatName || '',
            messageCount: Number(meta.messageCount) || 0
        }
    }))

onMounted(load)

async function load() {
    loading.value = true
    loadError.value = ''
    try {
        const [list, records, status, stats] = await Promise.all([
            fetchBindings(userId.value),
            fetchRecords(userId.value, today),
            fetchAuthStatus(userId.value),
            fetchReportStats(userId.value, addDaysISO(today, -6), today)
        ])
        bindings.value = list || []
        chatRecords.value = (records || []).filter(item => item.source === 'feishu-chat')
        authStatus.value = typeof status === 'string' ? status : ''
        weekTotal.value = ((stats && stats.days) || []).reduce((sum, day) => sum + (day.total || 0), 0)
    } catch (e) {
        loadError.value = errorText(e)
    } finally {
        loading.value = false
    }
}

function addDaysISO(iso, offset) {
    const date = new Date(`${iso}T00:00:00`)
    date.setDate(date.getDate() + offset)
    const month = `${date.getMonth() + 1}`.padStart(2, '0')
    const day = `${date.getDate()}`.padStart(2, '0')
    return `${date.getFullYear()}-${month}-${day}`
}

function chatTypeLabel(value) {
    const map = { P2P: '单聊', p2p: '单聊', GROUP: '群聊', group: '群聊' }
    return map[value] || value || '—'
}

function identityLabel(value) {
    const map = { USER: '用户身份', APP: '应用身份' }
    return map[value] || value || '—'
}

function timeOf(record) {
    return formatDateTime(record.occurredAt).slice(11, 16)
}

async function openAuthUrl() {
    try {
        const url = await fetchOauthUrl(userId.value)
        window.open(url, '_blank')
    } catch (e) {
        ElMessage.error(errorText(e))
    }
}

function openBind() {
    form.value = { chatId: '', name: '', identity: 'USER' }
    probeOk.value = null
    probeRaw.value = ''
    showRaw.value = false
    bindOpen.value = true
}

async function runProbe() {
    if (!form.value.chatId.trim()) {
        ElMessage.warning('请填写会话标识')
        return
    }
    probing.value = true
    try {
        const result = await probeChat({
            chatId: form.value.chatId.trim(),
            identity: form.value.identity,
            userId: userId.value,
            days: 7
        })
        probeRaw.value = JSON.stringify(result, null, 2)
        probeOk.value = true
    } catch (e) {
        probeRaw.value = errorText(e)
        probeOk.value = false
    } finally {
        probing.value = false
    }
}

async function runBind() {
    if (!form.value.chatId.trim()) {
        ElMessage.warning('请填写会话标识')
        return
    }
    binding.value = true
    try {
        const result = await bindChat({
            userId: userId.value,
            chatId: form.value.chatId.trim(),
            name: form.value.name || undefined,
            identity: form.value.identity
        })
        if (result.bound) {
            ElMessage.success(result.hint || '绑定成功')
            bindOpen.value = false
            await load()
        } else {
            probeOk.value = false
            probeRaw.value = result.reason || '绑定未完成'
            ElMessage.warning(result.reason || '绑定未完成')
        }
    } catch (e) {
        probeOk.value = false
        probeRaw.value = errorText(e)
    } finally {
        binding.value = false
    }
}

async function probeRow(row) {
    try {
        await probeChat({
            chatId: row.chatId,
            identity: row.identity,
            userId: row.userId,
            days: 7
        })
        ElMessage.success('探针通过')
        await load()
    } catch (e) {
        ElMessage.error(errorText(e))
    }
}

function viewSummaryFor(row) {
    const hit = summaries.value.find(item =>
        (item.chatName && item.chatName === row.name) || (item.record.chatId && item.record.chatId === row.chatId)
    )
    if (hit) {
        activeRecord.value = hit.record
        summaryOpen.value = true
        return
    }
    if (row.lastSummary) {
        activeRecord.value = {
            title: `${row.name || row.chatId} · 最近摘要`,
            content: row.lastSummary,
            occurredAt: row.lastSummaryAt,
            metadata: JSON.stringify({ chatName: row.name || row.chatId })
        }
        summaryOpen.value = true
        return
    }
    ElMessage.info('该会话还没有摘要')
}

function lastSummaryLabel(row) {
    if (!row.lastSummaryAt) {
        return '—'
    }
    const time = formatDateTime(row.lastSummaryAt)
    return time.slice(0, 10) === today ? `今天 ${time.slice(11, 16)}` : time.slice(5, 16)
}

function openSummary(item) {
    activeRecord.value = item.record
    summaryOpen.value = true
}

async function remove(row) {
    try {
        await unbindChat(row.id)
        ElMessage.success('已解绑')
        await load()
    } catch (e) {
        ElMessage.error(errorText(e))
    }
}

function rowAction(command, row) {
    if (command === 'probe') {
        probeRow(row)
    } else if (command === 'summary') {
        viewSummaryFor(row)
    } else if (command === 'unbind') {
        remove(row)
    }
}

function summarySub(item) {
    return `${timeOf(item.record)} · ${item.messageCount} 条消息`
}

function drawerSub(record) {
    const meta = parseMetadata(record.metadata)
    const parts = [formatDateTime(record.occurredAt), meta.chatName || '会话']
    if (Number(meta.messageCount)) {
        parts.push(`${Number(meta.messageCount)} 条消息`)
    }
    return parts.join(' · ')
}
</script>

<template>
    <div v-loading="loading" class="page">
        <header class="page-head">
            <div>
                <div class="eyebrow">{{ authLabel }}</div>
                <h1 class="h1">会话绑定</h1>
            </div>
            <div class="page-actions">
                <button class="btn" type="button" @click="openAuthUrl">飞书授权</button>
                <button class="btn" type="button" @click="openBind">新增绑定</button>
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
                        <span class="num-label">已绑定会话</span>
                        <span class="num-value num">{{ bindings.length }}</span>
                    </div>
                    <div class="num-item">
                        <span class="num-label">今日摘要</span>
                        <span class="num-value num">{{ chatRecords.length }}</span>
                    </div>
                    <div class="num-item">
                        <span class="num-label">本周入库</span>
                        <span class="num-value num">{{ weekTotal }}</span>
                    </div>
                </div>
                <div class="meta">
                    <span>只存摘要，不存原文</span>
                </div>
            </div>
            <div class="divider-full"></div>

            <section class="section">
                <div class="split split-340">
                    <div class="pane-left">
                        <div class="sec-head">
                            <span class="sec-title">已绑定</span>
                            <span class="sec-hint">{{ bindings.length }} 条</span>
                        </div>
                        <table v-if="bindings.length" class="table bind-table">
                            <thead>
                                <tr>
                                    <th style="width: 34%">备注名</th>
                                    <th style="width: 18%">类型</th>
                                    <th style="width: 12%">校验</th>
                                    <th style="width: 18%">最近摘要</th>
                                    <th class="act"></th>
                                </tr>
                            </thead>
                            <tbody>
                                <tr v-for="row in bindings" :key="row.id">
                                    <td>
                                        <div class="strong">{{ row.name || row.chatId }}</div>
                                        <div class="tiny muted mono chat-id">{{ row.chatId }}</div>
                                    </td>
                                    <td>
                                        <div class="small">{{ chatTypeLabel(row.chatType) }}</div>
                                        <div class="tiny muted">{{ identityLabel(row.identity) }}</div>
                                    </td>
                                    <td>
                                        <span class="tag" :class="row.verified ? 'tag-mint' : 'tag-rose'">
                                            {{ row.verified ? '已通过' : '未通过' }}
                                        </span>
                                    </td>
                                    <td class="muted num">{{ lastSummaryLabel(row) }}</td>
                                    <td class="act">
                                        <el-dropdown trigger="click" @command="command => rowAction(command, row)">
                                            <button class="btn btn-sm btn-ghost dots" type="button">···</button>
                                            <template #dropdown>
                                                <el-dropdown-menu>
                                                    <el-dropdown-item command="probe">再探针一次</el-dropdown-item>
                                                    <el-dropdown-item command="summary">查看摘要</el-dropdown-item>
                                                    <el-dropdown-item command="unbind" divided>解绑</el-dropdown-item>
                                                </el-dropdown-menu>
                                            </template>
                                        </el-dropdown>
                                    </td>
                                </tr>
                            </tbody>
                        </table>
                        <div v-else class="empty-line">
                            还没有绑定任何会话，先把常用的单聊或群聊加进来
                            <button class="btn btn-sm" type="button" @click="openBind">新增绑定</button>
                        </div>
                    </div>

                    <div class="pane-right">
                        <div class="sec-head">
                            <span class="sec-title">今日摘要</span>
                            <span class="sec-hint">{{ chatRecords.length }} 条</span>
                        </div>
                        <div v-if="summaries.length" class="rows rows-divided">
                            <div
                                v-for="item in summaries"
                                :key="item.record.id"
                                class="row-item"
                                role="button"
                                tabindex="0"
                                @click="openSummary(item)"
                                @keydown.enter="openSummary(item)"
                            >
                                <div class="row-main">
                                    <span class="row-title">{{ item.record.title }}</span>
                                    <span class="row-sub">{{ summarySub(item) }}</span>
                                </div>
                                <span class="chev">›</span>
                            </div>
                        </div>
                        <div v-else class="empty-line">今日还没有采集到会话摘要</div>
                    </div>
                </div>
            </section>
        </template>

        <el-dialog v-model="bindOpen" title="新增绑定" width="520">
            <div class="dialog-sub bind-sub">绑定前会先探针一次，标识或身份不对会当场提示</div>
            <div class="field">
                <span class="field-label">会话标识</span>
                <div class="field-body">
                    <el-input v-model="form.chatId" placeholder="oc_ 开头的一串标识" />
                    <span class="field-hint">从飞书客户端会话设置页复制</span>
                </div>
            </div>
            <div class="field">
                <span class="field-label">备注名</span>
                <div class="field-body">
                    <el-input v-model="form.name" placeholder="例如：与导师的单聊" />
                </div>
            </div>
            <div class="field">
                <span class="field-label">调用身份</span>
                <div class="field-body">
                    <el-select v-model="form.identity" class="w-full">
                        <el-option label="用户身份 · 读本人参与的会话（含单聊）" value="USER" />
                        <el-option label="应用身份 · 只读机器人所在的群与单聊" value="APP" />
                    </el-select>
                </div>
            </div>
            <div class="divider-inset probe-divider"></div>
            <div v-if="probeOk !== null" class="probe-line">
                <span class="dot" :class="{ rose: probeOk === false }"></span>
                <span>{{ probeOk === true ? '探针通过' : `探针失败：${probeRaw}` }}</span>
                <button v-if="probeOk === true" class="btn btn-sm btn-ghost probe-toggle" type="button" @click="showRaw = !showRaw">
                    {{ showRaw ? '收起响应' : '原始响应' }}
                </button>
            </div>
            <pre v-show="probeOk === true && showRaw" class="probe-pre mono">{{ probeRaw }}</pre>
            <template #footer>
                <button class="btn" type="button" :disabled="probing || binding" @click="runProbe">仅探针</button>
                <button class="btn btn-primary" type="button" :disabled="probing" @click="runBind">
                    {{ binding ? '正在绑定…' : '绑定' }}
                </button>
            </template>
        </el-dialog>

        <el-drawer v-model="summaryOpen" size="480">
            <template #header>
                <div>
                    <div class="dialog-title">{{ activeRecord ? activeRecord.title : '摘要详情' }}</div>
                    <div v-if="activeRecord" class="dialog-sub">{{ drawerSub(activeRecord) }}</div>
                </div>
            </template>
            <template v-if="activeRecord">
                <p class="summary-body">{{ activeRecord.content }}</p>
                <div class="divider-inset drawer-divider"></div>
                <div class="tiny muted drawer-note">摘要由模型生成，可能不完整，仅用于日报归集；不保存聊天原文。</div>
            </template>
            <div v-else class="empty-line">该会话今天还没有摘要</div>
            <template #footer>
                <button class="btn" type="button" @click="summaryOpen = false">关闭</button>
            </template>
        </el-drawer>
    </div>
</template>

<style scoped>
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

.empty-line {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-7) 0;
    font-size: var(--font-size-12);
    color: var(--text-3);
}

.bind-table td {
    white-space: normal;
}

.chat-id {
    word-break: break-all;
}

.bind-sub {
    margin-bottom: var(--space-3);
}

.probe-divider {
    margin: var(--space-3) 0;
}

.probe-line {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    font-size: var(--font-size-12);
    padding: var(--space-1) 0;
}

.probe-toggle {
    margin-left: auto;
}

.probe-pre {
    margin: var(--space-2) 0 0;
    padding: var(--space-3);
    background: var(--surface-0);
    border-radius: var(--radius-2);
    font-size: var(--font-size-12);
    line-height: var(--line-height-base);
    max-height: 32vh;
    overflow: auto;
    white-space: pre-wrap;
    word-break: break-all;
}

.summary-body {
    font-size: var(--font-size-13);
    line-height: var(--line-height-loose);
    color: var(--text-1);
}

.drawer-divider {
    margin: var(--space-4) 0 var(--space-3);
}

.drawer-note {
    line-height: var(--line-height-loose);
}
</style>
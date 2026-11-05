<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import {
    changePassword,
    errorText,
    fetchDevices,
    fetchProfile,
    fetchReportStats,
    logoutAllDevices,
    logoutDevice,
    saveProfile
} from '../api'
import { useAuth } from '../store/auth'
import { formatDate, formatDateTime, relativeFromNow, todayISO } from '../utils/format'

const auth = useAuth()
const router = useRouter()

const ROLE_LABEL = { INTERN: '实习生', MENTOR: '导师', ADMIN: '管理员' }
const HEAT_TOKENS = ['--chart-heat-0', '--chart-heat-1', '--chart-heat-2', '--chart-heat-3', '--chart-heat-4']

const today = todayISO()
const loading = ref(false)
const loadError = ref('')
const profile = ref(null)
const stats = ref(null)
const teamName = ref('')
const joinedAt = ref('')
const lastLoginAt = ref('')
const passwordUpdatedAt = ref('')
const statDays = ref([])
const devices = ref([])

const editOpen = ref(false)
const pwdOpen = ref(false)
const deviceOpen = ref(false)
const saving = ref(false)
const pwdSaving = ref(false)
const draft = ref({ name: '' })
const pwd = ref({ oldPassword: '', newPassword: '', confirm: '' })

const initial = computed(() => (profile.value?.name || auth.state.user?.name || '我').slice(0, 1))
const roleLabel = computed(() => ROLE_LABEL[profile.value?.role] || profile.value?.role || '-')
const feishuBound = computed(() => !!profile.value?.feishuOpenId)
const joinedMonth = computed(() => (joinedAt.value ? String(joinedAt.value).slice(0, 7) : '-'))
const windowDays = computed(() => (stats.value?.windowDays ?? '-'))

const heatDays = computed(() => {
    const map = new Map(statDays.value.map(day => [day.date, day.total]))
    const list = []
    for (let offset = 13; offset >= 0; offset--) {
        const iso = addDaysISO(today, -offset)
        list.push({ date: iso, total: map.get(iso) || 0 })
    }
    const max = list.reduce((acc, day) => Math.max(acc, day.total), 0)
    return list.map(day => ({
        ...day,
        level: day.total === 0 ? 0 : Math.min(4, Math.max(1, Math.ceil((day.total / (max || 1)) * 4))),
        token: null
    })).map(day => ({ ...day, token: HEAT_TOKENS[day.level] }))
})

const recordDays = computed(() => heatDays.value.filter(day => day.total > 0).length)
const longestStreak = computed(() => {
    let best = 0
    let current = 0
    heatDays.value.forEach(day => {
        if (day.total > 0) {
            current += 1
            best = Math.max(best, current)
        } else {
            current = 0
        }
    })
    return best
})

onMounted(load)

async function load() {
    loading.value = true
    loadError.value = ''
    try {
        const [data, deviceList] = await Promise.all([
            fetchProfile(),
            fetchDevices()
        ])
        profile.value = data.user
        stats.value = data.stats
        teamName.value = data.teamName || ''
        joinedAt.value = data.joinedAt || ''
        lastLoginAt.value = data.lastLoginAt || ''
        passwordUpdatedAt.value = data.passwordUpdatedAt || ''
        devices.value = deviceList || []
        draft.value = { name: data.user.name || '' }
        const statsView = await fetchReportStats(auth.userId.value, addDaysISO(today, -13), today)
        statDays.value = statsView?.days || []
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

function openEdit() {
    draft.value = { name: profile.value?.name || '' }
    editOpen.value = true
}

async function save() {
    saving.value = true
    try {
        await saveProfile({ name: draft.value.name, email: profile.value?.email })
        ElMessage.success('资料已保存')
        editOpen.value = false
        await load()
    } catch (e) {
        ElMessage.error(errorText(e))
    } finally {
        saving.value = false
    }
}

async function savePassword() {
    if (!pwd.value.oldPassword || !pwd.value.newPassword) {
        ElMessage.warning('请填写当前密码与新密码')
        return
    }
    if (!/^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).{8,}$/.test(pwd.value.newPassword)) {
        ElMessage.warning('新密码至少 8 位，且要包含大小写字母与数字')
        return
    }
    if (pwd.value.newPassword !== pwd.value.confirm) {
        ElMessage.warning('两次输入的新密码不一致')
        return
    }
    pwdSaving.value = true
    try {
        await changePassword({
            oldPassword: pwd.value.oldPassword,
            newPassword: pwd.value.newPassword
        })
        pwd.value = { oldPassword: '', newPassword: '', confirm: '' }
        pwdOpen.value = false
        ElMessage.success('密码已更新，其它设备已下线')
        await load()
    } catch (e) {
        ElMessage.error(errorText(e))
    } finally {
        pwdSaving.value = false
    }
}

async function kick(id) {
    try {
        await ElMessageBox.confirm('该设备上的登录会立即失效，确定退出？', '退出该设备', { type: 'warning' })
    } catch (e) {
        return
    }
    try {
        await logoutDevice(id)
        devices.value = (await fetchDevices()) || []
        ElMessage.success('已退出该设备')
    } catch (e) {
        ElMessage.error(errorText(e))
    }
}

async function kickAll() {
    try {
        await ElMessageBox.confirm('所有设备都会退出登录，包括当前这台，确定继续？', '退出全部设备', { type: 'warning' })
    } catch (e) {
        return
    }
    try {
        await logoutAllDevices()
        auth.clear()
        ElMessage.success('已退出全部设备')
        router.push('/login')
    } catch (e) {
        ElMessage.error(errorText(e))
    }
}
</script>

<template>
    <div v-loading="loading" class="page">
        <header class="page-head">
            <div>
                <div class="eyebrow">账号 {{ profile?.userId ?? '-' }} · 加入于 {{ joinedMonth }}</div>
                <h1 class="h1">个人中心</h1>
            </div>
            <div class="page-actions">
                <button class="btn" type="button" @click="openEdit">编辑资料</button>
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
            <section class="identity">
                <div class="avatar avatar-lg">{{ initial }}</div>
                <div class="identity-main">
                    <div class="identity-line">
                        <span class="identity-name">{{ profile?.name || '-' }}</span>
                        <span class="tag tag-brand">{{ roleLabel }}</span>
                    </div>
                    <div class="meta">
                        <span>{{ profile?.email || '未填写' }}</span>
                        <span class="sep"></span>
                        <span>{{ feishuBound ? '飞书已绑定' : '未绑定飞书' }}</span>
                    </div>
                </div>
                <div class="nums identity-nums">
                    <div class="num-item">
                        <span class="num-label">近 {{ windowDays }} 天记录</span>
                        <span class="num-value num">{{ stats?.recordCount ?? 0 }}</span>
                    </div>
                    <div class="num-item">
                        <span class="num-label">报告</span>
                        <span class="num-value num">{{ stats?.reportCount ?? 0 }}</span>
                    </div>
                    <div class="num-item danger">
                        <span class="num-label">未处理告警</span>
                        <span class="num-value num">{{ stats?.openAlertCount ?? 0 }}</span>
                    </div>
                    <div class="num-item">
                        <span class="num-label">问答</span>
                        <span class="num-value num">{{ stats?.questionCount ?? 0 }}</span>
                    </div>
                </div>
            </section>
            <div class="divider-full"></div>

            <section class="section">
                <div class="sec-head">
                    <span class="sec-title">账号信息</span>
                </div>
                <dl class="kv">
                    <div>
                        <dt>标识</dt>
                        <dd class="mono num">{{ profile?.userId ?? '-' }}</dd>
                    </div>
                    <div>
                        <dt>角色</dt>
                        <dd>{{ roleLabel }}</dd>
                    </div>
                    <div>
                        <dt>所在团队</dt>
                        <dd>{{ teamName || '-' }}</dd>
                    </div>
                    <div>
                        <dt>加入时间</dt>
                        <dd class="num">{{ joinedAt ? formatDate(joinedAt) : '-' }}</dd>
                    </div>
                    <div>
                        <dt>上次登录</dt>
                        <dd class="num">{{ lastLoginAt ? formatDateTime(lastLoginAt) : '-' }}</dd>
                    </div>
                </dl>
            </section>
            <div class="divider-full"></div>

            <section class="section">
                <div class="sec-head">
                    <span class="sec-title">最近两周</span>
                    <span class="sec-hint">深色代表当天有工作记录</span>
                </div>
                <div class="heat-row">
                    <div
                        v-for="day in heatDays"
                        :key="day.date"
                        class="heat-cell"
                        :style="{ background: `var(${day.token})` }"
                        :title="`${day.date} · ${day.total} 条`"
                    ></div>
                </div>
                <div class="tiny muted heat-summary">共 {{ recordDays }} 天有记录 · 最长连续 {{ longestStreak }} 天</div>
            </section>
            <div class="divider-full"></div>

            <section class="section">
                <div class="sec-head">
                    <span class="sec-title">安全</span>
                </div>
                <div class="rows rows-divided">
                    <div
                        class="row-item"
                        role="button"
                        tabindex="0"
                        @click="pwdOpen = true"
                        @keydown.enter="pwdOpen = true"
                    >
                        <div class="row-main">
                            <span class="row-title">登录密码</span>
                            <div class="row-sub">上次修改 {{ passwordUpdatedAt ? relativeFromNow(passwordUpdatedAt) : '从未' }}</div>
                        </div>
                        <div class="row-side"><span class="btn btn-sm btn-ghost">修改</span></div>
                    </div>
                    <div
                        class="row-item"
                        role="button"
                        tabindex="0"
                        @click="deviceOpen = true"
                        @keydown.enter="deviceOpen = true"
                    >
                        <div class="row-main">
                            <span class="row-title">登录设备</span>
                            <div class="row-sub">{{ devices.length }} 台在登录</div>
                        </div>
                        <div class="row-side"><span class="btn btn-sm btn-ghost">管理</span></div>
                    </div>
                </div>
            </section>
        </template>

        <el-dialog v-model="editOpen" title="编辑资料" width="420">
            <div class="dialog-hint tiny muted">报告与推送里会显示这个名字</div>
            <div class="form-item">
                <span class="form-label muted">姓名</span>
                <el-input v-model="draft.name" placeholder="报告与推送里显示的名字" />
            </div>
            <div class="form-item">
                <span class="form-label muted">邮箱</span>
                <el-input :model-value="profile?.email || '未设置'" disabled />
                <div class="tiny muted">邮箱是登录名，不可修改</div>
            </div>
            <template #footer>
                <button class="btn btn-ghost" type="button" @click="editOpen = false">取消</button>
                <button class="btn btn-primary" type="button" :disabled="saving" @click="save">保存</button>
            </template>
        </el-dialog>

        <el-dialog v-model="pwdOpen" title="修改密码" width="420">
            <div class="dialog-hint tiny muted">至少 8 位，含大小写与数字</div>
            <div class="form-item">
                <span class="form-label muted">当前密码</span>
                <el-input v-model="pwd.oldPassword" type="password" show-password placeholder="当前密码" />
            </div>
            <div class="form-item">
                <span class="form-label muted">新密码</span>
                <el-input v-model="pwd.newPassword" type="password" show-password placeholder="至少 8 位" />
            </div>
            <div class="form-item">
                <span class="form-label muted">再输一次</span>
                <el-input v-model="pwd.confirm" type="password" show-password />
            </div>
            <template #footer>
                <button class="btn btn-ghost" type="button" @click="pwdOpen = false">取消</button>
                <button class="btn btn-primary" type="button" :disabled="pwdSaving" @click="savePassword">保存新密码</button>
            </template>
        </el-dialog>

        <el-drawer v-model="deviceOpen" title="登录设备" size="480">
            <div class="tiny muted drawer-hint">{{ devices.length }} 台 · 退出后需要重新登录</div>
            <div v-if="devices.length" class="rows rows-divided">
                <div v-for="item in devices" :key="item.id" class="row-item">
                    <div class="row-main">
                        <span class="row-title">
                            {{ item.device || '未知设备' }}
                            <span v-if="item.current" class="tag tag-brand device-tag">当前</span>
                        </span>
                        <div class="row-sub">
                            <span class="mono">{{ item.ip || '未知地址' }}</span>
                            · 登录于 {{ item.loginAt ? formatDateTime(item.loginAt) : '-' }}
                            · 到期 {{ item.expireAt ? formatDate(item.expireAt) : '-' }}
                        </div>
                    </div>
                    <div v-if="!item.current" class="row-side">
                        <button class="btn btn-sm btn-ghost" type="button" @click="kick(item.id)">退出</button>
                    </div>
                </div>
            </div>
            <div v-else class="empty">没有有效的登录设备</div>
            <template #footer>
                <button class="btn btn-danger drawer-danger" type="button" @click="kickAll">退出全部设备</button>
                <button class="btn btn-ghost" type="button" @click="deviceOpen = false">关闭</button>
            </template>
        </el-drawer>
    </div>
</template>

<style scoped>
.notice {
    padding: var(--space-5) 0 var(--space-1);
}

.identity {
    display: flex;
    align-items: center;
    gap: var(--space-5);
    padding: var(--space-5) 0;
    flex-wrap: wrap;
}

.identity-main {
    min-width: 0;
}

.identity-line {
    display: flex;
    align-items: center;
    gap: var(--space-2);
}

.identity-name {
    font-size: var(--font-size-20);
    font-weight: var(--fw-semibold);
    letter-spacing: -.01em;
}

.identity-nums {
    margin-left: auto;
}

.avatar-lg {
    width: var(--size-avatar-lg);
    height: var(--size-avatar-lg);
    font-size: var(--font-size-20);
}

.heat-row {
    display: flex;
    gap: var(--space-2);
    flex-wrap: wrap;
}

.heat-cell {
    width: var(--space-6);
    height: var(--space-6);
    border-radius: var(--radius-0);
}

.heat-summary {
    margin-top: var(--space-3);
}

.dialog-hint {
    margin-bottom: var(--space-3);
}

.form-item {
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
    margin-bottom: var(--space-4);
}

.form-label {
    font-size: var(--font-size-12);
}

.drawer-hint {
    margin-bottom: var(--space-3);
}

.device-tag {
    margin-left: var(--space-2);
}

.drawer-danger {
    margin-right: auto;
}

@media (max-width: 1180px) {
    .identity-nums {
        margin-left: 0;
    }
}
</style>
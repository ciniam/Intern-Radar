<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import {
    approveTeamRequest,
    createTeam,
    errorText,
    fetchMyJoinRequests,
    fetchTeam,
    fetchTeamMembers,
    fetchTeamRequests,
    joinTeam,
    rejectTeamRequest,
    resetMemberPassword,
    rotateInviteCode
} from '../api'
import { useAuth } from '../store/auth'
import { formatDate, formatDateTime, relativeFromNow } from '../utils/format'

const auth = useAuth()
const router = useRouter()

const ROLE_LABEL = { INTERN: '实习生', MENTOR: '导师', ADMIN: '管理员' }
const STATUS_LABEL = { PENDING: '待审批', APPROVED: '已通过', REJECTED: '已拒绝' }

const loading = ref(false)
const loadError = ref('')
const creating = ref(false)
const joining = ref(false)
const inviteOpen = ref(false)
const approveOpen = ref(false)
const tempOpen = ref(false)

const team = ref(null)
const members = ref([])
const requests = ref([])
const myRequests = ref([])
const createForm = ref({ name: '', description: '' })
const joinForm = ref({ inviteCode: '', message: '' })
const tempPwd = ref({ name: '', userId: '', password: '', tip: '' })

const joined = computed(() => !!team.value && !!team.value.id)
const canManage = computed(() => !!team.value && (!!team.value.owner || team.value.myRole === 'ADMIN'))
const createdLabel = computed(() => (team.value && team.value.createdAt ? formatDate(team.value.createdAt).slice(0, 7) : ''))
const eyebrow = computed(() => [
    team.value && team.value.name ? team.value.name : '',
    createdLabel.value ? `创建于 ${createdLabel.value}` : ''
].filter(Boolean).join(' · '))
const memberTotal = computed(() => (team.value && team.value.memberCount) || members.value.length)
const internCount = computed(() => members.value.filter(item => item.role === 'INTERN').length)
const mentorCount = computed(() => members.value.filter(item => item.role === 'MENTOR').length)
const activeCount = computed(() => members.value.filter(item => Number(item.recordCount) > 0).length)
const pendingTotal = computed(() => {
    const fromTeam = Number(team.value && team.value.pendingCount)
    return Number.isFinite(fromTeam) && fromTeam > 0 ? fromTeam : requests.value.length
})
const latestApplication = computed(() => {
    if (myRequests.value.length) {
        return myRequests.value[0]
    }
    const status = team.value && team.value.joinStatus
    return status ? { id: 'latest', status } : null
})
const latestDot = computed(() => {
    const status = latestApplication.value ? latestApplication.value.status : ''
    if (status === 'REJECTED') {
        return 'rose'
    }
    if (status === 'PENDING') {
        return 'amber'
    }
    return ''
})

onMounted(load)

async function load() {
    loading.value = true
    loadError.value = ''
    try {
        const current = await fetchTeam()
        team.value = current
        if (current && current.id) {
            const manage = !!current.owner || current.myRole === 'ADMIN'
            members.value = (await fetchTeamMembers()) || []
            requests.value = manage ? (await fetchTeamRequests()) || [] : []
        } else {
            members.value = []
            requests.value = []
            myRequests.value = (await fetchMyJoinRequests()) || []
        }
    } catch (e) {
        loadError.value = errorText(e)
    } finally {
        loading.value = false
    }
}

async function submitCreate() {
    if (!createForm.value.name.trim()) {
        ElMessage.warning('请填写团队名称')
        return
    }
    creating.value = true
    try {
        await createTeam({
            name: createForm.value.name.trim(),
            description: createForm.value.description
        })
        ElMessage.success('团队已创建，你是这个团队的管理员')
        createForm.value = { name: '', description: '' }
        await load()
    } catch (e) {
        ElMessage.error(errorText(e))
    } finally {
        creating.value = false
    }
}

async function submitJoin() {
    if (!joinForm.value.inviteCode.trim()) {
        ElMessage.warning('请填写邀请码')
        return
    }
    joining.value = true
    try {
        await joinTeam({
            inviteCode: joinForm.value.inviteCode.trim(),
            message: joinForm.value.message
        })
        ElMessage.success('申请已提交，等待团队创建人审批')
        joinForm.value = { inviteCode: '', message: '' }
        await load()
    } catch (e) {
        ElMessage.error(errorText(e))
    } finally {
        joining.value = false
    }
}

function onRowCommand(command, row) {
    if (command === 'report') {
        router.push({ path: '/reports', query: { userId: row.userId } })
        return
    }
    if (command === 'reset') {
        resetMember(row)
    }
}

async function approve(item, role) {
    try {
        await approveTeamRequest(item.id, role || 'INTERN')
        ElMessage.success(`已通过 ${item.userName || item.userId} 的申请`)
        await load()
    } catch (e) {
        ElMessage.error(errorText(e))
    }
}

async function refuse(item) {
    try {
        await rejectTeamRequest(item.id)
        ElMessage.success('已拒绝该申请')
        await load()
    } catch (e) {
        ElMessage.error(errorText(e))
    }
}

async function resetMember(row) {
    try {
        await ElMessageBox.confirm(
            `将为 ${row.name || row.userId} 生成一次性临时密码，对方现有登录会立即失效。`,
            '重置密码',
            { type: 'warning', confirmButtonText: '生成临时密码', cancelButtonText: '取消' }
        )
    } catch (e) {
        return
    }
    try {
        const result = await resetMemberPassword(row.userId)
        tempPwd.value = {
            name: row.name || '',
            userId: row.userId,
            password: result.temporaryPassword,
            tip: result.tip || ''
        }
        tempOpen.value = true
    } catch (e) {
        ElMessage.error(errorText(e))
    }
}

async function rotate() {
    try {
        await ElMessageBox.confirm('重新生成后旧邀请码立即失效，确定继续？', '更换邀请码', { type: 'warning' })
    } catch (e) {
        return
    }
    try {
        const updated = await rotateInviteCode()
        if (updated && updated.id) {
            team.value = updated
        } else {
            await load()
        }
        ElMessage.success('邀请码已更新')
    } catch (e) {
        ElMessage.error(errorText(e))
    }
}

async function copyCode() {
    await copyText(team.value && team.value.inviteCode ? team.value.inviteCode : '')
}

async function copyInvite() {
    if (!team.value) {
        return
    }
    await copyText(`邀请你加入 ${team.value.name} 团队，邀请码 ${team.value.inviteCode}`)
}

async function copyText(text) {
    if (!text) {
        return
    }
    try {
        await navigator.clipboard.writeText(text)
        ElMessage.success('已复制')
    } catch (e) {
        ElMessage.warning('浏览器不允许自动复制，请手动选择')
    }
}

function roleLabel(role) {
    return ROLE_LABEL[role] || role || '-'
}

function statusLabel(status) {
    return STATUS_LABEL[status] || status || '-'
}

function requestTime(item) {
    return formatDateTime(item.createdAt).slice(5)
}

function memberRecords(row) {
    const value = row.recordCount
    return value === null || value === undefined ? '—' : value
}

function memberTasks(row) {
    const value = row.openTaskCount
    return value === null || value === undefined ? '—' : value
}

function initial(name) {
    return String(name || '?').trim().slice(0, 1) || '?'
}
</script>

<template>
    <div v-loading="loading" class="page">
        <header class="page-head">
            <div>
                <div class="eyebrow">{{ joined ? eyebrow : '还没有加入团队' }}</div>
                <h1 class="h1">我的团队</h1>
            </div>
            <div v-if="joined" class="page-actions">
                <button v-if="canManage" class="btn" type="button" @click="approveOpen = true">
                    待审批 <span class="tag tag-rose num">{{ pendingTotal }}</span>
                </button>
                <button class="btn" type="button" @click="inviteOpen = true">邀请成员</button>
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

        <template v-else-if="joined">
            <div class="nums-row">
                <div class="nums">
                    <div class="num-item">
                        <span class="num-label">成员</span>
                        <span class="num-value num">{{ memberTotal }}</span>
                    </div>
                    <div class="num-item">
                        <span class="num-label">实习生</span>
                        <span class="num-value num">{{ internCount }}</span>
                    </div>
                    <div class="num-item">
                        <span class="num-label">导师</span>
                        <span class="num-value num">{{ mentorCount }}</span>
                    </div>
                    <div class="num-item">
                        <span class="num-label">本周活跃</span>
                        <span class="num-value num">{{ activeCount }}<span class="unit">/ {{ memberTotal }}</span></span>
                    </div>
                </div>
                <div class="meta nums-meta">
                    <span class="dot"></span><span>团队正常</span>
                </div>
            </div>
            <div class="divider-full"></div>

            <section class="section">
                <div class="sec-head">
                    <span class="sec-title">成员</span>
                    <span class="sec-hint">{{ memberTotal }} 人</span>
                </div>

                <div v-if="!members.length" class="empty-line">团队里还没有成员，把邀请码发给同事就能加进来</div>
                <table v-else class="table team-table">
                    <thead>
                        <tr>
                            <th>姓名</th>
                            <th class="col-role">角色</th>
                            <th class="col-count num">本周记录</th>
                            <th class="col-count num">未完成</th>
                            <th class="col-active">最近活跃</th>
                            <th class="col-account">账号</th>
                            <th class="col-act"></th>
                        </tr>
                    </thead>
                    <tbody>
                        <tr v-for="row in members" :key="row.userId">
                            <td>
                                <span class="person">
                                    <span class="avatar" :class="{ mentor: row.role === 'MENTOR' }">{{ initial(row.name) }}</span>
                                    <span class="person-text">
                                        <span class="strong">{{ row.name || row.userId }}</span>
                                        <span class="muted tiny person-email">{{ row.email || '—' }}</span>
                                    </span>
                                </span>
                            </td>
                            <td class="muted">{{ roleLabel(row.role) }}</td>
                            <td class="num">{{ memberRecords(row) }}</td>
                            <td class="num">{{ memberTasks(row) }}</td>
                            <td class="muted">{{ relativeFromNow(row.lastActiveAt) }}</td>
                            <td>
                                <span class="tag" :class="row.pending ? 'tag-amber' : 'tag-mint'">
                                    {{ row.pending ? '待激活' : '正常' }}
                                </span>
                            </td>
                            <td class="act">
                                <span v-if="row.userId === auth.userId.value" class="muted tiny">本人</span>
                                <el-dropdown
                                    v-else
                                    trigger="click"
                                    @command="command => onRowCommand(command, row)"
                                >
                                    <button class="btn btn-sm btn-ghost row-menu dots" type="button">···</button>
                                    <template #dropdown>
                                        <el-dropdown-menu>
                                            <el-dropdown-item command="report">查看报告</el-dropdown-item>
                                            <el-dropdown-item v-if="canManage" command="reset">重置密码</el-dropdown-item>
                                        </el-dropdown-menu>
                                    </template>
                                </el-dropdown>
                            </td>
                        </tr>
                    </tbody>
                </table>
            </section>
        </template>

        <template v-else>
            <div v-if="latestApplication" class="notice">
                <div class="callout">
                    <span class="dot" :class="latestDot"></span>
                    <span>
                        最近一次申请：{{ statusLabel(latestApplication.status) }}
                        <template v-if="latestApplication.teamName"> · {{ latestApplication.teamName }}</template>
                    </span>
                </div>
            </div>

            <section class="section">
                <div class="join-grid">
                    <div>
                        <div class="h2">创建一个团队</div>
                        <div class="small muted join-sub">建好后把邀请码发给同事</div>
                        <div class="field">
                            <label class="field-label" for="team-name">团队名称</label>
                            <div class="field-body">
                                <input
                                    id="team-name"
                                    v-model="createForm.name"
                                    class="input w-full"
                                    maxlength="32"
                                    placeholder="例如：MES 项目组"
                                />
                            </div>
                        </div>
                        <div class="field">
                            <label class="field-label" for="team-desc">团队说明</label>
                            <div class="field-body">
                                <input
                                    id="team-desc"
                                    v-model="createForm.description"
                                    class="input w-full"
                                    placeholder="选填，一句话说明"
                                />
                            </div>
                        </div>
                        <div class="form-actions">
                            <button class="btn btn-primary" type="button" :disabled="creating" @click="submitCreate">
                                创建团队
                            </button>
                        </div>
                    </div>

                    <div class="join-sep"></div>

                    <div>
                        <div class="h2">用邀请码加入</div>
                        <div class="small muted join-sub">申请后由团队创建人审批</div>
                        <div class="field">
                            <label class="field-label" for="join-code">邀请码</label>
                            <div class="field-body">
                                <input
                                    id="join-code"
                                    v-model="joinForm.inviteCode"
                                    class="input w-full"
                                    maxlength="16"
                                    placeholder="6 位邀请码"
                                />
                            </div>
                        </div>
                        <div class="field">
                            <label class="field-label" for="join-message">留言</label>
                            <div class="field-body">
                                <input
                                    id="join-message"
                                    v-model="joinForm.message"
                                    class="input w-full"
                                    placeholder="选填"
                                />
                            </div>
                        </div>
                        <div class="form-actions">
                            <button class="btn" type="button" :disabled="joining" @click="submitJoin">
                                提交申请
                            </button>
                        </div>
                    </div>
                </div>
            </section>
        </template>

        <el-dialog v-model="inviteOpen" title="邀请成员" width="420">
            <div class="small muted dialog-tip">把邀请码发给同事，对方申请后在这里审批</div>
            <div class="invite-head">
                <span class="mono invite-code num">{{ team && team.inviteCode ? team.inviteCode : '—' }}</span>
                <button class="btn btn-sm" type="button" @click="copyCode">复制</button>
                <button v-if="canManage" class="btn btn-sm btn-ghost invite-rotate" type="button" @click="rotate">重新生成</button>
            </div>
            <div class="divider-inset invite-line"></div>
            <dl class="kv">
                <div>
                    <dt>有效期</dt>
                    <dd>与团队一致</dd>
                </div>
                <div>
                    <dt>可加入人数</dt>
                    <dd>不限</dd>
                </div>
            </dl>
            <template #footer>
                <button class="btn btn-ghost" type="button" @click="inviteOpen = false">关闭</button>
                <button class="btn btn-primary" type="button" @click="copyInvite">复制邀请语</button>
            </template>
        </el-dialog>

        <el-dialog v-model="approveOpen" title="待审批申请" width="520">
            <div class="small muted dialog-tip">{{ requests.length }} 条 · 通过后立即生效</div>
            <div v-if="!requests.length" class="empty-line">现在没有等待审批的申请</div>
            <div v-else class="rows rows-divided">
                <div v-for="item in requests" :key="item.id" class="row-item dialog-row">
                    <div class="row-main">
                        <span class="row-title">{{ item.userName || item.userId }}</span>
                        <span class="row-sub">{{ requestTime(item) }} · {{ item.message || '未留言' }}</span>
                    </div>
                    <div class="row-side">
                        <el-select
                            :model-value="item.role || 'INTERN'"
                            class="w-100"
                            size="small"
                            @update:model-value="value => (item.role = value)"
                        >
                            <el-option label="实习生" value="INTERN" />
                            <el-option label="导师" value="MENTOR" />
                        </el-select>
                        <button class="btn btn-sm btn-ghost" type="button" @click="refuse(item)">拒绝</button>
                        <button class="btn btn-sm btn-primary" type="button" @click="approve(item, item.role)">通过</button>
                    </div>
                </div>
            </div>
            <template #footer>
                <button class="btn btn-ghost" type="button" @click="approveOpen = false">稍后处理</button>
            </template>
        </el-dialog>

        <el-dialog v-model="tempOpen" title="临时密码已生成" width="420">
            <div class="small muted temp-tip">
                把下面的临时密码发给 {{ tempPwd.name || tempPwd.userId }}，本人登录后必须先改密。仅展示这一次。
            </div>
            <div class="temp-password">{{ tempPwd.password }}</div>
            <div v-if="tempPwd.tip" class="small muted temp-hint">{{ tempPwd.tip }}</div>
            <template #footer>
                <button class="btn btn-primary" type="button" @click="tempOpen = false">知道了</button>
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
    padding: var(--space-5) 0 var(--space-6);
}

.nums-meta {
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

.team-table {
    table-layout: fixed;
}

.col-role {
    width: 12%;
}

.col-count {
    width: 12%;
}

.col-active {
    width: 16%;
}

.col-account {
    width: 12%;
}

.col-act {
    width: 6%;
}

.person {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    min-width: 0;
}

.person-text {
    display: flex;
    align-items: baseline;
    gap: var(--space-1);
    min-width: 0;
}

.person-email {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.avatar.mentor {
    background: var(--color-mint-weak);
    color: var(--color-mint-deep);
}

.row-menu {
    color: var(--text-3);
}

.join-grid {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 1px minmax(0, 1fr);
    gap: var(--space-7);
    align-items: start;
}

.join-sep {
    background: var(--line-default);
}

.join-sub {
    margin-bottom: var(--space-3);
}

.dialog-tip {
    margin-bottom: var(--space-3);
}

.dialog-row {
    align-items: flex-start;
    cursor: default;
}

.dialog-row:hover .row-title {
    color: var(--text-1);
}

.invite-head {
    display: flex;
    align-items: center;
    gap: var(--space-3);
    padding: var(--space-1) 0 var(--space-4);
}

.invite-code {
    font-size: var(--font-size-20);
    font-weight: var(--fw-semibold);
}

.invite-rotate {
    margin-left: auto;
}

.invite-line {
    margin-bottom: var(--space-4);
}

.temp-tip {
    line-height: var(--line-height-loose);
    margin-bottom: var(--space-3);
}

.temp-password {
    font-family: var(--font-mono);
    font-size: var(--font-size-20);
    text-align: center;
    padding: var(--space-3);
    background: var(--surface-0);
    border-radius: var(--radius-2);
    font-variant-numeric: tabular-nums;
}

.temp-hint {
    margin-top: var(--space-2);
}

@media (max-width: 1180px) {
    .join-grid {
        grid-template-columns: 1fr;
    }

    .join-sep {
        display: none;
    }
}
</style>
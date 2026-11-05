<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
    errorText,
    fetchDataSources,
    fetchPreferences,
    fetchSettingsOptions,
    fetchSystemSettings,
    fetchTeamSettings,
    savePreferences,
    saveSystemSettings,
    saveTeamSettings,
    scanRepositories,
    testSystemSetting
} from '../api'
import { useAuth } from '../store/auth'
import { RISK_LEVEL_LABEL, RISK_TYPE_LABEL, RISK_TYPE_OPTIONS, formatDateTime } from '../utils/format'

const auth = useAuth()
const route = useRoute()

const CATEGORIES = ['规范', '环境搭建', '业务背景', '排障']
const PUSH_CHANNEL_LABEL = { USER: '私发给我', GROUP: '发到群', BOTH: '两者都发' }
const REPORT_STYLE_LABEL = { DETAIL: '详细', COMPACT: '简洁' }
const ANSWER_STYLE_LABEL = { CONCISE: '精简', STANDARD: '标准', THOROUGH: '详尽' }
const WEEKDAY_LABEL = {
    MON: '周一', TUE: '周二', WED: '周三', THU: '周四',
    FRI: '周五', SAT: '周六', SUN: '周日'
}

const NAV_GROUPS = [
    { label: '个人', items: [{ key: 'profile', label: '个人信息' }, { key: 'learn', label: '学习偏好' }] },
    { label: '采集', items: [{ key: 'git', label: '代码提交' }, { key: 'zentao', label: '内部任务' }] },
    { label: '推送与报告', items: [{ key: 'push', label: '消息与推送' }, { key: 'report', label: '报告偏好' }, { key: 'risk', label: '风险偏好' }] },
    { label: '团队 · 导师', mentor: true, items: [{ key: 'team', label: '团队设置' }, { key: 'system', label: '服务端配置' }] }
]
const GROUP_HINTS = {
    profile: '报告与推送里如何称呼你',
    learn: '知识库问答的默认偏好',
    git: '本地 git 仓库',
    zentao: '内部任务系统',
    push: '推送时间与渠道',
    report: '日报与周报',
    risk: '提醒类型与等级',
    team: '对全团队生效',
    system: '仅导师可见，保存后即时生效'
}

const loading = ref(false)
const loadError = ref('')
const saving = ref(false)
const probing = ref(false)
const statusLoading = ref(false)
const probeResult = ref(null)

const activeKey = ref('profile')
const snapshot = ref('')
const prefDraft = ref(emptyPreference())
const teamDraft = ref(emptyTeamSettings())
const systemDraft = ref(emptySystemSettings())

const form = ref(emptyPreference())
const passwordSet = ref(false)
const sources = ref([])
const team = ref(emptyTeamSettings())
const systemConfig = ref(emptySystemSettings())
const options = ref({})

const newPassword = ref('')
const clearPassword = ref(false)
const repoScanRoot = ref('')
const scannedRepos = ref([])

const isMentor = computed(() => auth.isMentor.value)
const selfId = computed(() => auth.userId.value)

const navGroups = computed(() => NAV_GROUPS.filter(group => !group.mentor || isMentor.value))
const navKeys = computed(() => navGroups.value.flatMap(group => group.items.map(item => item.key)))

const groupTitle = computed(() => {
    for (const group of navGroups.value) {
        const item = group.items.find(entry => entry.key === activeKey.value)
        if (item) {
            return item.label
        }
    }
    return ''
})
const groupHint = computed(() => GROUP_HINTS[activeKey.value] || '')

const isDirty = computed(() => {
    if (activeKey.value === 'team') {
        return JSON.stringify(teamDraft.value) !== snapshot.value
    }
    if (activeKey.value === 'system') {
        return JSON.stringify(systemDraft.value) !== snapshot.value
    }
    if (activeKey.value === 'zentao' && (newPassword.value || clearPassword.value)) {
        return true
    }
    return JSON.stringify(prefDraft.value) !== snapshot.value
})

function normalizeOptions(list, labelMap) {
    const source = Array.isArray(list) && list.length ? list : Object.keys(labelMap)
    return source.map(item => {
        if (item && typeof item === 'object') {
            return { value: item.value, label: item.label }
        }
        return { value: item, label: labelMap[item] || item }
    })
}

const pushChannelOptions = computed(() => normalizeOptions(options.value.pushChannels, PUSH_CHANNEL_LABEL))
const reportStyleOptions = computed(() => normalizeOptions(options.value.reportStyles, REPORT_STYLE_LABEL))
const answerStyleOptions = computed(() => normalizeOptions(options.value.answerStyles, ANSWER_STYLE_LABEL))
const weekdayOptions = computed(() => normalizeOptions(options.value.weekdays, WEEKDAY_LABEL))
const riskLevelOptions = computed(() => normalizeOptions(options.value.riskLevels, RISK_LEVEL_LABEL))
const riskTypeOptions = computed(() => normalizeOptions(options.value.riskTypes, RISK_TYPE_LABEL))
const learnCategoryOptions = computed(() => {
    const list = Array.isArray(options.value.learnCategories) && options.value.learnCategories.length
        ? options.value.learnCategories
        : CATEGORIES
    return list.map(item => (item && typeof item === 'object'
        ? { value: item.value, label: item.label }
        : { value: item, label: item }))
})

function emptyPreference() {
    return {
        userId: '',
        displayName: '',
        feishuOpenId: '',
        gitAuthors: [],
        gitRepositories: [],
        zentaoAccount: '',
        zentaoLoginAllowed: false,
        morningPushEnabled: true,
        morningPushTime: '09:00',
        eveningPushEnabled: true,
        eveningPushTime: '18:30',
        alertPushEnabled: true,
        quietStart: '',
        quietEnd: '',
        pushChannel: 'USER',
        reportStyle: 'DETAIL',
        attachCommitHash: true,
        planFocusLimit: 3,
        weeklyEnabled: true,
        weeklyWeekday: 'FRI',
        weeklyTime: '18:30',
        weeklyScope: 'WATCHED',
        watchInterns: [],
        weeklyTrendEnabled: true,
        riskTypes: [...RISK_TYPE_OPTIONS],
        riskMinLevel: 'LOW',
        learnCategories: [],
        learnTags: [],
        learnAnswerStyle: 'STANDARD'
    }
}

function emptyTeamSettings() {
    return {
        noOutputDays: 2,
        taskStallDays: 3,
        deadlineDays: 2,
        deadlineProgress: 50,
        alertThrottleHours: 6,
        defaultMorningTime: '09:00',
        defaultEveningTime: '18:30',
        knowledgeRootDir: 'knowledge',
        knowledgeMaxReadChars: 6000,
        knowledgeSearchTopK: 8
    }
}

function emptySystemSettings() {
    return {
        feishu: {
            appId: '',
            appSecretSet: false,
            appSecretSource: '未配置',
            webhookUrl: '',
            redirectUri: '',
            loginRedirectUri: '',
            oauthScope: ''
        },
        search: {
            apiKeySet: false,
            apiKeySource: '未配置'
        },
        mail: {
            host: '',
            port: 465,
            username: '',
            passwordSet: false,
            passwordSource: '未配置',
            from: '',
            ssl: true,
            starttls: false,
            configured: false
        },
        zentao: {
            baseUrl: '',
            account: '',
            passwordSet: false,
            passwordSource: '未配置',
            allowLogin: false,
            skipTlsVerify: true
        },
        git: { repositories: [] },
        model: {
            baseUrl: '',
            apiKeySet: false,
            apiKeySource: '未配置',
            name: '',
            temperature: 0.7,
            timeoutSeconds: 60
        }
    }
}

onMounted(async () => {
    await load()
    applyQueryOpen()
})

watch(() => route.query.open, applyQueryOpen)

function applyQueryOpen() {
    const target = typeof route.query.open === 'string' ? route.query.open : ''
    if (target && navKeys.value.includes(target)) {
        switchGroup(target)
    }
}

async function load() {
    loading.value = true
    loadError.value = ''
    try {
        const requests = [fetchPreferences(selfId.value), fetchDataSources(), fetchSettingsOptions()]
        if (isMentor.value) {
            requests.push(fetchTeamSettings(), fetchSystemSettings())
        }
        const result = await Promise.all(requests)
        const [view, status, optionView] = result
        form.value = { ...emptyPreference(), ...view.preference }
        passwordSet.value = !!view.zentaoPasswordSet
        sources.value = status || []
        options.value = optionView || {}
        if (isMentor.value) {
            team.value = { ...emptyTeamSettings(), ...result[3] }
            systemConfig.value = { ...emptySystemSettings(), ...result[4] }
        }
        enterGroup()
    } catch (e) {
        loadError.value = errorText(e)
    } finally {
        loading.value = false
    }
}

function switchGroup(key) {
    activeKey.value = key
    enterGroup()
}

function enterGroup() {
    probeResult.value = null
    newPassword.value = ''
    clearPassword.value = false
    scannedRepos.value = []
    repoScanRoot.value = ''
    prefDraft.value = JSON.parse(JSON.stringify(form.value))
    teamDraft.value = JSON.parse(JSON.stringify(team.value))
    systemDraft.value = JSON.parse(JSON.stringify(systemConfig.value))
    systemDraft.value.feishu.appSecret = null
    systemDraft.value.zentao.password = null
    systemDraft.value.mail.password = null
    snapshot.value = currentDraftJson()
}

function currentDraftJson() {
    if (activeKey.value === 'team') {
        return JSON.stringify(teamDraft.value)
    }
    if (activeKey.value === 'system') {
        return JSON.stringify(systemDraft.value)
    }
    return JSON.stringify(prefDraft.value)
}

async function save() {
    saving.value = true
    try {
        if (activeKey.value === 'team') {
            team.value = { ...emptyTeamSettings(), ...(await saveTeamSettings(teamDraft.value)) }
            ElMessage.success('团队设置已保存')
        } else if (activeKey.value === 'system') {
            const draft = systemDraft.value
            const payload = {
                feishu: {
                    appId: draft.feishu.appId,
                    appSecret: draft.feishu.appSecret,
                    webhookUrl: draft.feishu.webhookUrl,
                    redirectUri: draft.feishu.redirectUri,
                    loginRedirectUri: draft.feishu.loginRedirectUri,
                    oauthScope: draft.feishu.oauthScope
                },
                mail: {
                    host: draft.mail.host,
                    port: draft.mail.port,
                    username: draft.mail.username,
                    password: draft.mail.password,
                    from: draft.mail.from,
                    ssl: draft.mail.ssl,
                    starttls: draft.mail.starttls
                },
                zentao: {
                    baseUrl: draft.zentao.baseUrl,
                    account: draft.zentao.account,
                    password: draft.zentao.password,
                    allowLogin: draft.zentao.allowLogin,
                    skipTlsVerify: draft.zentao.skipTlsVerify
                },
                git: { repositories: draft.git.repositories },
                model: {
                    baseUrl: draft.model.baseUrl,
                    apiKey: draft.model.apiKey,
                    name: draft.model.name,
                    temperature: draft.model.temperature,
                    timeoutSeconds: draft.model.timeoutSeconds
                }
            }
            systemConfig.value = { ...emptySystemSettings(), ...(await saveSystemSettings(payload)) }
            ElMessage.success('服务端配置已保存，即时生效')
        } else {
            const payload = { ...prefDraft.value, userId: selfId.value }
            if (clearPassword.value) {
                payload.zentaoPassword = ''
            } else if (newPassword.value) {
                payload.zentaoPassword = newPassword.value
            } else {
                payload.zentaoPassword = null
            }
            const view = await savePreferences(payload)
            form.value = { ...emptyPreference(), ...view.preference }
            passwordSet.value = !!view.zentaoPasswordSet
            ElMessage.success('配置已保存')
        }
        enterGroup()
    } catch (e) {
        ElMessage.error(errorText(e))
    } finally {
        saving.value = false
    }
}

async function runProbe(domain) {
    probing.value = true
    probeResult.value = null
    try {
        if (activeKey.value === 'git') {
            probeResult.value = await testSystemSetting(
                'git',
                { git: { repositories: prefDraft.value.gitRepositories } },
                prefDraft.value.gitAuthors.join(',')
            )
        } else if (activeKey.value === 'zentao') {
            probeResult.value = await testSystemSetting('zentao', {
                zentao: {
                    account: prefDraft.value.zentaoAccount,
                    password: newPassword.value || null
                }
            })
        } else {
            const draft = systemDraft.value
            if (domain === 'feishu-app') {
                probeResult.value = await testSystemSetting('feishu-app', {
                    feishu: { appId: draft.feishu.appId, appSecret: draft.feishu.appSecret }
                })
            } else if (domain === 'feishu-webhook') {
                probeResult.value = await testSystemSetting('feishu-webhook', {
                    feishu: { webhookUrl: draft.feishu.webhookUrl }
                })
            } else {
                probeResult.value = await testSystemSetting('model', {
                    model: {
                        baseUrl: draft.model.baseUrl,
                        apiKey: draft.model.apiKey,
                        name: draft.model.name,
                        temperature: draft.model.temperature,
                        timeoutSeconds: draft.model.timeoutSeconds
                    }
                })
            }
        }
    } catch (e) {
        probeResult.value = { success: false, message: errorText(e) }
    } finally {
        probing.value = false
    }
}

async function refreshStatus() {
    statusLoading.value = true
    try {
        sources.value = await fetchDataSources()
    } catch (e) {
        ElMessage.error(errorText(e))
    } finally {
        statusLoading.value = false
    }
}

function addRepo() {
    prefDraft.value.gitRepositories.push('')
}

function removeRepo(index) {
    prefDraft.value.gitRepositories.splice(index, 1)
}

async function scanRepos() {
    if (!repoScanRoot.value) {
        ElMessage.warning('请先填写要扫描的根目录')
        return
    }
    try {
        const result = await scanRepositories(repoScanRoot.value)
        scannedRepos.value = result.repositories || []
        ElMessage[scannedRepos.value.length ? 'success' : 'warning'](result.message)
    } catch (e) {
        ElMessage.error(errorText(e))
    }
}

function adoptRepo(path) {
    if (!prefDraft.value.gitRepositories.includes(path)) {
        prefDraft.value.gitRepositories.push(path)
    }
}

async function resetDefault() {
    try {
        await ElMessageBox.confirm('恢复默认会清空个人配置里已保存的个性化设置，确定继续？', '恢复默认', { type: 'warning' })
    } catch (e) {
        return
    }
    try {
        const payload = { ...emptyPreference(), userId: selfId.value, zentaoPassword: null }
        const view = await savePreferences(payload)
        form.value = { ...emptyPreference(), ...view.preference }
        passwordSet.value = !!view.zentaoPasswordSet
        enterGroup()
        ElMessage.success('已恢复默认')
    } catch (e) {
        ElMessage.error(errorText(e))
    }
}

function onMenuCommand(command) {
    if (command === 'reset') {
        resetDefault()
    }
}

function sourceDotClass(item) {
    if (!item.configured) {
        return 'muted'
    }
    return item.lastSuccess === false ? 'rose' : ''
}

function statusText(item) {
    if (!item.configured) {
        return '未配置'
    }
    return item.lastSuccess === false ? '上次失败' : '可用'
}
</script>

<template>
    <div class="page settings-page">
        <header class="page-head">
            <div>
                <div class="eyebrow">个人配置 · 按分组保存</div>
                <h1 class="h1">我的配置</h1>
            </div>
            <div class="page-actions">
                <el-dropdown trigger="click" @command="onMenuCommand">
                    <button class="btn btn-ghost page-menu dots" type="button">···</button>
                    <template #dropdown>
                        <el-dropdown-menu>
                            <el-dropdown-item command="reset">恢复默认</el-dropdown-item>
                        </el-dropdown-menu>
                    </template>
                </el-dropdown>
            </div>
        </header>
        <div class="divider-full"></div>

        <div v-if="loadError" class="load-failed">
            <div class="callout rose">
                <span class="dot rose"></span>
                <span>{{ loadError }}</span>
                <span class="co-actions">
                    <button class="btn btn-sm" type="button" @click="load">重试</button>
                </span>
            </div>
        </div>

        <div v-else v-loading="loading" class="split split-200 settings-split">
            <div class="pane-left">
                <div class="subnav">
                    <template v-for="group in navGroups" :key="group.label">
                        <div class="subnav-group">{{ group.label }}</div>
                        <button
                            v-for="item in group.items"
                            :key="item.key"
                            type="button"
                            class="subnav-item"
                            :class="{ active: activeKey === item.key }"
                            @click="switchGroup(item.key)"
                        >{{ item.label }}</button>
                    </template>
                </div>
            </div>

            <div class="pane-right">
                <div class="sec-head">
                    <span class="sec-title">{{ groupTitle }}</span>
                    <span class="sec-hint">{{ groupHint }}</span>
                </div>

                <div class="form-col">
                    <template v-if="activeKey === 'git'">
                        <div class="status-row">
                            <div class="status-list">
                                <div v-for="item in sources" :key="item.source" class="status-line">
                                    <span class="dot" :class="sourceDotClass(item)"></span>
                                    <span class="small">{{ item.label }} · {{ statusText(item) }}</span>
                                    <span class="tiny muted">
                                        最近采集 {{ item.collectedAt ? formatDateTime(item.collectedAt) : '从未' }}<template v-if="item.fetched !== null && item.fetched !== undefined"> · 拉取 {{ item.fetched }} 条 · 入库 {{ item.inserted }} 条</template>
                                    </span>
                                </div>
                            </div>
                            <button class="btn btn-sm btn-ghost" type="button" :disabled="statusLoading" @click="refreshStatus">刷新状态</button>
                        </div>
                        <div class="divider-inset"></div>
                    </template>

                    <template v-if="activeKey === 'profile'">
                        <div class="field">
                            <span class="field-label">显示名</span>
                            <div class="field-body">
                                <el-input v-model="prefDraft.displayName" placeholder="报告与推送里显示的名字" />
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">飞书用户标识</span>
                            <div class="field-body">
                                <el-input v-model="prefDraft.feishuOpenId" placeholder="ou_ 开头，用于私发消息" />
                                <span class="field-hint">填了才能收到私发推送；也可在会话绑定页完成授权后自动带出</span>
                            </div>
                        </div>
                    </template>

                    <template v-else-if="activeKey === 'learn'">
                        <div class="field">
                            <span class="field-label">关注分类</span>
                            <div class="field-body">
                                <el-select v-model="prefDraft.learnCategories" multiple class="w-full">
                                    <el-option v-for="item in learnCategoryOptions" :key="item.value" :label="item.label" :value="item.value" />
                                </el-select>
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">关注标签</span>
                            <div class="field-body">
                                <el-select
                                    v-model="prefDraft.learnTags"
                                    multiple
                                    filterable
                                    allow-create
                                    default-first-option
                                    placeholder="回车添加"
                                    class="w-full"
                                />
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">作答详细程度</span>
                            <div class="field-body">
                                <el-radio-group v-model="prefDraft.learnAnswerStyle">
                                    <el-radio-button v-for="item in answerStyleOptions" :key="item.value" :value="item.value">{{ item.label }}</el-radio-button>
                                </el-radio-group>
                            </div>
                        </div>
                    </template>

                    <template v-else-if="activeKey === 'git'">
                        <div class="field">
                            <span class="field-label">作者标识</span>
                            <div class="field-body">
                                <el-select
                                    v-model="prefDraft.gitAuthors"
                                    multiple
                                    filterable
                                    allow-create
                                    default-first-option
                                    placeholder="回车添加，例如 name@example.com"
                                    class="w-full"
                                />
                                <span class="field-hint">按提交里的作者名或邮箱匹配，常用写法就是提交时使用的邮箱</span>
                            </div>
                        </div>
                        <div class="divider-inset form-sep"></div>
                        <div class="field">
                            <span class="field-label">本地仓库</span>
                            <div class="field-body">
                                <div class="rows rows-divided rows-edit">
                                    <div v-for="(repo, index) in prefDraft.gitRepositories" :key="index" class="row-item">
                                        <el-input v-model="prefDraft.gitRepositories[index]" placeholder="例如 d:/mycode/project-a" />
                                        <button class="btn btn-sm btn-ghost" type="button" @click="removeRepo(index)">移除</button>
                                    </div>
                                </div>
                                <div v-if="!prefDraft.gitRepositories.length" class="empty">还没有关联仓库，个人未配置时按服务端兜底仓库采集</div>
                                <div class="field-controls">
                                    <button class="btn btn-sm" type="button" @click="addRepo">添加仓库</button>
                                </div>
                                <span class="field-hint">只读取本地 .git，不联网、不需要凭据；多个项目都统计进来就把它们克隆到本机再加进列表</span>
                            </div>
                        </div>
                        <div class="divider-inset form-sep"></div>
                        <div class="field">
                            <span class="field-label">批量发现</span>
                            <div class="field-body">
                                <div class="field-controls">
                                    <el-input v-model="repoScanRoot" class="w-240" placeholder="根目录，例如 d:/mycode" />
                                    <button class="btn" type="button" @click="scanRepos">扫描</button>
                                    <span v-if="scannedRepos.length" class="tiny muted">命中 {{ scannedRepos.length }} 个</span>
                                </div>
                                <div v-if="scannedRepos.length" class="field-controls">
                                    <button
                                        v-for="repo in scannedRepos"
                                        :key="repo"
                                        type="button"
                                        class="chip"
                                        :class="{ active: prefDraft.gitRepositories.includes(repo) }"
                                        @click="adoptRepo(repo)"
                                    >{{ repo }}</button>
                                </div>
                                <span class="field-hint">点命中结果采纳，已加入的不会重复添加</span>
                            </div>
                        </div>
                    </template>

                    <template v-else-if="activeKey === 'zentao'">
                        <div class="field">
                            <span class="field-label">系统账号</span>
                            <div class="field-body">
                                <el-input v-model="prefDraft.zentaoAccount" placeholder="留空则使用服务账号" />
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">系统密码</span>
                            <div class="field-body">
                                <el-input
                                    v-model="newPassword"
                                    type="password"
                                    show-password
                                    :placeholder="passwordSet ? '已设置，留空表示不修改' : '未设置'"
                                />
                                <div class="field-controls">
                                    <el-checkbox v-model="clearPassword">清除已保存的密码</el-checkbox>
                                </div>
                                <span class="field-hint">密码只写不读，保存后无法再查看</span>
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">允许提交登录</span>
                            <div class="field-body">
                                <div class="field-controls">
                                    <el-switch v-model="prefDraft.zentaoLoginAllowed" />
                                </div>
                                <span class="field-hint">关闭时只用已有会话；连续登录失败会自动熔断三十分钟，避免账号被锁</span>
                            </div>
                        </div>
                    </template>

                    <template v-else-if="activeKey === 'push'">
                        <div class="field">
                            <span class="field-label">早推送</span>
                            <div class="field-body">
                                <div class="field-controls">
                                    <el-switch v-model="prefDraft.morningPushEnabled" />
                                    <el-time-picker
                                        v-model="prefDraft.morningPushTime"
                                        value-format="HH:mm"
                                        format="HH:mm"
                                        class="w-160"
                                        :disabled="!prefDraft.morningPushEnabled"
                                    />
                                </div>
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">晚推送</span>
                            <div class="field-body">
                                <div class="field-controls">
                                    <el-switch v-model="prefDraft.eveningPushEnabled" />
                                    <el-time-picker
                                        v-model="prefDraft.eveningPushTime"
                                        value-format="HH:mm"
                                        format="HH:mm"
                                        class="w-160"
                                        :disabled="!prefDraft.eveningPushEnabled"
                                    />
                                </div>
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">静默时段</span>
                            <div class="field-body">
                                <div class="field-controls">
                                    <el-time-picker v-model="prefDraft.quietStart" value-format="HH:mm" format="HH:mm" placeholder="开始" class="w-160" />
                                    <span class="small muted">至</span>
                                    <el-time-picker v-model="prefDraft.quietEnd" value-format="HH:mm" format="HH:mm" placeholder="结束" class="w-160" />
                                </div>
                                <span class="field-hint">静默时段内不推送，留空表示不设置</span>
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">告警接收</span>
                            <div class="field-body">
                                <div class="field-controls">
                                    <el-switch v-model="prefDraft.alertPushEnabled" />
                                </div>
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">推送渠道</span>
                            <div class="field-body">
                                <el-select v-model="prefDraft.pushChannel" class="w-240">
                                    <el-option v-for="item in pushChannelOptions" :key="item.value" :label="item.label" :value="item.value" />
                                </el-select>
                            </div>
                        </div>
                    </template>

                    <template v-else-if="activeKey === 'report'">
                        <div class="field">
                            <span class="field-label">日报风格</span>
                            <div class="field-body">
                                <el-radio-group v-model="prefDraft.reportStyle">
                                    <el-radio-button v-for="item in reportStyleOptions" :key="item.value" :value="item.value">{{ item.label }}</el-radio-button>
                                </el-radio-group>
                                <span class="field-hint">详细会写清关键实现与取舍；简洁只给每条产出的结论</span>
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">附上提交短哈希</span>
                            <div class="field-body">
                                <div class="field-controls">
                                    <el-switch v-model="prefDraft.attachCommitHash" />
                                </div>
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">计划重点条数</span>
                            <div class="field-body">
                                <el-input-number v-model="prefDraft.planFocusLimit" :min="1" :max="8" />
                            </div>
                        </div>
                        <div class="divider-inset form-sep"></div>
                        <div class="field">
                            <span class="field-label">周报</span>
                            <div class="field-body">
                                <div class="field-controls">
                                    <el-switch v-model="prefDraft.weeklyEnabled" />
                                    <el-select v-model="prefDraft.weeklyWeekday" class="w-160" :disabled="!prefDraft.weeklyEnabled">
                                        <el-option v-for="item in weekdayOptions" :key="item.value" :label="item.label" :value="item.value" />
                                    </el-select>
                                    <el-time-picker
                                        v-model="prefDraft.weeklyTime"
                                        value-format="HH:mm"
                                        format="HH:mm"
                                        class="w-160"
                                        :disabled="!prefDraft.weeklyEnabled"
                                    />
                                </div>
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">周报附趋势变化</span>
                            <div class="field-body">
                                <div class="field-controls">
                                    <el-switch v-model="prefDraft.weeklyTrendEnabled" :disabled="!prefDraft.weeklyEnabled" />
                                </div>
                            </div>
                        </div>
                    </template>

                    <template v-else-if="activeKey === 'risk'">
                        <div class="field">
                            <span class="field-label">关注的提醒类型</span>
                            <div class="field-body">
                                <el-checkbox-group v-model="prefDraft.riskTypes">
                                    <el-checkbox v-for="item in riskTypeOptions" :key="item.value" :value="item.value">{{ item.label }}</el-checkbox>
                                </el-checkbox-group>
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">告警等级下限</span>
                            <div class="field-body">
                                <el-select v-model="prefDraft.riskMinLevel" class="w-240">
                                    <el-option v-for="item in riskLevelOptions" :key="item.value" :label="item.label" :value="item.value" />
                                </el-select>
                                <span class="field-hint">低于该等级的提醒不推送，但仍会记录</span>
                            </div>
                        </div>
                    </template>

                    <template v-else-if="activeKey === 'team'">
                        <div class="field">
                            <span class="field-label">无产出告警天数</span>
                            <div class="field-body">
                                <el-input-number v-model="teamDraft.noOutputDays" :min="1" :max="14" />
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">任务停滞天数</span>
                            <div class="field-body">
                                <el-input-number v-model="teamDraft.taskStallDays" :min="1" :max="30" />
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">临期剩余天数</span>
                            <div class="field-body">
                                <el-input-number v-model="teamDraft.deadlineDays" :min="1" :max="14" />
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">临期进度阈值</span>
                            <div class="field-body">
                                <el-input-number v-model="teamDraft.deadlineProgress" :min="0" :max="100" />
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">告警重复间隔（小时）</span>
                            <div class="field-body">
                                <el-input-number v-model="teamDraft.alertThrottleHours" :min="1" :max="48" />
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">默认早推送时间</span>
                            <div class="field-body">
                                <el-time-picker v-model="teamDraft.defaultMorningTime" value-format="HH:mm" format="HH:mm" class="w-160" />
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">默认晚推送时间</span>
                            <div class="field-body">
                                <el-time-picker v-model="teamDraft.defaultEveningTime" value-format="HH:mm" format="HH:mm" class="w-160" />
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">知识目录</span>
                            <div class="field-body">
                                <el-input v-model="teamDraft.knowledgeRootDir" class="w-240" />
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">单篇文档读取上限</span>
                            <div class="field-body">
                                <el-input-number v-model="teamDraft.knowledgeMaxReadChars" :min="1000" :max="20000" :step="500" />
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">检索返回条数</span>
                            <div class="field-body">
                                <el-input-number v-model="teamDraft.knowledgeSearchTopK" :min="1" :max="30" />
                            </div>
                        </div>
                    </template>

                    <template v-else-if="activeKey === 'system'">
                        <div class="sub-title">飞书应用</div>
                        <div class="field">
                            <span class="field-label">应用标识</span>
                            <div class="field-body">
                                <el-input v-model="systemDraft.feishu.appId" />
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">应用密钥</span>
                            <div class="field-body">
                                <el-input
                                    v-model="systemDraft.feishu.appSecret"
                                    type="password"
                                    show-password
                                    :placeholder="systemDraft.feishu.appSecretSet ? '已设置，留空表示不修改' : '未设置'"
                                />
                                <span class="field-hint">当前来源：{{ systemDraft.feishu.appSecretSource }}</span>
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">群机器人 Webhook</span>
                            <div class="field-body">
                                <el-input v-model="systemDraft.feishu.webhookUrl" placeholder="https://open.feishu.cn/open-apis/bot/v2/hook/..." />
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">聊天授权回调地址</span>
                            <div class="field-body">
                                <el-input v-model="systemDraft.feishu.redirectUri" />
                                <span class="field-hint">用于聊天记录采集授权，回调后只展示授权结果</span>
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">登录回调地址</span>
                            <div class="field-body">
                                <el-input v-model="systemDraft.feishu.loginRedirectUri" />
                                <span class="field-hint">用于飞书登录，必须与聊天授权回调不同，且两个地址都要加进开放平台白名单</span>
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">授权权限范围</span>
                            <div class="field-body">
                                <el-input v-model="systemDraft.feishu.oauthScope" />
                            </div>
                        </div>

                        <div class="divider-inset form-sep"></div>
                        <div class="sub-title">发信邮箱（自助找回密码）</div>
                        <div class="field">
                            <span class="field-label">SMTP 地址</span>
                            <div class="field-body">
                                <el-input v-model="systemDraft.mail.host" placeholder="例如 smtp.exmail.qq.com" />
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">端口</span>
                            <div class="field-body">
                                <el-input-number v-model="systemDraft.mail.port" :min="1" :max="65535" controls-position="right" class="w-160" />
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">加密方式</span>
                            <div class="field-body">
                                <el-select v-model="systemDraft.mail.ssl" class="w-240">
                                    <el-option label="SSL（465）" :value="true" />
                                    <el-option label="明文/STARTTLS（587）" :value="false" />
                                </el-select>
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">发信账号</span>
                            <div class="field-body">
                                <el-input v-model="systemDraft.mail.username" placeholder="通常是完整邮箱地址" />
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">发信密码</span>
                            <div class="field-body">
                                <el-input
                                    v-model="systemDraft.mail.password"
                                    type="password"
                                    show-password
                                    :placeholder="systemDraft.mail.passwordSet ? '已设置，留空表示不修改' : '未设置'"
                                />
                                <span class="field-hint">当前来源：{{ systemDraft.mail.passwordSource }} · 多数邮箱需要填授权码而不是登录密码</span>
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">发件人地址</span>
                            <div class="field-body">
                                <el-input v-model="systemDraft.mail.from" placeholder="例如 radar@company.com" />
                                <span class="field-hint">{{ systemDraft.mail.configured ? '当前状态：已可用，忘记密码可自助重置' : '未配置时只能由团队创建人重置成员密码' }}</span>
                            </div>
                        </div>

                        <div class="divider-inset form-sep"></div>
                        <div class="sub-title">内部任务系统</div>
                        <div class="field">
                            <span class="field-label">站点地址</span>
                            <div class="field-body">
                                <el-input v-model="systemDraft.zentao.baseUrl" placeholder="https://zentao.example.com" />
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">服务账号</span>
                            <div class="field-body">
                                <el-input v-model="systemDraft.zentao.account" placeholder="留空表示不使用服务账号" />
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">服务账号密码</span>
                            <div class="field-body">
                                <el-input
                                    v-model="systemDraft.zentao.password"
                                    type="password"
                                    show-password
                                    :placeholder="systemDraft.zentao.passwordSet ? '已设置，留空表示不修改' : '未设置'"
                                />
                                <span class="field-hint">当前来源：{{ systemDraft.zentao.passwordSource }}</span>
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">允许提交登录</span>
                            <div class="field-body">
                                <div class="field-controls">
                                    <el-switch v-model="systemDraft.zentao.allowLogin" />
                                </div>
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">跳过证书校验</span>
                            <div class="field-body">
                                <div class="field-controls">
                                    <el-switch v-model="systemDraft.zentao.skipTlsVerify" />
                                </div>
                                <span class="field-hint">内网自签证书场景才需要打开</span>
                            </div>
                        </div>

                        <div class="divider-inset form-sep"></div>
                        <div class="sub-title">git 兜底仓库</div>
                        <div class="field">
                            <span class="field-label">仓库列表</span>
                            <div class="field-body">
                                <div class="rows rows-divided rows-edit">
                                    <div v-for="(repo, index) in systemDraft.git.repositories" :key="index" class="row-item">
                                        <el-input v-model="systemDraft.git.repositories[index]" placeholder="个人没配仓库时用这里的地址" />
                                        <button class="btn btn-sm btn-ghost" type="button" @click="systemDraft.git.repositories.splice(index, 1)">移除</button>
                                    </div>
                                </div>
                                <div v-if="!systemDraft.git.repositories.length" class="empty">还没有兜底仓库</div>
                                <div class="field-controls">
                                    <button class="btn btn-sm" type="button" @click="systemDraft.git.repositories.push('')">添加仓库</button>
                                </div>
                            </div>
                        </div>

                        <div class="divider-inset form-sep"></div>
                        <div class="sub-title">模型配置</div>
                        <div class="field">
                            <span class="field-label">接口地址</span>
                            <div class="field-body">
                                <el-input v-model="systemDraft.model.baseUrl" placeholder="https://api.deepseek.com" />
                                <span class="field-hint">走 OpenAI 兼容协议，填平台的基础地址即可</span>
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">接口密钥</span>
                            <div class="field-body">
                                <el-input
                                    v-model="systemDraft.model.apiKey"
                                    type="password"
                                    show-password
                                    :placeholder="systemDraft.model.apiKeySet ? '已设置，留空表示不修改' : '未设置'"
                                />
                                <span class="field-hint">当前来源：{{ systemDraft.model.apiKeySource }}</span>
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">模型名</span>
                            <div class="field-body">
                                <el-input v-model="systemDraft.model.name" placeholder="deepseek-chat" />
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">温度</span>
                            <div class="field-body">
                                <el-input-number v-model="systemDraft.model.temperature" :min="0" :max="2" :step="0.1" class="w-160" />
                            </div>
                        </div>
                        <div class="field">
                            <span class="field-label">超时（秒）</span>
                            <div class="field-body">
                                <el-input-number v-model="systemDraft.model.timeoutSeconds" :min="5" :max="600" :step="5" class="w-160" />
                                <span class="field-hint">保存后全局生效，无需重启服务</span>
                            </div>
                        </div>
                    </template>
                </div>

                <div v-if="probeResult" class="probe-wrap">
                    <div class="callout" :class="probeResult.success ? 'mint' : 'rose'">
                        <span class="dot" :class="probeResult.success ? '' : 'rose'"></span>
                        <span>{{ probeResult.message || '测试完成' }}</span>
                    </div>
                    <div v-if="(probeResult.items || []).length" class="probe-items">
                        <span v-for="item in probeResult.items" :key="item.path" class="tiny muted">
                            {{ item.path }} · {{ item.message }}<template v-if="item.ok"> · 该作者提交 {{ item.authorCommits }} 次<template v-if="item.lastCommitAt"> · 最后提交 {{ formatDateTime(item.lastCommitAt) }}</template></template>
                        </span>
                    </div>
                </div>

                <div class="savebar">
                    <span class="sb-hint">{{ isDirty ? '本分组有未保存修改' : '暂无未保存修改' }}</span>
                    <div class="sb-actions">
                        <button
                            v-if="activeKey === 'git' || activeKey === 'zentao'"
                            class="btn btn-ghost"
                            type="button"
                            :disabled="probing"
                            @click="runProbe()"
                        >测试连接</button>
                        <template v-else-if="activeKey === 'system'">
                            <button class="btn btn-sm btn-ghost" type="button" :disabled="probing" @click="runProbe('feishu-app')">测试飞书应用</button>
                            <button class="btn btn-sm btn-ghost" type="button" :disabled="probing" @click="runProbe('feishu-webhook')">测试机器人</button>
                            <button class="btn btn-sm btn-ghost" type="button" :disabled="probing" @click="runProbe('model')">测试模型</button>
                        </template>
                        <button class="btn btn-primary" type="button" :disabled="saving" @click="save">保存</button>
                    </div>
                </div>
            </div>
        </div>
    </div>
</template>

<style scoped>
.settings-page {
    padding-bottom: 0;
}

.settings-split {
    padding-top: var(--space-4);
    min-height: 50vh;
}

.subnav-item {
    width: 100%;
    border: 0;
    background: transparent;
    text-align: left;
    cursor: pointer;
}

.subnav-group:first-child {
    margin-top: 0;
}

.load-failed {
    padding: var(--space-5) 0;
}

.status-row {
    display: flex;
    align-items: flex-start;
    gap: var(--space-4);
    padding: var(--space-2) 0 var(--space-4);
}

.status-list {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
}

.status-line {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    flex-wrap: wrap;
}

.form-sep {
    margin: var(--space-4) 0;
}

.rows-edit .row-item {
    cursor: default;
}

.rows-edit .el-input {
    flex: 1;
    min-width: 0;
}

.chip {
    border: 0;
}

.probe-wrap {
    margin-bottom: var(--space-4);
}

.probe-items {
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
    padding: var(--space-2) 0 0;
}
</style>
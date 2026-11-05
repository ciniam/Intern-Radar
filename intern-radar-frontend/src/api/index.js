import axios from 'axios'

// 根据环境变量设置 API 基础 URL
const API_BASE_URL = process.env.NODE_ENV === 'production'
    ? '/api' // 生产环境使用相对路径，适用于前后端部署在同一域名下
    : 'http://localhost:8123/api' // 开发环境指向本地后端服务

export const TOKEN_KEY = 'intern-radar-token'

const request = axios.create({
    baseURL: API_BASE_URL,
    timeout: 120000
})

request.interceptors.request.use(config => {
    const token = localStorage.getItem(TOKEN_KEY)
    if (token) {
        config.headers['X-Auth-Token'] = token
    }
    return config
})

request.interceptors.response.use(
    response => response.data,
    error => {
        const status = error.response ? error.response.status : 0
        if (status === 401) {
            localStorage.removeItem(TOKEN_KEY)
            window.dispatchEvent(new CustomEvent('radar-unauthorized'))
        }
        return Promise.reject(error)
    }
)

/**
 * 统一从后端响应里取出可读的错误说明
 */
export function errorText(error) {
    const data = error && error.response ? error.response.data : null
    if (data && data.error) {
        return data.error
    }
    if (data && data.message) {
        return data.message
    }
    return (error && error.message) || '请求失败'
}

// ==================== 登录 ====================

export const fetchLoginUsers = () => request.get('/auth/users')

// 正式登录：邮箱 + 密码
export const login = payload => request.post('/auth/login', payload)

export const register = payload => request.post('/auth/register', payload)

// 本地调试用的身份直选
export const devLogin = userId => request.post('/auth/dev-login', null, { params: { userId } })

export const forgotPassword = email => request.post('/auth/password/forgot', { email })

export const resetPassword = payload => request.post('/auth/password/reset', payload)

export const changePassword = payload => request.post('/profile/password', payload)

export const fetchMe = () => request.get('/auth/me')

export const logout = () => request.post('/auth/logout')

export const feishuLoginUrl = () => request.get('/auth/feishu/url')

// ==================== 个人中心 ====================

export const fetchProfile = () => request.get('/profile')

export const saveProfile = payload => request.post('/profile', payload)

export const fetchDevices = () => request.get('/profile/devices')

export const logoutDevice = id => request.delete(`/profile/devices/${id}`)

export const logoutAllDevices = () => request.post('/profile/devices/logout-all')

// ==================== 团队 ====================

export const fetchTeam = () => request.get('/team')

export const fetchTeamMembers = () => request.get('/team/members')

export const createTeam = payload => request.post('/team', payload)

export const joinTeam = payload => request.post('/team/join', payload)

export const fetchMyJoinRequests = () => request.get('/team/requests/mine')

export const fetchTeamRequests = () => request.get('/team/requests')

export const approveTeamRequest = (id, role) =>
    request.post(`/team/requests/${id}/approve`, null, { params: { role } })

export const rejectTeamRequest = id => request.post(`/team/requests/${id}/reject`)

export const rotateInviteCode = () => request.post('/team/invite-code')

// 管理员重置成员密码，返回一次性临时密码
export const resetMemberPassword = userId => request.post(`/team/members/${userId}/reset-password`)

// ==================== 执行轨迹 ====================

export const fetchAgentRuns = params => request.get('/agent/runs', { params })

export const fetchAgentRun = id => request.get(`/agent/runs/${id}`)

// ==================== 报告 ====================

export const collectWork = (userId, date) =>
    request.post('/report/collect', null, { params: { userId, date } })

export const generateDaily = (userId, date, push) =>
    request.post('/report/generate', null, { params: { userId, date, push } })

export const generateMorning = (userId, date, push) =>
    request.post('/report/morning', null, { params: { userId, date, push } })

export const generateWeekly = (userId, weekStart, push) =>
    request.post('/report/weekly', null, { params: { userId, weekStart, push } })

export const fetchDaily = (userId, date) =>
    request.get('/report/daily', { params: { userId, date } })

export const fetchReports = params => request.get('/report/list', { params })

export const fetchWeeklyReport = (userId, weekStart) =>
    request.get('/report/weekly', { params: { userId, weekStart } })

export const fetchRecords = (userId, date) =>
    request.get('/report/records', { params: { userId, date } })

export const fetchTasks = (userId, date) =>
    request.get('/report/tasks', { params: { userId, date } })

// 按天 × 按来源的统计，今日页的柱图、环形图与热力图共用这一次响应
export const fetchReportStats = (userId, start, end) =>
    request.get('/report/stats', { params: { userId, start, end } })

// ==================== 风险 ====================

export const checkRisk = (userId, date) =>
    request.post('/risk/check', null, { params: { userId, date } })

export const notifyRisk = userId => request.post('/risk/notify', null, { params: { userId } })

export const fetchAlerts = (userId, status) =>
    request.get('/risk/alerts', { params: { userId, status } })

export const ackAlert = id => request.post(`/risk/${id}/ack`)

// ==================== 导师视图 ====================

export const fetchMentorOverview = days => request.get('/mentor/overview', { params: { days } })

export const fetchMentorInterns = days => request.get('/mentor/interns', { params: { days } })

export const fetchMentorTrend = days => request.get('/mentor/trend', { params: { days } })

export const fetchMentorAlerts = () => request.get('/mentor/alerts')

// ==================== 学习 ====================

export const askLearn = (userId, question, conversationId) =>
    request.post('/learn/ask', null, { params: { userId, question, conversationId } })

/**
 * 流式提问。用 fetch 读取 SSE，逐块回调，axios 在浏览器端读不了流
 * 事件形如 {type:'conversation'|'references'|'delta'|'done'|'error'}
 * 不传会话标识表示开启新会话，服务端会在首条事件里回传会话标识
 */
export const askLearnStream = async (userId, question, conversationId, handlers) => {
    const token = localStorage.getItem(TOKEN_KEY)
    const body = new URLSearchParams({ question })
    if (userId) {
        body.append('userId', userId)
    }
    if (conversationId) {
        body.append('conversationId', conversationId)
    }
    const response = await fetch(`${API_BASE_URL}/learn/ask/stream`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/x-www-form-urlencoded',
            ...(token ? { 'X-Auth-Token': token } : {})
        },
        body
    })
    if (!response.ok || !response.body) {
        const text = await response.text().catch(() => '')
        throw new Error(text || `请求失败：${response.status}`)
    }
    const reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    while (true) {
        const { value, done } = await reader.read()
        if (done) {
            break
        }
        buffer += decoder.decode(value, { stream: true })
        buffer = buffer.replace(/\r\n/g, '\n')
        let boundary = buffer.indexOf('\n\n')
        while (boundary >= 0) {
            const chunk = buffer.slice(0, boundary)
            buffer = buffer.slice(boundary + 2)
            for (const line of chunk.split('\n')) {
                if (!line.startsWith('data:')) {
                    continue
                }
                const raw = line.slice(5).trim()
                if (!raw) {
                    continue
                }
                let event
                try {
                    event = JSON.parse(raw)
                } catch (ignored) {
                    continue
                }
                if (handlers && handlers.onEvent) {
                    handlers.onEvent(event)
                }
            }
            boundary = buffer.indexOf('\n\n')
        }
    }
}

export const learnRecommend = userId => request.get('/learn/recommend', { params: { userId } })

export const fetchConversations = keyword =>
    request.get('/learn/conversations', { params: { keyword } })

export const fetchConversation = id => request.get(`/learn/conversations/${id}`)

// 助手提出的配置变更提案，确认后才写进个人配置
export const fetchProposals = conversationId => request.get(`/learn/conversations/${conversationId}/proposals`)

export const applyProposal = id => request.post(`/learn/proposals/${id}/apply`)

export const rejectProposal = id => request.post(`/learn/proposals/${id}/reject`)

export const deleteConversation = id => request.delete(`/learn/conversations/${id}`)

export const renameConversation = (id, title) =>
    request.post(`/learn/conversations/${id}/title`, null, { params: { title } })

// 回退最后一轮，返回需要重新生成的问题
export const rewindConversation = id => request.post(`/learn/conversations/${id}/rewind`)

export const fetchDocs = params => request.get('/learn/docs', { params })

export const fetchDoc = id => request.get(`/learn/docs/${id}`)

export const refreshDocs = () => request.post('/learn/docs/refresh')

export const checkDocDuplicate = fileName =>
    request.get('/learn/docs/check', { params: { fileName } })

// 不手工设置 Content-Type，交给浏览器带上 multipart 边界
export const uploadDoc = (formData, overwrite) =>
    request.post('/learn/docs/upload', formData, { params: { overwrite: !!overwrite } })

export const fetchPitfalls = keyword => request.get('/learn/pitfalls', { params: { keyword } })

// ==================== 设置 ====================

export const fetchPreferences = userId => request.get('/settings/preferences', { params: { userId } })

export const savePreferences = payload => request.post('/settings/preferences', payload)

export const fetchDataSources = () => request.get('/settings/datasources')

// 个人配置引导：还差哪几项没有配
export const fetchOnboarding = () => request.get('/settings/onboarding')

export const fetchTeamSettings = () => request.get('/settings/team')

export const saveTeamSettings = payload => request.post('/settings/team', payload)

export const fetchSettingsOptions = () => request.get('/settings/options')

export const fetchSystemSettings = () => request.get('/settings/system')

export const saveSystemSettings = payload => request.post('/settings/system', payload)

export const testSystemSetting = (domain, payload, authors) =>
    request.post('/settings/system/test', payload || {}, { params: { domain, authors } })

export const scanRepositories = rootDir =>
    request.get('/settings/system/repositories/scan', { params: { rootDir } })

// ==================== 会话绑定 ====================

export const fetchBindings = userId => request.get('/feishu/chats/bindings', { params: { userId } })

export const bindChat = params => request.post('/feishu/chats/bind', null, { params })

export const unbindChat = id => request.delete(`/feishu/chats/bindings/${id}`)

export const probeChat = params => request.get('/feishu/probe', { params })

export const fetchChats = params => request.get('/feishu/chats', { params })

export const fetchAuthStatus = userId => request.get('/feishu/auth', { params: { userId } })

export const fetchOauthUrl = userId => request.get('/feishu/oauth/url', { params: { userId } })

// ==================== 可观测 ====================

export const fetchMetrics = days => request.get('/admin/metrics', { params: { days } })

export default request

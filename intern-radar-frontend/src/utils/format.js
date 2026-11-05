export function toISODate(date) {
    const year = date.getFullYear()
    const month = String(date.getMonth() + 1).padStart(2, '0')
    const day = String(date.getDate()).padStart(2, '0')
    return `${year}-${month}-${day}`
}

export function todayISO() {
    return toISODate(new Date())
}

/**
 * 最近 7 天的起始日期，报告页默认区间用
 */
export function weekAgoISO() {
    const date = new Date()
    date.setDate(date.getDate() - 6)
    return toISODate(date)
}

/**
 * 取某天所在周的周一，周报查询用
 */
export function mondayISO(base) {
    const date = base ? new Date(base) : new Date()
    const weekDay = date.getDay() === 0 ? 7 : date.getDay()
    date.setDate(date.getDate() - (weekDay - 1))
    return toISODate(date)
}

/**
 * 本地时间字符串。toISOString 是 UTC，直接显示会比本地时间差几个小时
 */
export function localDateTime() {
    const date = new Date()
    const pad = value => String(value).padStart(2, '0')
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
        + `T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

export function formatDateTime(value) {
    if (!value) {
        return '-'
    }
    return String(value).replace('T', ' ').slice(0, 16)
}

export function formatDate(value) {
    if (!value) {
        return '-'
    }
    return String(value).slice(0, 10)
}

export const RISK_LEVEL_LABEL = {
    HIGH: '高风险',
    MEDIUM: '中风险',
    LOW: '提示'
}

export const RISK_LEVEL_TAG = {
    HIGH: 'danger',
    MEDIUM: 'warning',
    LOW: 'success'
}

export const RISK_TYPE_LABEL = {
    NO_OUTPUT: '无产出',
    TASK_STALL: '任务停滞',
    DEADLINE_RISK: '临期未完成',
    TREND_ANOMALY: '趋势突变',
    PITFALL: '坑位提示'
}

export const REPORT_TYPE_LABEL = {
    DAILY: '日报',
    PLAN: '今日计划',
    WEEKLY: '周报'
}

export const REPORT_STATUS_LABEL = {
    SUCCESS: '成功',
    EMPTY: '空数据',
    FAILED: '失败'
}

export const REPORT_STATUS_TAG = {
    SUCCESS: 'primary',
    EMPTY: 'info',
    FAILED: 'danger'
}

export const RISK_TYPE_OPTIONS = Object.keys(RISK_TYPE_LABEL)

export function riskLevelLabel(level) {
    return RISK_LEVEL_LABEL[level] || level || '-'
}

export function riskLevelTag(level) {
    return RISK_LEVEL_TAG[level] || 'info'
}

export function riskTypeLabel(type) {
    return RISK_TYPE_LABEL[type] || type || '-'
}

export function reportTypeLabel(type) {
    return REPORT_TYPE_LABEL[type] || type || '-'
}

export function reportStatusLabel(status) {
    return REPORT_STATUS_LABEL[status] || status || '-'
}

export function reportStatusTag(status) {
    return REPORT_STATUS_TAG[status] || 'info'
}

/**
 * 后端把证据用分隔符拼成一串，这里还原成数组
 */
export function splitEvidence(evidence) {
    if (!evidence) {
        return []
    }
    return String(evidence).split('｜').filter(item => item.trim())
}

export function parseMetadata(metadata) {
    if (!metadata) {
        return {}
    }
    try {
        return typeof metadata === 'string' ? JSON.parse(metadata) : metadata
    } catch (e) {
        return {}
    }
}

/**
 * 相对时间。今天页的提醒与最近活跃用「2 小时前」这种说法，比绝对时间更好扫
 */
export function relativeFromNow(value) {
    if (!value) {
        return '-'
    }
    const target = new Date(String(value).replace(' ', 'T'))
    if (Number.isNaN(target.getTime())) {
        return formatDateTime(value)
    }
    const diff = Date.now() - target.getTime()
    if (diff < 0) {
        return formatDateTime(value)
    }
    const minute = 60 * 1000
    const hour = 60 * minute
    const day = 24 * hour
    if (diff < minute) {
        return '刚刚'
    }
    if (diff < hour) {
        return `${Math.floor(diff / minute)} 分钟前`
    }
    if (diff < day) {
        return `${Math.floor(diff / hour)} 小时前`
    }
    if (diff < 7 * day) {
        return `${Math.floor(diff / day)} 天前`
    }
    return formatDate(value)
}

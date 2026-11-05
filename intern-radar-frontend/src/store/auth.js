import { computed, reactive } from 'vue'
import {
    devLogin,
    fetchMe,
    fetchProfile,
    login as loginApi,
    logout as logoutApi,
    register as registerApi,
    TOKEN_KEY
} from '../api'

const TEAM_ROLES = ['MENTOR', 'ADMIN']

const state = reactive({
    token: localStorage.getItem(TOKEN_KEY) || '',
    user: null,
    // 初始密码或管理员重置后为真，必须先改密才能继续用
    passwordResetRequired: false
})

export function homeOf(user) {
    if (user && TEAM_ROLES.includes(user.role)) {
        return '/dashboard'
    }
    return '/today'
}

export function useAuth() {
    return {
        state,
        isLoggedIn: computed(() => !!state.token),
        role: computed(() => (state.user ? state.user.role : null)),
        isMentor: computed(() => !!state.user && TEAM_ROLES.includes(state.user.role)),
        // 没加入团队时只能访问团队页与个人中心
        hasTeam: computed(() => !!state.user && !!state.user.teamId && state.user.teamId > 0),
        // 登出过程中组件仍可能重渲染，这里统一兜底，避免取到空用户
        userId: computed(() => (state.user ? state.user.userId : '')),
        homeOf: () => homeOf(state.user),

        /**
         * 邮箱密码登录
         */
        async login(email, password) {
            const result = await loginApi({ email, password })
            applyToken(result.token)
            state.passwordResetRequired = !!result.passwordResetRequired
            await this.loadMe()
            return result
        },

        async register(payload) {
            const result = await registerApi(payload)
            applyToken(result.token)
            state.passwordResetRequired = !!result.passwordResetRequired
            await this.loadMe()
            return result
        },

        /**
         * 身份直选登录，本地调试用
         */
        async devLogin(userId) {
            const result = await devLogin(userId)
            applyToken(result.token)
            await this.loadMe()
            return result
        },

        /**
         * 飞书登录回调拿到令牌后接管登录态
         */
        async adoptToken(token) {
            applyToken(token)
            return this.loadMe()
        },

        async loadMe() {
            state.user = await fetchMe()
            try {
                const profile = await fetchProfile()
                state.passwordResetRequired = !!profile.passwordResetRequired
            } catch (e) {
                state.passwordResetRequired = false
            }
            return state.user
        },

        async logout() {
            try {
                await logoutApi()
            } catch (e) {
                // 令牌可能已失效，忽略登出失败
            }
            this.clear()
        },

        clear() {
            state.token = ''
            state.user = null
            state.passwordResetRequired = false
            localStorage.removeItem(TOKEN_KEY)
        }
    }
}

function applyToken(token) {
    state.token = token
    localStorage.setItem(TOKEN_KEY, token)
}

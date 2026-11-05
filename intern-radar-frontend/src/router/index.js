import { createRouter, createWebHistory } from 'vue-router'
import { homeOf, useAuth } from '../store/auth'

const routes = [
    {
        path: '/login',
        name: 'Login',
        component: () => import('../views/Login.vue'),
        meta: { public: true, title: '登录' }
    },
    {
        path: '/',
        component: () => import('../layout/AppLayout.vue'),
        children: [
            { path: '', redirect: '/today' },
            {
                path: 'today',
                name: 'Today',
                component: () => import('../views/Today.vue'),
                meta: { roles: ['INTERN'], title: '我的今日' }
            },
            // 报告与我的报告合并成一页，旧地址保持可达
            {
                path: 'my-reports',
                redirect: to => ({ path: '/reports', query: to.query })
            },
            {
                path: 'dashboard',
                name: 'Dashboard',
                component: () => import('../views/Dashboard.vue'),
                meta: { roles: ['MENTOR', 'ADMIN'], title: '导师看板' }
            },
            {
                path: 'reports',
                name: 'Reports',
                component: () => import('../views/Reports.vue'),
                meta: { title: '报告' }
            },
            {
                path: 'learn',
                name: 'Learn',
                component: () => import('../views/Learn.vue'),
                meta: { title: 'AI 助手' }
            },
            {
                path: 'docs',
                name: 'Docs',
                component: () => import('../views/Knowledge.vue'),
                meta: { title: '文档库' }
            },
            {
                path: 'chats',
                name: 'Chats',
                component: () => import('../views/Chats.vue'),
                meta: { roles: ['INTERN'], title: '会话绑定' }
            },
            {
                path: 'team',
                name: 'Team',
                component: () => import('../views/Team.vue'),
                meta: { title: '我的团队' }
            },
            {
                path: 'profile',
                name: 'Profile',
                component: () => import('../views/Profile.vue'),
                meta: { title: '个人中心' }
            },
            {
                path: 'settings',
                name: 'Settings',
                component: () => import('../views/Settings.vue'),
                meta: { title: '配置' }
            }
        ]
    },
    // 旧地址已随基线页面移除，统一回到当前角色的首页
    { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({
    history: createWebHistory(),
    routes
})

router.beforeEach(async to => {
    const auth = useAuth()

    if (to.meta.public) {
        return auth.state.token ? homeOf(auth.state.user) : true
    }
    if (!auth.state.token) {
        return { path: '/login' }
    }
    if (!auth.state.user) {
        try {
            await auth.loadMe()
        } catch (e) {
            auth.clear()
            return { path: '/login' }
        }
    }
    // 没加入团队时只能进团队页与个人中心，其余页面都会被后端拒绝，这里直接引导过去
    const joined = !!auth.state.user.teamId && auth.state.user.teamId > 0
    if (!joined && to.path !== '/team' && to.path !== '/profile') {
        return { path: '/team' }
    }
    if (joined && to.path === '/team' && to.meta.allowJoined === false) {
        return homeOf(auth.state.user)
    }
    if (to.meta.roles && !to.meta.roles.includes(auth.state.user.role)) {
        return homeOf(auth.state.user)
    }
    return true
})

router.afterEach(to => {
    if (to.meta.title) {
        document.title = `${to.meta.title} · 实习雷达`
    }
})

// 任何请求返回 401 时退回登录页
window.addEventListener('radar-unauthorized', () => {
    const auth = useAuth()
    auth.clear()
    if (router.currentRoute.value.path !== '/login') {
        router.push('/login')
    }
})

export default router

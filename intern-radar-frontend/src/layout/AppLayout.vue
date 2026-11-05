<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { changePassword, errorText } from '../api'
import { useAuth } from '../store/auth'
import BrandIcon from '../components/BrandIcon.vue'

const auth = useAuth()
const route = useRoute()
const router = useRouter()

const COLLAPSE_KEY = 'intern-radar-sidebar-collapsed'
const collapsed = ref(localStorage.getItem(COLLAPSE_KEY) === '1')

watch(collapsed, value => {
    localStorage.setItem(COLLAPSE_KEY, value ? '1' : '0')
})

function toggleCollapse() {
    collapsed.value = !collapsed.value
}

const ROLE_LABEL = { INTERN: '实习生', MENTOR: '导师', ADMIN: '管理员' }
const roleLabel = computed(() => ROLE_LABEL[auth.state.user?.role] || '')
const userName = computed(() => auth.state.user?.name || '-')
const initial = computed(() => userName.value.slice(0, 1))

/**
 * 导航按「工作台 / 知识 / 设置」三组分，看板只对导师可见；
 * 未加入团队时只留团队与个人中心，避免点进去被后端拒绝
 */
const groups = computed(() => {
    if (!auth.hasTeam.value) {
        return [
            { label: '开始', items: [{ path: '/team', label: '我的团队', icon: 'OfficeBuilding' }] },
            { label: '账号', items: [{ path: '/profile', label: '个人中心', icon: 'User' }] }
        ]
    }
    const workspace = auth.isMentor.value
        ? [
            { path: '/dashboard', label: '导师看板', icon: 'DataLine' },
            { path: '/reports', label: '报告', icon: 'Document' },
            { path: '/team', label: '我的团队', icon: 'OfficeBuilding' }
        ]
        : [
            { path: '/today', label: '我的今日', icon: 'Grid' },
            { path: '/reports', label: '报告', icon: 'Document' },
            { path: '/team', label: '我的团队', icon: 'OfficeBuilding' }
        ]
    const knowledge = [
        { path: '/learn', label: 'AI 助手', icon: 'ChatDotRound' },
        { path: '/docs', label: '文档库', icon: 'Folder' }
    ]
    const settings = [
        { path: '/settings', label: '我的配置', icon: 'Setting' }
    ]
    if (!auth.isMentor.value) {
        settings.push({ path: '/chats', label: '会话绑定', icon: 'Link' })
    }
    settings.push({ path: '/profile', label: '个人中心', icon: 'User' })
    return [
        { label: '工作台', items: workspace },
        { label: '知识', items: knowledge },
        { label: '设置', items: settings }
    ]
})

function goHome() {
    router.push(auth.isMentor.value ? '/dashboard' : '/today')
}

async function handleLogout() {
    try {
        await ElMessageBox.confirm('退出后需要重新登录才能继续使用，确定退出？', '退出登录', {
            type: 'warning',
            confirmButtonText: '退出',
            cancelButtonText: '取消'
        })
    } catch (e) {
        return
    }
    await auth.logout()
    ElMessage.success('已退出登录')
    router.push('/login')
}

/* 初始密码或管理员重置后必须先改密，这里做成关不掉的弹窗 */
const passwordForm = ref({ oldPassword: '', newPassword: '', confirm: '' })
const passwordSaving = ref(false)

async function submitPassword() {
    if (!passwordForm.value.oldPassword || !passwordForm.value.newPassword) {
        ElMessage.warning('请填写当前密码与新密码')
        return
    }
    if (passwordForm.value.newPassword !== passwordForm.value.confirm) {
        ElMessage.warning('两次输入的新密码不一致')
        return
    }
    passwordSaving.value = true
    try {
        await changePassword({
            oldPassword: passwordForm.value.oldPassword,
            newPassword: passwordForm.value.newPassword
        })
        auth.state.passwordResetRequired = false
        passwordForm.value = { oldPassword: '', newPassword: '', confirm: '' }
        ElMessage.success('密码已更新')
    } catch (e) {
        ElMessage.error(errorText(e))
    } finally {
        passwordSaving.value = false
    }
}
</script>

<template>
    <div class="layout" :class="{ 'nav-mini': collapsed }">
        <aside class="nav">
            <div class="nav-head">
                <a class="brand" href="#" @click.prevent="goHome">
                    <BrandIcon variant="soft" :size="26" />
                    <span class="brand-text">
                        <span class="brand-name">实习雷达</span>
                        <span class="brand-sub">Intern Radar</span>
                    </span>
                </a>
                <button
                    class="nav-collapse"
                    type="button"
                    :aria-label="collapsed ? '展开侧栏' : '收起侧栏'"
                    :aria-expanded="!collapsed"
                    @click="toggleCollapse"
                >
                    <el-icon><component :is="collapsed ? 'Expand' : 'Fold'" /></el-icon>
                </button>
            </div>

            <nav class="nav-body" aria-label="主导航">
                <div v-for="group in groups" :key="group.label" class="nav-group">
                    <div class="nav-label">{{ group.label }}</div>
                    <router-link
                        v-for="item in group.items"
                        :key="item.path"
                        :to="item.path"
                        class="nav-item"
                        :class="{ active: route.path === item.path }"
                        :title="item.label"
                    >
                        <span class="nav-ico"><el-icon><component :is="item.icon" /></el-icon></span>
                        <span class="nav-txt">{{ item.label }}</span>
                    </router-link>
                </div>
            </nav>

            <div class="nav-bottom">
                <div class="nav-user" role="button" tabindex="0" @click="router.push('/profile')" @keydown.enter="router.push('/profile')">
                    <span class="avatar">{{ initial }}</span>
                    <span class="nav-user-text">
                        <b>{{ userName }}</b>
                        <span>{{ roleLabel }}</span>
                    </span>
                </div>
                <button class="nav-item nav-btn" type="button" @click="handleLogout">
                    <span class="nav-ico"><el-icon><SwitchButton /></el-icon></span>
                    <span class="nav-txt">退出</span>
                </button>
            </div>
        </aside>

        <main class="main">
            <router-view />
        </main>

        <el-dialog
            :model-value="auth.state.passwordResetRequired"
            title="请先设置新密码"
            width="420"
            :close-on-click-modal="false"
            :close-on-press-escape="false"
            :show-close="false"
        >
            <p class="pwd-tip">
                当前用的是初始密码或管理员下发的临时密码，改完才能继续使用其它功能。
            </p>
            <el-form label-position="top">
                <el-form-item label="当前密码">
                    <el-input v-model="passwordForm.oldPassword" type="password" show-password />
                </el-form-item>
                <el-form-item label="新密码">
                    <el-input v-model="passwordForm.newPassword" type="password" show-password />
                    <p class="pwd-tip">至少 8 位，且要同时包含大写字母、小写字母与数字</p>
                </el-form-item>
                <el-form-item label="再输一次">
                    <el-input v-model="passwordForm.confirm" type="password" show-password />
                </el-form-item>
            </el-form>
            <template #footer>
                <el-button type="primary" :loading="passwordSaving" @click="submitPassword">保存并继续</el-button>
            </template>
        </el-dialog>
    </div>
</template>

<style scoped>
.pwd-tip {
    margin: 0 0 var(--space-2);
    font-size: var(--font-size-12);
    line-height: var(--line-height-base);
    color: var(--text-3);
}

/* 侧栏的退出项是按钮，去掉浏览器默认外观，尺寸与导航项一致 */
.nav-btn {
    width: 100%;
    border: 0;
    background: transparent;
    text-align: left;
    cursor: pointer;
    font-size: var(--font-size-13);
}
</style>

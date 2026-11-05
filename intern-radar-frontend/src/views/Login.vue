<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { errorText, fetchLoginUsers, forgotPassword, resetPassword } from '../api'
import { useAuth } from '../store/auth'
import BrandIcon from '../components/BrandIcon.vue'

const auth = useAuth()
const router = useRouter()
const route = useRoute()

const mode = ref('signin')
const loading = ref(false)
const loginForm = ref({ email: '', password: '' })
const registerForm = ref({ name: '', email: '', password: '', confirm: '' })
const forgotForm = ref({ email: '', code: '', password: '' })
const codeSent = ref(false)
const countdown = ref(0)
let timer = null

const devUsers = ref([])
const devLoginEnabled = ref(false)
const devOpen = ref(false)
const showDev = computed(() => route.query.debug === '1')

onMounted(async () => {
    try {
        const data = await fetchLoginUsers()
        devUsers.value = data.users || []
        devLoginEnabled.value = !!data.devLoginEnabled
    } catch (e) {
        devUsers.value = []
    }
})

onBeforeUnmount(stopCountdown)

function stopCountdown() {
    if (timer) {
        clearInterval(timer)
        timer = null
    }
}

function startCountdown() {
    countdown.value = 60
    stopCountdown()
    timer = setInterval(() => {
        countdown.value -= 1
        if (countdown.value <= 0) {
            stopCountdown()
        }
    }, 1000)
}

function switchMode(target) {
    mode.value = target
}

function roleLabel(role) {
    if (role === 'MENTOR') {
        return '导师'
    }
    if (role === 'ADMIN') {
        return '管理员'
    }
    return '实习生'
}

async function submitLogin() {
    if (!loginForm.value.email || !loginForm.value.password) {
        ElMessage.warning('请填写邮箱与密码')
        return
    }
    loading.value = true
    try {
        await auth.login(loginForm.value.email.trim(), loginForm.value.password)
        ElMessage.success('登录成功')
        router.push(auth.homeOf())
    } catch (e) {
        ElMessage.error(errorText(e))
    } finally {
        loading.value = false
    }
}

async function submitRegister() {
    const form = registerForm.value
    if (!form.email || !form.password || !form.confirm) {
        ElMessage.warning('请填写邮箱与密码')
        return
    }
    if (form.password !== form.confirm) {
        ElMessage.warning('两次输入的密码不一致')
        return
    }
    loading.value = true
    try {
        await auth.register({
            email: form.email.trim(),
            password: form.password,
            name: form.name.trim()
        })
        ElMessage.success('注册成功')
        router.push(auth.homeOf())
    } catch (e) {
        ElMessage.error(errorText(e))
    } finally {
        loading.value = false
    }
}

async function sendCode() {
    if (!forgotForm.value.email) {
        ElMessage.warning('请填写注册邮箱')
        return
    }
    loading.value = true
    try {
        await forgotPassword(forgotForm.value.email.trim())
        codeSent.value = true
        startCountdown()
        ElMessage.success('如果邮箱已注册，验证码已发出，请查收')
    } catch (e) {
        ElMessage.error(errorText(e))
    } finally {
        loading.value = false
    }
}

function backToEmail() {
    codeSent.value = false
    stopCountdown()
    countdown.value = 0
    forgotForm.value.code = ''
    forgotForm.value.password = ''
}

async function submitReset() {
    if (!forgotForm.value.email || !forgotForm.value.code || !forgotForm.value.password) {
        ElMessage.warning('请填写邮箱、验证码与新密码')
        return
    }
    loading.value = true
    try {
        await resetPassword({
            email: forgotForm.value.email.trim(),
            code: forgotForm.value.code.trim(),
            password: forgotForm.value.password
        })
        ElMessage.success('密码已重置，请用新密码登录')
        loginForm.value.email = forgotForm.value.email.trim()
        loginForm.value.password = ''
        backToEmail()
        forgotForm.value = { email: '', code: '', password: '' }
        mode.value = 'signin'
    } catch (e) {
        ElMessage.error(errorText(e))
    } finally {
        loading.value = false
    }
}

async function submitDev(userId) {
    loading.value = true
    try {
        await auth.devLogin(userId)
        router.push(auth.homeOf())
    } catch (e) {
        ElMessage.error(errorText(e))
    } finally {
        loading.value = false
    }
}
</script>

<template>
    <div class="login-body">
        <div class="login-wrap">
            <div class="login-card">
                <div class="login-brand">
                    <BrandIcon variant="soft" :size="32" />
                    <span class="login-name">实习雷达</span>
                </div>
                <div class="login-sub">把每天的代码、任务与会话，收成一份能看的日报</div>

                <div class="login-tabs" role="tablist" aria-label="登录方式">
                    <button
                        class="tab"
                        type="button"
                        role="tab"
                        :aria-selected="mode === 'signin'"
                        :class="{ active: mode === 'signin' }"
                        @click="switchMode('signin')"
                    >登录</button>
                    <button
                        class="tab"
                        type="button"
                        role="tab"
                        :aria-selected="mode === 'signup'"
                        :class="{ active: mode === 'signup' }"
                        @click="switchMode('signup')"
                    >注册</button>
                    <button
                        class="tab"
                        type="button"
                        role="tab"
                        :aria-selected="mode === 'forgot'"
                        :class="{ active: mode === 'forgot' }"
                        @click="switchMode('forgot')"
                    >找回密码</button>
                </div>

                <div v-show="mode === 'signin'" role="tabpanel">
                    <div class="login-form">
                        <input
                            v-model="loginForm.email"
                            class="input"
                            placeholder="邮箱"
                            autocomplete="username"
                        >
                        <el-input
                            v-model="loginForm.password"
                            type="password"
                            show-password
                            placeholder="密码"
                            autocomplete="current-password"
                            @keyup.enter="submitLogin"
                        />
                        <button class="btn btn-primary btn-block" type="button" :disabled="loading" @click="submitLogin">登录</button>
                    </div>
                    <div class="login-tip">账号由团队创建人审批后生效，注册后先创建或加入团队</div>
                </div>

                <div v-show="mode === 'signup'" role="tabpanel">
                    <div class="login-form">
                        <input
                            v-model="registerForm.name"
                            class="input"
                            placeholder="昵称（选填）"
                            maxlength="32"
                        >
                        <input
                            v-model="registerForm.email"
                            class="input"
                            placeholder="邮箱"
                            autocomplete="username"
                        >
                        <el-input
                            v-model="registerForm.password"
                            type="password"
                            show-password
                            placeholder="密码，至少 8 位，含大小写与数字"
                            autocomplete="new-password"
                        />
                        <el-input
                            v-model="registerForm.confirm"
                            type="password"
                            show-password
                            placeholder="再输一次"
                            @keyup.enter="submitRegister"
                        />
                        <button class="btn btn-primary btn-block" type="button" :disabled="loading" @click="submitRegister">注册并进入</button>
                    </div>
                    <div class="login-tip">注册后需要创建团队或用邀请码加入，才能使用其它功能</div>
                </div>

                <div v-show="mode === 'forgot'" role="tabpanel">
                    <div class="login-form">
                        <input
                            v-model="forgotForm.email"
                            class="input"
                            placeholder="邮箱"
                            autocomplete="username"
                            :disabled="codeSent"
                        >
                        <div class="code-row">
                            <input
                                v-model="forgotForm.code"
                                class="input"
                                placeholder="验证码"
                                maxlength="6"
                            >
                            <button class="btn" type="button" :disabled="countdown > 0 || loading" @click="sendCode">
                                {{ countdown > 0 ? `${countdown}s 后重发` : '发送验证码' }}
                            </button>
                        </div>
                        <el-input
                            v-model="forgotForm.password"
                            type="password"
                            show-password
                            placeholder="新密码，至少 8 位，含大小写与数字"
                            autocomplete="new-password"
                            @keyup.enter="submitReset"
                        />
                        <button class="btn btn-primary btn-block" type="button" :disabled="loading" @click="submitReset">重置密码</button>
                        <button v-if="codeSent" class="btn btn-sm btn-ghost" type="button" @click="backToEmail">换个邮箱</button>
                    </div>
                    <div class="login-tip">验证码 10 分钟内有效；重置成功后请用新密码登录</div>
                </div>

                <template v-if="showDev && devLoginEnabled && devUsers.length">
                    <div class="divider-inset dev-divider"></div>
                    <button class="btn btn-sm btn-ghost" type="button" @click="devOpen = !devOpen">
                        {{ devOpen ? '收起调试直登' : '调试直登' }}
                    </button>
                    <div v-if="devOpen" class="dev-list">
                        <button
                            v-for="user in devUsers"
                            :key="user.userId"
                            class="btn btn-sm dev-item"
                            type="button"
                            :disabled="loading"
                            @click="submitDev(user.userId)"
                        >{{ user.name || user.userId }} · {{ roleLabel(user.role) }}</button>
                    </div>
                </template>
            </div>
        </div>
    </div>
</template>

<style scoped>
.code-row {
    display: flex;
    gap: var(--space-2);
}

.code-row .input {
    flex: 1;
}

.dev-divider {
    margin: var(--space-6) 0 var(--space-4);
}

.dev-list {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    margin-top: var(--space-3);
}

.dev-item {
    justify-content: flex-start;
}
</style>
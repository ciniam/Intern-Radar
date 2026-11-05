<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
    applyProposal,
    askLearnStream,
    deleteConversation,
    errorText,
    fetchConversation,
    fetchConversations,
    fetchProposals,
    learnRecommend,
    rejectProposal as rejectProposalApi,
    renameConversation,
    rewindConversation
} from '../api'
import { useAuth } from '../store/auth'
import { formatDateTime, localDateTime } from '../utils/format'
import AgentTrace from '../components/AgentTrace.vue'
import BrandIcon from '../components/BrandIcon.vue'
import MarkdownView from '../components/MarkdownView.vue'

const auth = useAuth()
const router = useRouter()
const route = useRoute()

const question = ref('')
const asking = ref(false)
const loadingMessages = ref(false)
const messages = ref([])
const conversations = ref([])
const conversationId = ref(null)
const conversationTitle = ref('')
const keyword = ref('')
const proposals = ref([])
const recommend = ref(null)
const recommendOpen = ref(false)
const convOpen = ref(true)
const chatBox = ref(null)
const composerInput = ref(null)

const isEmpty = computed(() => !messages.value.length && !proposals.value.length)
const userName = computed(() => auth.state.user?.name || '我')
const recommendDocs = computed(() => (recommend.value && recommend.value.docs) || [])
const recommendPitfalls = computed(() => (recommend.value && recommend.value.pitfalls) || [])

const lastBotIndex = computed(() => {
    for (let i = messages.value.length - 1; i >= 0; i--) {
        if (messages.value[i].role === 'bot') {
            return i
        }
    }
    return -1
})

const groups = computed(() => {
    const now = new Date()
    const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
    const day = 86400000
    const buckets = [
        { label: '今天', items: [] },
        { label: '昨天', items: [] },
        { label: '7 天内', items: [] },
        { label: '更早', items: [] }
    ]
    conversations.value.forEach(item => {
        const ts = new Date(item.updatedAt).getTime()
        if (Number.isNaN(ts)) {
            buckets[3].items.push(item)
        } else if (ts >= startOfToday) {
            buckets[0].items.push(item)
        } else if (ts >= startOfToday - day) {
            buckets[1].items.push(item)
        } else if (ts >= startOfToday - day * 6) {
            buckets[2].items.push(item)
        } else {
            buckets[3].items.push(item)
        }
    })
    return buckets.filter(bucket => bucket.items.length)
})

onMounted(async () => {
    await loadConversations()
    await loadRecommend()
    if (typeof route.query.ask === 'string' && route.query.ask) {
        question.value = route.query.ask
    }
})

function toMessage(item) {
    return {
        role: item.role === 'USER' ? 'user' : 'bot',
        text: item.content,
        references: item.references || [],
        matchedDocs: [],
        searchCalls: 0,
        readCount: 0,
        agentRunId: item.agentRunId || null,
        meta: item.promptTokens || item.completionTokens
            ? `token ${(item.promptTokens || 0) + (item.completionTokens || 0)}`
            : '',
        createdAt: item.createdAt || '',
        phase: '',
        error: false
    }
}

async function loadConversations() {
    try {
        conversations.value = (await fetchConversations(keyword.value || undefined)) || []
        if (!conversationId.value && conversations.value.length) {
            await openConversation(conversations.value[0].id)
        }
    } catch (e) {
        ElMessage.error(errorText(e))
    }
}

async function searchConversations() {
    await loadConversations()
}

async function openConversation(id) {
    loadingMessages.value = true
    try {
        const view = await fetchConversation(id)
        conversationId.value = view.id
        conversationTitle.value = view.title
        messages.value = (view.messages || []).map(toMessage)
        await loadProposals()
        await scrollToBottom()
    } catch (e) {
        ElMessage.error(errorText(e))
    } finally {
        loadingMessages.value = false
    }
}

function newConversation() {
    conversationId.value = null
    conversationTitle.value = ''
    messages.value = []
    proposals.value = []
    focusComposer()
}

function focusComposer() {
    nextTick(() => {
        if (composerInput.value) {
            composerInput.value.focus()
        }
    })
}

async function loadProposals() {
    if (!conversationId.value) {
        proposals.value = []
        return
    }
    try {
        proposals.value = (await fetchProposals(conversationId.value)) || []
    } catch (e) {
        proposals.value = []
    }
}

async function applyOne(item) {
    try {
        await applyProposal(item.id)
        ElMessage.success(`${item.fieldLabel} 已更新`)
        await loadProposals()
    } catch (e) {
        ElMessage.error(errorText(e))
    }
}

async function rejectOne(item) {
    try {
        await rejectProposalApi(item.id)
        ElMessage.success('已忽略该提案')
        await loadProposals()
    } catch (e) {
        ElMessage.error(errorText(e))
    }
}

async function renameCurrent() {
    if (!conversationId.value) {
        ElMessage.warning('新会话还没有可修改的标题')
        return
    }
    let value
    try {
        const result = await ElMessageBox.prompt('输入新的会话标题', '重命名会话', {
            inputValue: conversationTitle.value,
            inputValidator: text => (text && text.trim() ? true : '标题不能为空')
        })
        value = result.value
    } catch (e) {
        return
    }
    try {
        const view = await renameConversation(conversationId.value, value.trim())
        conversationTitle.value = view.title
        await loadConversations()
        ElMessage.success('已重命名')
    } catch (e) {
        ElMessage.error(errorText(e))
    }
}

async function renameOne(item) {
    if (item.id === conversationId.value) {
        await renameCurrent()
        return
    }
    await openConversation(item.id)
    await renameCurrent()
}

async function removeConversation(id) {
    try {
        await ElMessageBox.confirm('删除后该会话的历史消息不再保留，确定继续？', '删除会话', { type: 'warning' })
    } catch (e) {
        return
    }
    try {
        await deleteConversation(id)
        if (id === conversationId.value) {
            newConversation()
        }
        await loadConversations()
    } catch (e) {
        ElMessage.error(errorText(e))
    }
}

async function loadRecommend() {
    try {
        recommend.value = await learnRecommend(auth.userId.value)
    } catch (e) {
        recommend.value = { docs: [], pitfalls: [] }
    }
}

function openRecommend() {
    recommendOpen.value = true
    loadRecommend()
}

function openDoc(id) {
    router.push({ path: '/docs', query: { docId: id } })
}

async function submit() {
    const text = question.value.trim()
    if (!text || asking.value) {
        return
    }
    question.value = ''
    await ask(text)
}

async function ask(text) {
    asking.value = true
    messages.value.push({
        role: 'user',
        text,
        references: [],
        matchedDocs: [],
        searchCalls: 0,
        readCount: 0,
        agentRunId: null,
        meta: '',
        createdAt: localDateTime(),
        phase: '',
        error: false
    })
    messages.value.push({
        role: 'bot',
        text: '',
        references: [],
        matchedDocs: [],
        searchCalls: 0,
        readCount: 0,
        agentRunId: null,
        meta: '',
        createdAt: localDateTime(),
        phase: '正在检索知识库…',
        error: false
    })
    const bot = messages.value[messages.value.length - 1]
    await scrollToBottom()
    try {
        await askLearnStream(auth.userId.value, text, conversationId.value, {
            onEvent(event) {
                if (event.type === 'conversation') {
                    if (!conversationId.value) {
                        conversationTitle.value = text.length > 40 ? `${text.slice(0, 40)}…` : text
                    }
                    conversationId.value = event.conversationId
                } else if (event.type === 'run') {
                    bot.agentRunId = event.runId
                } else if (event.type === 'references') {
                    bot.references = event.references || []
                    bot.matchedDocs = event.matchedDocs || []
                    bot.searchCalls = event.searchCalls || 0
                    bot.readCount = event.readCount || 0
                    bot.phase = '正在作答…'
                } else if (event.type === 'delta') {
                    bot.text += event.text
                    bot.phase = ''
                } else if (event.type === 'done') {
                    bot.meta = `token ${(event.promptTokens || 0) + (event.completionTokens || 0)}`
                        + ` · ${((event.elapsedMs || 0) / 1000).toFixed(1)}s`
                } else if (event.type === 'error') {
                    bot.error = true
                    bot.text = `回答失败：${event.message}`
                    bot.phase = ''
                }
                scrollToBottom()
            }
        })
    } catch (e) {
        bot.error = true
        bot.text = `回答失败：${errorText(e)}`
        bot.phase = ''
    } finally {
        bot.phase = ''
        asking.value = false
        await loadConversations()
        await loadProposals()
        await scrollToBottom()
    }
}

async function doRewind(fallback) {
    if (asking.value || !conversationId.value) {
        return
    }
    try {
        const result = await rewindConversation(conversationId.value)
        messages.value.splice(Math.max(messages.value.length - 2, 0), 2)
        await ask(result.question || fallback || '')
    } catch (e) {
        ElMessage.error(errorText(e))
    }
}

async function regenerate(index) {
    if (index !== lastBotIndex.value) {
        ElMessage.warning('只能重新生成最后一条回答')
        return
    }
    await doRewind(messages.value[index - 1]?.text)
}

async function rewindLast() {
    if (messages.value.length < 2) {
        ElMessage.warning('当前会话还没有可回退的问答')
        return
    }
    await doRewind(messages.value[messages.value.length - 2]?.text)
}

function onEnter(event) {
    if (event.isComposing || event.shiftKey) {
        return
    }
    event.preventDefault()
    submit()
}

async function copy(message) {
    if (!message.text) {
        return
    }
    try {
        await navigator.clipboard.writeText(message.text)
        ElMessage.success('已复制')
    } catch (e) {
        ElMessage.warning('浏览器不允许自动复制，请手动选择文本')
    }
}

function citeSummary(message) {
    const parts = [`引用 ${(message.references || []).length} 篇`]
    if ((message.matchedDocs || []).length) {
        parts.push(`命中 ${message.matchedDocs.length}`)
    }
    if (message.searchCalls) {
        parts.push(`检索 ${message.searchCalls} 次`)
    }
    if (message.readCount) {
        parts.push(`读取 ${message.readCount} 篇`)
    }
    return parts.join(' · ')
}

function hasCite(message) {
    return (message.references || []).length
        || (message.matchedDocs || []).length
        || message.searchCalls
        || message.readCount
}

function pad(index) {
    return `${index}`.padStart(2, '0')
}

function fmtTime(value) {
    if (!value) {
        return ''
    }
    const text = formatDateTime(value)
    return text.length >= 16 ? text.slice(11, 16) : text
}

async function scrollToBottom() {
    await nextTick()
    if (chatBox.value) {
        chatBox.value.scrollTop = chatBox.value.scrollHeight
    }
}
</script>

<template>
    <div class="chat">
        <aside v-if="convOpen" class="chat-left">
            <button class="btn btn-primary" type="button" @click="newConversation">开启新对话</button>
            <input
                v-model="keyword"
                class="input w-full"
                type="text"
                placeholder="搜索历史会话"
                @keyup.enter="searchConversations"
            >
            <div class="chat-left-scroll">
                <div v-if="!conversations.length" class="empty">还没有历史会话</div>
                <template v-for="group in groups" :key="group.label">
                    <div class="conv-group">{{ group.label }}</div>
                    <div
                        v-for="item in group.items"
                        :key="item.id"
                        class="conv-item"
                        :class="{ active: item.id === conversationId }"
                    >
                        <button class="conv-link" type="button" @click="openConversation(item.id)">
                            <span>{{ item.title }}</span>
                        </button>
                        <details class="menu conv-menu">
                            <summary>···</summary>
                            <div class="menu-panel">
                                <button type="button" @click="renameOne(item)">重命名会话</button>
                                <button type="button" class="danger" @click="removeConversation(item.id)">删除会话</button>
                            </div>
                        </details>
                    </div>
                </template>
            </div>
        </aside>

        <section class="chat-mid">
            <div class="chat-head">
                <button
                    class="conv-toggle"
                    :class="{ closed: !convOpen }"
                    type="button"
                    :title="convOpen ? '收起会话列表' : '展开会话列表'"
                    :aria-label="convOpen ? '收起会话列表' : '展开会话列表'"
                    @click="convOpen = !convOpen"
                >
                    <svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M10 4L6 8l4 4" /></svg>
                </button>
                <span class="chat-title">{{ conversationTitle || '新对话' }}</span>
                <div class="sec-actions">
                    <button class="btn btn-sm btn-ghost" type="button" @click="openRecommend">推荐</button>
                    <details class="menu">
                        <summary>···</summary>
                        <div class="menu-panel">
                            <button type="button" :disabled="!conversationId" @click="renameCurrent">重命名会话</button>
                            <button type="button" :disabled="!conversationId || asking" @click="rewindLast">回退上一轮</button>
                            <button
                                type="button"
                                class="danger"
                                :disabled="!conversationId"
                                @click="removeConversation(conversationId)"
                            >删除会话</button>
                        </div>
                    </details>
                </div>
            </div>

            <div
                ref="chatBox"
                v-loading="loadingMessages"
                class="chat-scroll"
                :class="{ 'chat-scroll-empty': isEmpty }"
                aria-live="polite"
            >
                <div class="chat-inner" :class="{ 'chat-inner-empty': isEmpty }">
                    <div v-if="isEmpty" class="chat-welcome">
                        <div class="welcome-title">
                            <BrandIcon variant="solid" :size="26" />
                            <span>嗨！今天想聊些什么？</span>
                        </div>
                        <div class="welcome-box">
                            <textarea
                                ref="composerInput"
                                v-model="question"
                                placeholder="问点什么，回车发送，Shift + 回车换行"
                                :disabled="asking"
                                @keydown.enter="onEnter"
                            ></textarea>
                            <button
                                class="btn btn-primary btn-icon send-btn"
                                type="button"
                                aria-label="发送"
                                :disabled="asking || !question.trim()"
                                @click="submit"
                            >↑</button>
                        </div>
                        <div class="welcome-tip">基于团队知识库，可能不完整。</div>
                    </div>

                    <div
                        v-for="(message, index) in messages"
                        :key="index"
                        class="msg"
                        :class="message.role === 'user' ? 'msg-user' : 'msg-ai'"
                    >
                        <div class="msg-avatar">
                            <BrandIcon v-if="message.role !== 'user'" variant="solid" :size="26" />
                            <span v-else>{{ userName.slice(0, 1) }}</span>
                        </div>
                        <div class="msg-body">
                            <template v-if="message.role === 'user'">
                                <div class="bubble">{{ message.text }}</div>
                            </template>
                            <template v-else>
                                <div v-if="message.error" class="callout rose">
                                    <span class="dot rose"></span>
                                    <span>{{ message.text }}</span>
                                    <span class="co-actions">
                                        <button class="btn btn-sm" type="button" :disabled="asking" @click="doRewind(messages[index - 1]?.text)">重试</button>
                                    </span>
                                </div>
                                <template v-else>
                                    <div v-if="message.phase" class="msg-phase">{{ message.phase }}</div>
                                    <div v-if="message.text" class="doc">
                                        <MarkdownView :content="message.text" />
                                    </div>
                                    <details v-if="hasCite(message)" class="cite">
                                        <summary>{{ citeSummary(message) }}</summary>
                                        <div class="cite-list">
                                            <button
                                                v-for="(doc, i) in message.references"
                                                :key="doc.id"
                                                type="button"
                                                class="cite-item"
                                                @click="openDoc(doc.id)"
                                            >
                                                <span class="idx">{{ pad(i + 1) }}</span>
                                                <span>{{ doc.title }}</span>
                                                <span v-if="doc.category" class="tiny muted cite-cat">{{ doc.category }}</span>
                                            </button>
                                            <button
                                                v-for="doc in message.matchedDocs"
                                                :key="doc.id"
                                                type="button"
                                                class="cite-item"
                                                @click="openDoc(doc.id)"
                                            >
                                                <span class="idx">··</span>
                                                <span>{{ doc.title }}</span>
                                                <span v-if="doc.category" class="tiny muted cite-cat">{{ doc.category }}</span>
                                            </button>
                                        </div>
                                    </details>
                                    <AgentTrace
                                        v-if="message.agentRunId"
                                        :run-id="message.agentRunId"
                                        title="智能体执行轨迹"
                                        dense
                                    />
                                    <div class="msg-foot">
                                        <span>{{ fmtTime(message.createdAt) }}</span>
                                        <span v-if="message.meta" class="tiny muted">{{ message.meta }}</span>
                                        <button class="btn btn-sm btn-ghost" type="button" @click="copy(message)">复制</button>
                                        <button
                                            v-if="index === lastBotIndex"
                                            class="btn btn-sm btn-ghost"
                                            type="button"
                                            :disabled="asking"
                                            @click="regenerate(index)"
                                        >重新生成</button>
                                    </div>
                                </template>
                            </template>
                        </div>
                    </div>

                    <div v-if="proposals.length" class="proposal">
                        <div class="proposal-head">助手想改你的配置，确认后才生效</div>
                        <div v-for="item in proposals" :key="item.id" class="proposal-row">
                            <div class="pr-text">
                                <span class="mono">{{ item.fieldLabel }}</span> 改为「{{ item.fieldValue }}」<span v-if="item.reason" class="muted"> · {{ item.reason }}</span>
                            </div>
                            <div class="pr-actions">
                                <button class="btn btn-sm btn-ghost" type="button" @click="rejectOne(item)">忽略</button>
                                <button class="btn btn-sm btn-primary" type="button" @click="applyOne(item)">应用</button>
                            </div>
                        </div>
                    </div>
                </div>
            </div>

            <div v-if="!isEmpty" class="composer-wrap">
                <div class="composer">
                    <textarea
                        ref="composerInput"
                        v-model="question"
                        placeholder="问点什么，回车发送，Shift + 回车换行"
                        :disabled="asking"
                        @keydown.enter="onEnter"
                    ></textarea>
                    <button
                        class="btn btn-primary btn-icon send-btn"
                        type="button"
                        aria-label="发送"
                        :disabled="asking || !question.trim()"
                        @click="submit"
                    >↑</button>
                </div>
                <div class="composer-tip">基于团队知识库，可能不完整。</div>
            </div>
        </section>

        <el-dialog v-model="recommendOpen" title="为你推荐" width="420">
            <div class="dialog-sub-slot">按当前会话主题匹配</div>
            <div v-if="recommendDocs.length" class="rows rows-divided">
                <div
                    v-for="doc in recommendDocs"
                    :key="doc.id"
                    class="row-item"
                    role="button"
                    tabindex="0"
                    @click="openDoc(doc.id)"
                    @keydown.enter="openDoc(doc.id)"
                >
                    <div class="row-main">
                        <span class="row-title">{{ doc.title }}</span>
                        <span class="row-sub">{{ doc.category }}<template v-if="(doc.tags || []).length"> · {{ (doc.tags || []).join(' · ') }}</template></span>
                    </div>
                    <span class="chev">›</span>
                </div>
            </div>
            <div v-else class="empty">暂时没有匹配到相关文档</div>

            <div class="sub-title pitfall-title">可能踩到的坑位</div>
            <div v-if="recommendPitfalls.length" class="rows rows-divided">
                <div v-for="pitfall in recommendPitfalls" :key="pitfall.id" class="row-item">
                    <div class="row-main">
                        <span class="row-title">{{ pitfall.title }}</span>
                        <span class="row-sub">{{ (pitfall.tags || []).join(' · ') }}<template v-if="pitfall.solution"> · 解法：{{ pitfall.solution }}</template></span>
                    </div>
                </div>
            </div>
            <div v-else class="empty">当前没有命中的坑位</div>

            <template #footer>
                <button class="btn btn-ghost" type="button" @click="recommendOpen = false">关闭</button>
                <button class="btn" type="button" @click="loadRecommend">换一批</button>
            </template>
        </el-dialog>
    </div>
</template>

<style scoped>
.conv-item {
    gap: var(--space-1);
}

.conv-link {
    flex: 1;
    min-width: 0;
    display: flex;
    background: transparent;
    border: 0;
    padding: 0;
    text-align: left;
    color: inherit;
    cursor: pointer;
}

.conv-item .menu > summary {
    width: var(--size-control-sm);
    height: var(--size-control-sm);
    font-size: var(--font-size-14);
    color: var(--text-3);
}

.conv-menu {
    flex: none;
}

.chat-mid {
    position: relative;
}

.conv-toggle.closed svg {
    transform: rotate(180deg);
}

.chat-empty {
    justify-content: center;
}

.chat-scroll-empty {
    display: flex;
    align-items: center;
    justify-content: center;
}

.chat-inner-empty {
    width: 100%;
}

.chat-welcome {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: var(--space-4);
    padding-bottom: var(--space-9);
}

.welcome-title {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    font-size: var(--font-size-20);
    font-weight: var(--fw-semibold);
    color: var(--text-1);
}

.welcome-box {
    width: 100%;
    max-width: 640px;
    display: flex;
    align-items: flex-end;
    gap: var(--space-2);
    padding: var(--space-2) var(--space-2) var(--space-2) var(--space-3);
    background: var(--surface-1);
    border-radius: var(--radius-3);
}

.welcome-box textarea {
    flex: 1;
    min-height: 52px;
    max-height: 120px;
    border: 0;
    background: transparent;
    outline: none;
    resize: none;
    padding: var(--space-1) 0;
    font: inherit;
    font-size: var(--font-size-13);
    line-height: 1.6;
    color: var(--text-1);
}

.welcome-tip {
    font-size: var(--font-size-11);
    color: var(--text-4);
}

.msg-phase {
    font-size: var(--font-size-12);
    color: var(--text-3);
}

.cite-item {
    width: 100%;
    border: 0;
    background: transparent;
    text-align: left;
    cursor: pointer;
}

.cite-item:hover {
    color: var(--color-brand-hover);
}

.cite-cat {
    margin-left: auto;
}

.send-btn {
    flex: none;
}

.pitfall-title {
    margin-top: var(--space-5);
}

.dialog-sub-slot {
    font-size: var(--font-size-12);
    color: var(--text-3);
    margin-bottom: var(--space-3);
}
</style>
<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
    checkDocDuplicate,
    errorText,
    fetchDoc,
    fetchDocs,
    fetchPitfalls,
    refreshDocs,
    uploadDoc
} from '../api'
import { useAuth } from '../store/auth'
import MarkdownView from '../components/MarkdownView.vue'
import { formatDateTime } from '../utils/format'

const auth = useAuth()
const route = useRoute()
const router = useRouter()

const CATEGORIES = ['规范', '环境搭建', '业务背景', '排障']
const PAGE_SIZE = 10
const TRUNCATED_FLAG = '正文超长已截断'

const activeTab = ref('docs')
const loading = ref(false)
const loadError = ref('')
const docs = ref([])
const pitfalls = ref([])
const detail = ref(null)
const drawerVisible = ref(false)
const keyword = ref('')
const category = ref('')
const page = ref(1)

const uploadOpen = ref(false)
const uploading = ref(false)
const uploadFile = ref(null)
const duplicateOpen = ref(false)
const duplicateInfo = ref(null)
const form = ref(emptyForm())

const isMentor = computed(() => auth.isMentor.value)
const docsCount = computed(() => docs.value.length)
const pitfallsCount = computed(() => pitfalls.value.length)

const updatedLabel = computed(() => {
    const latest = docs.value.reduce(
        (max, doc) => (doc.updatedAt && doc.updatedAt > max ? doc.updatedAt : max),
        ''
    )
    return latest ? `更新于 ${dateShort(latest)}` : ''
})

const totalPages = computed(() => Math.max(1, Math.ceil(docs.value.length / PAGE_SIZE)))
const pagedDocs = computed(() => docs.value.slice((page.value - 1) * PAGE_SIZE, page.value * PAGE_SIZE))

watch(totalPages, value => {
    if (page.value > value) {
        page.value = value
    }
})

onMounted(async () => {
    await Promise.all([loadDocs(), loadPitfalls()])
    const docId = route.query.docId
    if (docId) {
        await openDoc(Number(docId))
    }
})

watch(() => route.query.docId, value => {
    if (value) {
        openDoc(Number(value))
    }
})

function emptyForm() {
    return { title: '', category: '规范', visibility: 'TEAM' }
}

function dateShort(value) {
    return value ? formatDateTime(value).slice(5, 10) : ''
}

function clipSummary(text) {
    const value = text || '（无摘要）'
    return value.length > 60 ? `${value.slice(0, 60)}…` : value
}

function readMinutes(content) {
    const minutes = Math.ceil((content || '').length / 400)
    return minutes < 1 ? '<1 分钟' : `${minutes} 分钟`
}

function isTruncated(content) {
    return (content || '').includes(TRUNCATED_FLAG)
}

async function loadDocs() {
    loading.value = true
    loadError.value = ''
    try {
        docs.value = (await fetchDocs({
            keyword: keyword.value || undefined,
            category: category.value || undefined
        })) || []
        page.value = 1
    } catch (e) {
        loadError.value = errorText(e)
    } finally {
        loading.value = false
    }
}

async function loadPitfalls() {
    try {
        pitfalls.value = (await fetchPitfalls()) || []
    } catch (e) {
        ElMessage.error(errorText(e))
    }
}

function resetFilters() {
    keyword.value = ''
    category.value = ''
    page.value = 1
    loadDocs()
}

function pickCategory(value) {
    category.value = value
    loadDocs()
}

async function openDoc(id) {
    try {
        detail.value = await fetchDoc(id)
        drawerVisible.value = true
    } catch (e) {
        ElMessage.error(errorText(e))
    }
}

function closeDrawer() {
    drawerVisible.value = false
    if (route.query.docId) {
        router.replace({ path: '/docs' })
    }
}

async function sync() {
    loading.value = true
    try {
        const result = await refreshDocs()
        ElMessage.success(`同步完成，文档 ${result.docs} 篇，坑位 ${result.pitfalls} 条`)
        await Promise.all([loadDocs(), loadPitfalls()])
    } catch (e) {
        ElMessage.error(errorText(e))
    } finally {
        loading.value = false
    }
}

function openUpload() {
    uploadOpen.value = true
}

function pickFile(file) {
    uploadFile.value = file.raw
    if (!form.value.title) {
        form.value.title = (file.name || '').replace(/\.(md|txt)$/i, '')
    }
}

function resetUpload() {
    uploadFile.value = null
    form.value = emptyForm()
}

async function submitUpload() {
    if (!uploadFile.value) {
        ElMessage.warning('请先选择文件')
        return
    }
    try {
        const check = await checkDocDuplicate(uploadFile.value.name)
        if (check.exists) {
            duplicateInfo.value = check
            duplicateOpen.value = true
            return
        }
    } catch (e) {
        ElMessage.warning(`同名检测失败，将按另存处理：${errorText(e)}`)
    }
    await doUpload(false)
}

async function doUpload(overwrite) {
    duplicateOpen.value = false
    uploading.value = true
    try {
        const data = new FormData()
        data.append('file', uploadFile.value)
        data.append('title', form.value.title)
        data.append('category', form.value.category)
        data.append('tags', '')
        data.append('source', '上传')
        data.append('visibility', form.value.visibility)
        const result = await uploadDoc(data, overwrite)
        ElMessage.success(overwrite ? '已覆盖原文档' : '已上传并同步')
        uploadOpen.value = false
        resetUpload()
        await loadDocs()
        const matched = docs.value.find(doc => doc.title === result.title)
        if (matched) {
            await openDoc(matched.id)
        }
    } catch (e) {
        ElMessage.error(errorText(e))
    } finally {
        uploading.value = false
    }
}

function askWithDoc() {
    if (!detail.value) {
        return
    }
    router.push({ path: '/learn', query: { sign: detail.value.doc.id } })
}
</script>

<template>
    <div v-loading="loading" class="page">
        <header class="page-head">
            <div>
                <div v-if="updatedLabel" class="eyebrow">{{ updatedLabel }}</div>
                <h1 class="h1">文档库</h1>
            </div>
            <div class="page-actions">
                <button v-if="isMentor" class="btn" type="button" @click="sync">同步知识目录</button>
                <button class="btn" type="button" @click="openUpload">上传文档</button>
            </div>
        </header>
        <div class="divider-full"></div>

        <div v-if="loadError" class="error-line">
            <div class="callout rose">
                <span class="dot rose"></span>
                <span>{{ loadError }}</span>
                <span class="co-actions">
                    <button class="btn btn-sm" type="button" @click="loadDocs">重试</button>
                </span>
            </div>
        </div>

        <template v-else>
            <section class="section">
                <div class="tabs" role="tablist" aria-label="文档与坑位">
                    <button
                        class="tab"
                        type="button"
                        role="tab"
                        :aria-selected="activeTab === 'docs'"
                        :class="{ active: activeTab === 'docs' }"
                        @click="activeTab = 'docs'"
                    >知识文档<span class="tab-num num">{{ docsCount }}</span></button>
                    <button
                        class="tab"
                        type="button"
                        role="tab"
                        :aria-selected="activeTab === 'pitfalls'"
                        :class="{ active: activeTab === 'pitfalls' }"
                        @click="activeTab = 'pitfalls'"
                    >坑位库<span class="tab-num num">{{ pitfallsCount }}</span></button>
                </div>

                <div v-show="activeTab === 'docs'" role="tabpanel">
                    <div class="filter-row">
                        <input
                            v-model="keyword"
                            class="input w-240"
                            placeholder="搜索标题、摘要、标签"
                            @keyup.enter="loadDocs"
                        />
                        <button
                            class="chip"
                            type="button"
                            :class="{ active: category === '' }"
                            @click="pickCategory('')"
                        >全部</button>
                        <button
                            v-for="item in CATEGORIES"
                            :key="item"
                            class="chip"
                            type="button"
                            :class="{ active: category === item }"
                            @click="pickCategory(item)"
                        >{{ item }}</button>
                        <button class="btn btn-sm btn-ghost" type="button" @click="resetFilters">重置</button>
                    </div>

                    <div v-if="docs.length" class="rows rows-divided">
                        <div
                            v-for="doc in pagedDocs"
                            :key="doc.id"
                            class="row-item doc-row"
                            role="button"
                            tabindex="0"
                            @click="openDoc(doc.id)"
                            @keydown.enter="openDoc(doc.id)"
                        >
                            <div class="row-main">
                                <span class="row-title">{{ doc.title }}</span>
                                <span class="row-sub doc-summary">{{ clipSummary(doc.summary) }}</span>
                            </div>
                            <span class="tag doc-tag">{{ doc.category }}</span>
                            <span class="tiny muted num doc-meta">
                                {{ doc.uploaderName || '随目录同步' }} · {{ dateShort(doc.updatedAt || doc.uploadedAt) }}
                            </span>
                            <span class="chev">›</span>
                        </div>
                    </div>
                    <div v-else class="empty">
                        还没有文档，上传第一篇吧
                        <button class="btn btn-sm" type="button" @click="openUpload">上传文档</button>
                    </div>

                    <div v-if="docs.length" class="pager">
                        <button class="btn btn-sm btn-ghost" type="button" :disabled="page <= 1" @click="page -= 1">上一页</button>
                        <span class="tiny muted num">第 {{ page }} / {{ totalPages }} 页</span>
                        <button class="btn btn-sm" type="button" :disabled="page >= totalPages" @click="page += 1">下一页</button>
                    </div>
                </div>

                <div v-show="activeTab === 'pitfalls'" role="tabpanel">
                    <div v-if="pitfalls.length" class="doc">
                        <div v-for="pitfall in pitfalls" :key="pitfall.id" class="sub">
                            <div class="pit-head">
                                <span class="h2">{{ pitfall.title }}</span>
                                <span v-for="tag in (pitfall.tags || [])" :key="tag" class="tag">{{ tag }}</span>
                            </div>
                            <div class="pit-block">
                                <div class="sub-title">症状</div>
                                <p class="small">{{ pitfall.symptom || '-' }}</p>
                            </div>
                            <div class="pit-block">
                                <div class="sub-title">原因</div>
                                <p class="small">{{ pitfall.cause || '-' }}</p>
                            </div>
                            <div class="pit-block">
                                <div class="sub-title">解法</div>
                                <p class="small">{{ pitfall.solution || '-' }}</p>
                            </div>
                        </div>
                    </div>
                    <div v-else class="empty">坑位库还是空的，同步知识目录后会出现</div>
                </div>
            </section>
        </template>

        <el-drawer
            v-model="drawerVisible"
            size="480px"
            :title="detail ? detail.doc.title : ''"
            @closed="closeDrawer"
        >
            <template v-if="detail">
                <div class="tiny muted doc-meta-line">
                    {{ detail.doc.category }} · {{ detail.doc.uploaderName || '随目录同步' }} · 更新于 {{ dateShort(detail.doc.updatedAt) }} · {{ readMinutes(detail.content) }}
                </div>
                <div v-if="isTruncated(detail.content)" class="callout rose truncation">
                    <span class="dot rose"></span>
                    <span>正文超过读取上限，只显示前半段，完整内容请查看原文件</span>
                </div>
                <div class="doc doc-body">
                    <MarkdownView :content="detail.content" />
                </div>
            </template>
            <template #footer>
                <button class="btn btn-ghost" type="button" @click="drawerVisible = false">关闭</button>
                <button class="btn" type="button" @click="askWithDoc">用这篇文档提问</button>
            </template>
        </el-drawer>

        <el-dialog v-model="uploadOpen" title="上传文档" width="520" @closed="resetUpload">
            <div class="field">
                <span class="field-label">文件</span>
                <div class="field-body">
                    <el-upload :auto-upload="false" :limit="1" accept=".md,.txt" :on-change="pickFile">
                        <button class="btn btn-sm" type="button">选择文件</button>
                    </el-upload>
                </div>
            </div>
            <div class="field">
                <span class="field-label">标题</span>
                <div class="field-body">
                    <el-input v-model="form.title" placeholder="不填则用文件名" />
                </div>
            </div>
            <div class="field">
                <span class="field-label">分类</span>
                <div class="field-body">
                    <el-select v-model="form.category">
                        <el-option v-for="item in CATEGORIES" :key="item" :label="item" :value="item" />
                    </el-select>
                </div>
            </div>
            <div class="field">
                <span class="field-label">可见范围</span>
                <div class="field-body">
                    <el-select v-model="form.visibility">
                        <el-option label="团队可见" value="TEAM" />
                        <el-option label="仅本人" value="PRIVATE" />
                    </el-select>
                </div>
            </div>
            <template #footer>
                <button class="btn btn-ghost" type="button" @click="uploadOpen = false">取消</button>
                <button class="btn btn-primary" type="button" :disabled="uploading" @click="submitUpload">上传并同步</button>
            </template>
        </el-dialog>

        <el-dialog v-model="duplicateOpen" title="已存在同名文档" width="420">
            <p v-if="duplicateInfo" class="small">
                知识库里已有 <b>{{ duplicateInfo.fileName }}</b><span v-if="duplicateInfo.title">（{{ duplicateInfo.title }}）</span>。覆盖会替换原文档的正文与元数据，另存会保留原文档并自动加序号。
            </p>
            <template #footer>
                <button class="btn btn-ghost" type="button" @click="duplicateOpen = false">取消</button>
                <button class="btn" type="button" :disabled="uploading" @click="doUpload(false)">另存为新文档</button>
                <button class="btn btn-primary" type="button" :disabled="uploading" @click="doUpload(true)">覆盖原文档</button>
            </template>
        </el-dialog>
    </div>
</template>

<style scoped>
.error-line {
    padding: var(--space-5) 0 0;
}

.filter-row {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: var(--space-2);
    padding-bottom: var(--space-3);
}

.doc-row {
    cursor: pointer;
}

.doc-summary {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.doc-tag {
    flex: none;
}

.doc-meta {
    flex: none;
    white-space: nowrap;
}

.pager {
    display: flex;
    align-items: center;
    gap: var(--space-3);
    padding-top: var(--space-4);
}

.pit-head {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: var(--space-2);
}

.pit-block {
    margin-top: var(--space-3);
}

.doc-meta-line {
    margin-bottom: var(--space-3);
}

.truncation {
    margin-bottom: var(--space-3);
}

.doc-body {
    padding-bottom: var(--space-4);
}

.field-body :deep(.el-select) {
    width: 100%;
}
</style>
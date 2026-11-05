/**
 * 令牌门禁。
 *
 * 三件事：
 * 1. src/styles/tokens.css 必须与 docs/tokens.css 完全一致，防止副本漂移。
 * 2. 已改造的文件里不允许出现裸 hex / rgb / 裸圆角 / 裸字号 / 700 以上字重。
 * 3. 旧文件已清空，新增文件一律按已改造标准校验。
 *
 * 用法：node scripts/check-tokens.mjs
 */

import { readFile, readdir, stat } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import { join, relative, resolve } from 'node:path'

const ROOT = resolve(process.cwd())
const SRC = join(ROOT, 'src')
const TOKEN_COPY = join(SRC, 'styles', 'tokens.css')
const TOKEN_SOURCE = join(ROOT, '..', 'docs', 'tokens.css')

/** 旧页面已全部删除，不再保留豁免名单 */
const LEGACY = new Set()

const HEX = /#[0-9a-fA-F]{3,8}\b/g
const RGB = /\brgba?\s*\(/g
const RADIUS = /border-radius\s*:\s*(?!var\()[^;]*?\d+px/g
const FONT_SIZE = /font-size\s*:\s*(?!var\()[^;]*?\d+px/g
const BOLD = /font-weight\s*:\s*(?:[7-9]\d{2}|bold)/g

async function walk(dir) {
    const entries = await readdir(dir, { withFileTypes: true })
    const files = []
    for (const entry of entries) {
        const full = join(dir, entry.name)
        if (entry.isDirectory()) {
            files.push(...await walk(full))
        } else if (/\.(vue|css|js)$/.test(entry.name)) {
            files.push(full)
        }
    }
    return files
}

function normalize(text) {
    return text.replace(/^\uFEFF/, '').replace(/\r\n/g, '\n').trim()
}

async function checkTokenCopy(errors) {
    if (!existsSync(TOKEN_SOURCE)) {
        console.log('· 跳过令牌一致性检查：找不到 docs/tokens.css')
        return
    }
    const [copy, source] = await Promise.all([
        readFile(TOKEN_COPY, 'utf8'),
        readFile(TOKEN_SOURCE, 'utf8')
    ])
    if (normalize(copy) !== normalize(source)) {
        errors.push('src/styles/tokens.css 与 docs/tokens.css 不一致，运行同步后重试')
    }
}

async function main() {
    const errors = []
    const pending = []

    await checkTokenCopy(errors)

    if (!existsSync(SRC)) {
        console.error('找不到 src 目录，请在 intern-radar-frontend 下运行')
        process.exit(1)
    }

    const files = await walk(SRC)
    for (const file of files) {
        const rel = relative(ROOT, file).replace(/\\/g, '/')
        if (rel === 'src/styles/tokens.css') {
            continue
        }
        const content = await readFile(file, 'utf8')
        const hits = []
        for (const [label, pattern] of [['裸 hex', HEX], ['裸 rgb', RGB], ['裸圆角', RADIUS], ['裸字号', FONT_SIZE], ['字重 700+', BOLD]]) {
            const matches = content.match(pattern)
            if (matches) {
                matches.forEach(match => hits.push(`${label}：${match.trim()}`))
            }
        }
        if (!hits.length) {
            continue
        }
        if (LEGACY.has(rel)) {
            pending.push({ rel, count: hits.length })
        } else {
            hits.slice(0, 8).forEach(hit => errors.push(`${rel} → ${hit}`))
        }
    }

    if (pending.length) {
        console.log(`· 待迁移文件 ${pending.length} 个（本轮豁免）`)
        pending
            .sort((left, right) => left.rel.localeCompare(right.rel))
            .forEach(item => console.log(`  ${item.rel}：${item.count} 处`))
        console.log('')
    }

    if (errors.length) {
        console.error(`✗ 令牌检查未通过，共 ${errors.length} 处：`)
        errors.forEach(error => console.error(`  ${error}`))
        process.exit(1)
    }
    console.log('✓ 令牌检查通过：副本一致，已改造文件 0 处裸值')
}

main().catch(error => {
    console.error(error)
    process.exit(1)
})

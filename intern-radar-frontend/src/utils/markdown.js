import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js'
import DOMPurify from 'dompurify'
import 'highlight.js/styles/github.css'

/**
 * Markdown 渲染：两层防护
 * 第一层 markdown-it 关闭内联 HTML，源码里的标签会原样当文本输出
 * 第二层对渲染结果做一遍清洗，兜住链接与图片属性这类向量
 */
const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
  highlight(code, lang) {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return hljs.highlight(code, { language: lang }).value
      } catch (e) {
        return ''
      }
    }
    return ''
  }
})

const defaultLinkOpen = md.renderer.rules.link_open
  || ((tokens, idx, options, env, self) => self.renderToken(tokens, idx, options))

md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  tokens[idx].attrSet('target', '_blank')
  tokens[idx].attrSet('rel', 'noopener noreferrer')
  return defaultLinkOpen(tokens, idx, options, env, self)
}

export function renderMarkdown(text) {
  if (!text) {
    return ''
  }
  const html = md.render(String(text))
  return DOMPurify.sanitize(html, { ADD_ATTR: ['target', 'rel'] })
}

export function plainText(text) {
  if (!text) {
    return ''
  }
  return String(text).replace(/[#*`>|-]/g, '').replace(/\s+/g, ' ').trim()
}

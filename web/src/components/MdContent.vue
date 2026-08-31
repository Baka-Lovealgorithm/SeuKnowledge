<template>
  <div ref="rootRef" class="md-content" v-html="renderedHtml"></div>
</template>

<script setup>
import { computed, onMounted, onUpdated, nextTick, ref } from 'vue'
import { renderMarkdown } from '../utils/markdown'
import 'highlight.js/styles/github-dark.css'

const props = defineProps({
  content: { type: String, default: '' }
})

const rootRef = ref(null)
const renderedHtml = computed(() => renderMarkdown(props.content))

function injectCodeBlocks() {
  const root = rootRef.value
  if (!root) return
  const pres = root.querySelectorAll('pre')
  pres.forEach((pre) => {
    if (pre.closest('.code-block-wrapper')) return

    // 外层卡片容器
    const wrapper = document.createElement('div')
    wrapper.className = 'code-block-wrapper'
    pre.parentNode.insertBefore(wrapper, pre)
    wrapper.appendChild(pre)

    // 头部栏：左侧语言标签 + 右侧复制按钮
    const header = document.createElement('div')
    header.className = 'code-block-header'
    wrapper.insertBefore(header, pre)

    const code = pre.querySelector('code')
    const lang = code ? (code.className.replace('hljs language-', '').trim() || code.className.trim()) : ''
    const label = document.createElement('span')
    label.className = 'code-lang-label'
    label.textContent = lang || 'code'
    header.appendChild(label)

    const btn = document.createElement('button')
    btn.className = 'code-copy-btn'
    btn.type = 'button'
    btn.innerHTML =
      '<svg viewBox="0 0 16 16" width="13" height="13" fill="currentColor" aria-hidden="true">' +
      '<path d="M0 6.75C0 5.784.784 5 1.75 5h1.5a.75.75 0 0 1 0 1.5h-1.5a.25.25 0 0 0-.25.25v7.5c0 .138.112.25.25.25h7.5a.25.25 0 0 0 .25-.25v-1.5a.75.75 0 0 1 1.5 0v1.5A1.75 1.75 0 0 1 9.25 16h-7.5A1.75 1.75 0 0 1 0 14.25v-7.5Z"/>' +
      '<path d="M5 1.75C5 .784 5.784 0 6.75 0h7.5C15.216 0 16 .784 16 1.75v7.5A1.75 1.75 0 0 1 14.25 11h-7.5A1.75 1.75 0 0 1 5 9.25v-7.5Zm1.75-.25a.25.25 0 0 0-.25.25v7.5c0 .138.112.25.25.25h7.5a.25.25 0 0 0 .25-.25v-7.5a.25.25 0 0 0-.25-.25h-7.5Z"/>' +
      '</svg><span>复制</span>'
    btn.title = '复制代码'
    btn.onclick = () => {
      const text = pre.textContent || ''
      navigator.clipboard.writeText(text).then(() => {
        btn.classList.add('copied')
        btn.querySelector('span').textContent = '已复制'
        setTimeout(() => {
          btn.classList.remove('copied')
          btn.querySelector('span').textContent = '复制'
        }, 2000)
      }).catch(() => {})
    }
    header.appendChild(btn)
  })
}

onMounted(() => nextTick(injectCodeBlocks))
onUpdated(() => nextTick(injectCodeBlocks))
</script>

<style scoped>
.md-content { line-height: 1.7; word-break: break-word; }
.md-content :deep(p) { margin: 0.35em 0; }
.md-content :deep(strong) { font-weight: 700; }
.md-content :deep(em) { font-style: italic; }
.md-content :deep(ul), .md-content :deep(ol) { padding-left: 1.5em; margin: 0.35em 0; }
.md-content :deep(li) { margin: 0.15em 0; }
.md-content :deep(> :first-child) { margin-top: 0; }
.md-content :deep(> :last-child) { margin-bottom: 0; }
.md-content :deep(h1), .md-content :deep(h2), .md-content :deep(h3) { margin: 0.8em 0 0.4em; font-weight: 700; }
.md-content :deep(blockquote) { border-left: 3px solid #409eff; padding: 2px 10px; margin: 0.5em 0; color: #606266; }
.md-content :deep(code) { font-family: Consolas, Menlo, Monaco, monospace; font-size: 0.9em; background: #f0f2f5; padding: 1px 5px; border-radius: 3px; }
.md-content :deep(table) { border-collapse: collapse; margin: 0.6em 0; width: 100%; font-size: 0.92em; }
.md-content :deep(th), .md-content :deep(td) { border: 1px solid #dcdfe6; padding: 5px 10px; text-align: left; }
.md-content :deep(thead th) { background: #f5f7fa; font-weight: 600; }

/* ===== 代码块卡片（深色，仿 DeepSeek 官网） ===== */
.md-content :deep(.code-block-wrapper) {
  margin: 0.8em 0;
  border: 1px solid #30363d;
  border-radius: 8px;
  background: #1f242f;
  overflow: hidden;
}
.md-content :deep(.code-block-header) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 12px;
  background: #262c38;
  border-bottom: 1px solid #30363d;
}
.md-content :deep(.code-lang-label) {
  font-size: 12px;
  font-weight: 600;
  color: #8b949e;
  font-family: Consolas, Menlo, Monaco, monospace;
}
.md-content :deep(.code-copy-btn) {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: #8b949e;
  background: transparent;
  border: 1px solid transparent;
  border-radius: 4px;
  padding: 2px 8px;
  cursor: pointer;
  line-height: 1.5;
  transition: all 0.15s;
}
.md-content :deep(.code-copy-btn:hover) {
  background: rgba(255, 255, 255, 0.06);
  border-color: #30363d;
  color: #e6edf3;
}
.md-content :deep(.code-copy-btn.copied) {
  color: #3fb950;
  border-color: #3fb950;
  background: rgba(63, 185, 80, 0.12);
}
.md-content :deep(pre) { overflow-x: auto; margin: 0; background: #1f242f; padding: 14px 16px; }
.md-content :deep(pre code) { background: none; padding: 0; font-size: 0.88em; }
.md-content :deep(pre code.hljs) { background: transparent; }
</style>
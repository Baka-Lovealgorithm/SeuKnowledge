<template>
  <div class="md-content" v-html="renderedHtml"></div>
</template>

<script setup>
import { computed, onMounted, onUpdated, nextTick } from 'vue'
import { renderMarkdown } from '../utils/markdown'
import 'highlight.js/styles/github-dark.css'

const props = defineProps({
  content: { type: String, default: '' }
})

const renderedHtml = computed(() => renderMarkdown(props.content))

function injectCopyButtons() {
  const pres = document.querySelectorAll('.md-content pre')
  pres.forEach((pre) => {
    if (pre.closest('.code-block-wrapper')) return
    const wrapper = document.createElement('div')
    wrapper.className = 'code-block-wrapper'
    pre.parentNode.insertBefore(wrapper, pre)
    wrapper.appendChild(pre)

    const code = pre.querySelector('code')
    const lang = code ? (code.className.replace('hljs language-', '').trim() || code.className.trim()) : ''
    if (lang) {
      const label = document.createElement('span')
      label.className = 'code-lang-label'
      label.textContent = lang
      wrapper.appendChild(label)
    }

    const btn = document.createElement('button')
    btn.className = 'code-copy-btn'
    btn.innerHTML = '📋'
    btn.title = '复制代码'
    btn.onclick = () => {
      const text = pre.textContent || ''
      navigator.clipboard.writeText(text).then(() => {
        btn.innerHTML = '✓'
        setTimeout(() => { btn.innerHTML = '📋' }, 2000)
      }).catch(() => {})
    }
    wrapper.appendChild(btn)
  })
}

onMounted(() => nextTick(injectCopyButtons))
onUpdated(() => nextTick(injectCopyButtons))
</script>

<style scoped>
.md-content { line-height: 1.7; word-break: break-word; }
.md-content :deep(p) { margin: 0.5em 0; }
.md-content :deep(strong) { font-weight: 700; }
.md-content :deep(em) { font-style: italic; }
.md-content :deep(ul), .md-content :deep(ol) { padding-left: 1.5em; margin: 0.5em 0; }
.md-content :deep(li) { margin: 0.25em 0; }
.md-content :deep(h1), .md-content :deep(h2), .md-content :deep(h3) { margin: 0.8em 0 0.4em; font-weight: 700; }
.md-content :deep(blockquote) { border-left: 3px solid #409eff; padding: 2px 10px; margin: 0.5em 0; color: #606266; }
.md-content :deep(code) { font-family: Consolas, Menlo, Monaco, monospace; font-size: 0.9em; background: #f0f2f5; padding: 1px 5px; border-radius: 3px; }
.md-content :deep(pre) { overflow-x: auto; }
.md-content :deep(pre code) { background: none; padding: 0; font-size: 0.88em; }
.code-block-wrapper { position: relative; margin: 0.8em 0; }
.code-block-wrapper :deep(pre) { background: #1e1e1e; border-radius: 8px; padding: 14px 16px; margin: 0; }
.code-lang-label { position: absolute; top: 4px; right: 50px; font-size: 11px; color: #999; background: rgba(255,255,255,0.08); padding: 1px 8px; border-radius: 3px; }
.code-copy-btn { position: absolute; top: 4px; right: 8px; font-size: 14px; background: rgba(255,255,255,0.1); border: none; color: #ccc; cursor: pointer; border-radius: 4px; padding: 2px 6px; line-height: 1; }
.code-copy-btn:hover { background: rgba(255,255,255,0.2); color: #fff; }
</style>
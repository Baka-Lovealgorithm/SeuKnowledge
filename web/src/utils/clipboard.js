/**
 * 复制文本到剪贴板（带降级兜底）。
 *
 * 为什么需要兜底：`navigator.clipboard` 只在**安全上下文**存在——HTTPS、localhost、
 * 127.0.0.1 三者之一。本项目生产部署是纯 HTTP（nginx `listen 80`，对外端口 8088），
 * 浏览器用 http://<IP>:8088 访问时 `navigator.clipboard` 为 undefined，
 * 直接调用只会抛 TypeError，界面只能笼统地提示"复制失败"。
 * 因此这里回退到 `document.execCommand('copy')`——虽已废弃，但在非安全上下文的
 * 桌面浏览器里仍广泛可用。
 *
 * 用法：
 *   const ok = await copyText('some text')
 *   if (ok) ElMessage.success('已复制') else ElMessage.warning('复制失败，请手动选择复制')
 *
 * @param {string} text 待复制文本
 * @returns {Promise<boolean>} 是否复制成功
 */
export async function copyText(text) {
  const value = text == null ? '' : String(text)
  if (!value) return false

  // 首选异步 Clipboard API：需要安全上下文 + 页面处于聚焦状态
  if (navigator.clipboard && window.isSecureContext) {
    try {
      await navigator.clipboard.writeText(value)
      return true
    } catch {
      // 权限被拒 / 文档失焦 / 被 iframe 嵌套未授 clipboard-write —— 落到下面的兜底
    }
  }

  // 注意：非安全上下文下这里是在「首个 await 之前」同步执行的，
  // 用户手势未被消耗，execCommand 才能生效。
  return legacyCopy(value)
}

/** 兜底实现：临时 textarea + execCommand('copy')，兼容非安全上下文 */
function legacyCopy(value) {
  const ta = document.createElement('textarea')
  ta.value = value
  // readonly 避免移动端弹出软键盘；移出视口避免页面跳动
  ta.setAttribute('readonly', '')
  ta.style.position = 'fixed'
  ta.style.top = '-9999px'
  ta.style.left = '-9999px'
  document.body.appendChild(ta)
  try {
    ta.focus()
    ta.select()
    ta.setSelectionRange(0, value.length)
    return document.execCommand('copy')
  } catch {
    return false
  } finally {
    document.body.removeChild(ta)
  }
}

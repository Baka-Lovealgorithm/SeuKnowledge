/**
 * 页面工作状态的本地持久化，用于「离开页面再回来仍停在上次的位置」。
 *
 * 存储键 `seuknowledge.viewState.<name>.<wsId>`，值为 JSON 对象，**按工作空间隔离**
 * （与 Chat.vue 的 `lastKbId` / `lastSessionId` 同思路，避免切空间后串到别的空间的选择）。
 *
 * 约定：这里存的是「上次的选择」，不是权威数据。调用方读到后**必须先做存在性校验**再采纳，
 * 不可用时走各自的降级链（如回落到第一个知识库）——数据可能已被删、被撤权或换库。
 */
const PREFIX = 'seuknowledge.viewState'

const storageKey = (name, wsId) => `${PREFIX}.${name}.${wsId || 'default'}`

/** 读取：任何异常（无值、非 JSON、被清空）都返回空对象，调用方无需判空 */
export function loadViewState(name, wsId) {
  try {
    const raw = localStorage.getItem(storageKey(name, wsId))
    if (!raw) return {}
    const parsed = JSON.parse(raw)
    return parsed && typeof parsed === 'object' ? parsed : {}
  } catch {
    return {}
  }
}

/** 合并写入：只覆盖传入的字段，其余保留（各调用点各记一部分也能拼成完整状态） */
export function saveViewState(name, wsId, patch) {
  try {
    const next = { ...loadViewState(name, wsId), ...patch }
    localStorage.setItem(storageKey(name, wsId), JSON.stringify(next))
  } catch {
    /* 隐私模式 / 配额满：静默降级，不影响页面本身的功能 */
  }
}

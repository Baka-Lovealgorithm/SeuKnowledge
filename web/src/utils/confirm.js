import { ElMessageBox } from 'element-plus'

/**
 * 二次确认：用户点「取消」时返回 false，不抛异常。
 *
 * ElMessageBox.confirm 在取消时会 reject，若调用方不接就变成 unhandled rejection
 * （Vue 会打 "[Vue warn] Unhandled error during execution of native event handler"，
 * 控制台留下 "Uncaught (in promise) cancel"），还可能污染前端错误监控。
 * 统一在这里把「取消」吞掉，调用方只关心 true/false：
 *
 *   if (!(await confirmAction('确定删除？'))) return
 *
 * @param {string} message 提示正文
 * @param {string} title   标题
 * @param {object} options 透传给 ElMessageBox.confirm 的选项（confirmButtonText / cancelButtonText / type 等）
 * @returns {Promise<boolean>} 确认 true / 取消 false
 */
export async function confirmAction(message, title = '提示', options = {}) {
  try {
    await ElMessageBox.confirm(message, title, { type: 'warning', ...options })
    return true
  } catch {
    return false
  }
}

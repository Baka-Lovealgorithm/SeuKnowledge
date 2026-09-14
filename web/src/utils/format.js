/**
 * 展示层格式化工具。
 * 后端时间字段序列化为 ISO 形式（2026-09-14T10:20:30），各页面统一在此转换。
 */

/**
 * ISO 时间 → 'YYYY-MM-DD HH:mm:ss'
 * 后端已按本地时区序列化（无 Z 后缀），故只做 T→空格替换与截断，不做时区换算。
 */
export function formatDateTime(t) {
  return t ? t.replace('T', ' ').slice(0, 19) : ''
}

/** 字节数 → '1.2 MB' / '340.0 KB' / '512 B'；空值返回 '-' */
export function formatSize(n) {
  if (!n) return '-'
  if (n > 1024 * 1024) return (n / 1024 / 1024).toFixed(1) + ' MB'
  if (n > 1024) return (n / 1024).toFixed(1) + ' KB'
  return n + ' B'
}

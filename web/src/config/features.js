/**
 * 前端功能开关。
 *
 * AI 抽取暂时仅在前端隐藏；抽取页面、接口与后端实现均保留。设置
 * VITE_ENABLE_AI_EXTRACTION=true 后重新启动 Vite，或重新构建前端镜像，
 * 即可恢复全部入口。
 */
export const features = Object.freeze({
  aiExtraction: import.meta.env.VITE_ENABLE_AI_EXTRACTION === 'true'
})

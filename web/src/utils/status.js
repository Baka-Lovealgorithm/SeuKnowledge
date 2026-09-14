/**
 * 领域状态映射：状态值 → [el-tag 类型, 展示文案]。
 *
 * 各域枚举不同，按域分表、不强行合并——同名 key 在不同域含义可能不同，
 * 合并成一张表看着省事，日后加枚举时反而容易串味。
 * 展示时配合 statusType() / statusText() 使用。
 */

/** 知识库状态 */
export const KB_STATUS = {
  DRAFT: ['info', '草稿'],
  BUILDING: ['warning', '构建中'],
  AVAILABLE: ['success', '可用'],
  ERROR: ['danger', '异常'],
  DISABLED: ['info', '停用']
}

/**
 * 知识条目状态：业务知识与问答对共用（草稿 → 通过/拒绝 的生命周期一致）。
 * 问答对额外可能出现 DISABLED，业务知识不会；同一张表覆盖两者，多出的键无副作用。
 */
export const ENTRY_STATUS = {
  DRAFT: ['warning', '草稿'],
  APPROVED: ['success', '已通过'],
  REJECTED: ['danger', '已拒绝'],
  DISABLED: ['info', '已禁用']
}

/** 取 el-tag 的类型，未收录的状态回落到 info */
export function statusType(map, s) {
  return (map[s] || ['info'])[0]
}

/** 取展示文案；未收录的状态原样回显，便于发现后端新增了枚举 */
export function statusText(map, s) {
  return (map[s] || [null, s])[1]
}

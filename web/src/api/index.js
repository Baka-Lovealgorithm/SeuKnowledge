import http from './http'

export const authApi = {
  login: (data) => http.post('/auth/login', data),
  me: () => http.get('/auth/me'),
  logout: () => http.post('/auth/logout')
}

export const kbApi = {
  list: () => http.get('/kb'),
  create: (data) => http.post('/kb', data),
  update: (id, data) => http.put(`/kb/${id}`, data),
  remove: (id) => http.delete(`/kb/${id}`),
  updateStatus: (id, status) => http.patch(`/kb/${id}/status`, null, { params: { status } }),
  accessList: (id) => http.get(`/kb/${id}/access`),
  accessGrant: (id, data) => http.post(`/kb/${id}/access`, data),
  accessRevoke: (id, accessId) => http.delete(`/kb/${id}/access/${accessId}`),
  setVisibility: (id, visibility) => http.patch(`/kb/${id}/access/visibility`, null, { params: { visibility } })
}

/** 知识库访问组管理（组级别授权基础；组内成员限当前工作空间成员） */
export const groupApi = {
  list: () => http.get('/workspace/groups'),
  create: (data) => http.post('/workspace/groups', data),
  rename: (id, data) => http.put(`/workspace/groups/${id}`, data),
  remove: (id) => http.delete(`/workspace/groups/${id}`),
  members: (id) => http.get(`/workspace/groups/${id}/members`),
  addMember: (id, data) => http.post(`/workspace/groups/${id}/members`, data),
  removeMember: (id, userId) => http.delete(`/workspace/groups/${id}/members/${userId}`)
}

export const docApi = {
  upload: (kbId, files, replace = false, reuseCache = false, curateGate = false, config = {}) => {
    const fd = new FormData()
    files.forEach((f) => fd.append('files', f))
    return http.post(`/kb/${kbId}/documents`, fd, { params: { replace, reuseCache, curateGate }, ...config })
  },
  list: (kbId) => http.get(`/kb/${kbId}/documents`),
  remove: (id) => http.delete(`/documents/${id}`),
  chunks: (id, filter) => http.get(`/documents/${id}/chunks`, { params: { filter } }),
  /** 分页查分块（大文档全量加载慢）：page 0 起，size 1~200，cleanStatus 空 = 不过滤 */
  chunkPage: (id, page = 0, size = 20, cleanStatus = '') =>
    http.get(`/documents/${id}/chunks/page`, { params: { page, size, cleanStatus: cleanStatus || undefined } }),
  /** 该文档全部待审核（SUSPECT）chunk id（一键通过：取 id 后走批量审核） */
  suspectIds: (id) => http.get(`/documents/${id}/chunks/suspect-ids`),
  retry: (id) => http.post(`/documents/${id}/retry`),
  /** 重命名（只改元数据与检索引用名，不重解析；扩展名必须保持不变） */
  rename: (id, fileName) => http.patch(`/documents/${id}/name`, { fileName }),
  /** 只重建向量、不重新解析（向量化失败/模型未配置后的原地救济） */
  reindex: (id) => http.post(`/documents/${id}/reindex`)
}

/** 文档初洗/精修：初洗（读队列/md/chunk + 写 md/接受）与精修（edit/drop/keep/unkeep/merge/confirm） */
export const curateApi = {
  info: (id) => http.get(`/documents/${id}/curate`),
  queue: (kbId) => http.get(`/kb/${kbId}/curate/queue`),
  getMd: (id) => http.get(`/documents/${id}/curate/md`),
  saveMd: (id, content) => http.post(`/documents/${id}/curate/md`, { content }),
  chunks: (id) => http.get(`/documents/${id}/curate/chunks`),
  /** 分页查分块：sort = suspect（待审核优先，默认）/ seq（自然顺序） */
  chunkPage: (id, page = 0, size = 20, sort = 'suspect', cleanStatus = '') =>
    http.get(`/documents/${id}/curate/chunks/page`, { params: { page, size, sort, cleanStatus: cleanStatus || undefined } }),
  /** 一键通过：保留该文档全部待审核（SUSPECT）分块（纯 DB 操作，确认前不触 ES） */
  batchKeep: (id) => http.post(`/documents/${id}/curate/chunks/batch-keep`),
  accept: (id) => http.post(`/documents/${id}/curate/accept`),
  confirm: (id) => http.post(`/documents/${id}/curate/confirm`),
  editChunk: (id, chunkId, data) => http.post(`/documents/${id}/curate/chunks/${chunkId}/edit`, data),
  dropChunk: (id, chunkId) => http.post(`/documents/${id}/curate/chunks/${chunkId}/drop`),
  keepChunk: (id, chunkId) => http.post(`/documents/${id}/curate/chunks/${chunkId}/keep`),
  unkeepChunk: (id, chunkId) => http.post(`/documents/${id}/curate/chunks/${chunkId}/unkeep`),
  mergeChunk: (id, data) => http.post(`/documents/${id}/curate/chunks/merge`, data),
  /** 精修阶段新增分块（锚点后插入，纯 DB，确认时统一向量化） */
  addChunk: (id, data) => http.post(`/documents/${id}/curate/chunks`, data)
}

/** 文档精修（普通文档分块复核/运维入口）：SUSPECT 队列 + 已向量化文档的编辑/删除/新增/合并（操作即时单块向量化） */
export const reviewApi = {
  suspectQueue: (kbId) => http.get(`/kb/${kbId}/chunks/suspect`),
  keep: (id) => http.post(`/chunks/${id}/review/keep`),
  drop: (id) => http.post(`/chunks/${id}/review/drop`),
  unkeep: (id) => http.post(`/chunks/${id}/review/unkeep`),
  edit: (id, data) => http.post(`/chunks/${id}/edit`, data),
  batch: (ids, action) => http.post('/chunks/review/batch', { ids, action }),
  /** 新增分块（插入锚点之后，后续 seq 让位，落库即向量化） */
  addChunk: (docId, data) => http.post(`/documents/${docId}/chunks`, data),
  /** 合并相邻分块（source 并入 target，target 重嵌、source 移出 ES） */
  mergeChunk: (docId, data) => http.post(`/documents/${docId}/chunks/merge`, data)
}

export const modelApi = {
  list: () => http.get('/models'),
  create: (data) => http.post('/models', data),
  update: (id, data) => http.put(`/models/${id}`, data),
  remove: (id) => http.delete(`/models/${id}`),
  test: (id) => http.post(`/models/${id}/test`)
}

export const chatApi = {
  createSession: (kbId) => http.post('/chat/session', { kbId }),
  listSessions: () => http.get('/chat/session'),
  messages: (id) => http.get(`/chat/session/${id}/messages`),
  ask: (id, question) => http.post(`/chat/session/${id}/ask`, { question }),
  rename: (id, title) => http.put(`/chat/session/${id}`, { title }),
  remove: (id) => http.delete(`/chat/session/${id}`),
  cancelAsk: (id) => http.post(`/chat/session/${id}/ask/cancel`),
  // 评价答案：rating = UP / DOWN / NONE(撤销)；reason 仅点踩有效，可省略（跳过）
  rate: (messageId, rating, reason, note) =>
    http.post(`/chat/message/${messageId}/feedback`, { rating, reason: reason || null, note: note || null })
}

export const statsApi = {
  overview: (days) => http.get('/stats/qa/overview', { params: { days } }),
  dislikes: (days, page, size) => http.get('/stats/qa/dislikes', { params: { days, page, size } })
}

export const agentApi = {
  get: (kbId) => http.get(`/kb/${kbId}/agent`),
  update: (kbId, data) => http.put(`/kb/${kbId}/agent`, data)
}

export const bkApi = {
  list: (kbId, status) => http.get(`/kb/${kbId}/business-knowledge`, { params: { status } }),
  create: (kbId, data) => http.post(`/kb/${kbId}/business-knowledge`, data),
  update: (id, data) => http.put(`/business-knowledge/${id}`, data),
  remove: (id) => http.delete(`/business-knowledge/${id}`),
  approve: (id) => http.post(`/business-knowledge/${id}/approve`),
  reject: (id) => http.post(`/business-knowledge/${id}/reject`),
  merge: (id, targetId) => http.post(`/business-knowledge/${id}/merge`, { targetId }),
  versions: (id) => http.get(`/business-knowledge/${id}/versions`),
  rollback: (id, version) => http.post(`/business-knowledge/${id}/versions/${version}/rollback`)
}

export const qaApi = {
  list: (kbId, status) => http.get(`/kb/${kbId}/qa-pairs`, { params: { status } }),
  create: (kbId, data) => http.post(`/kb/${kbId}/qa-pairs`, data),
  update: (id, data) => http.put(`/qa-pairs/${id}`, data),
  remove: (id) => http.delete(`/qa-pairs/${id}`),
  approve: (id) => http.post(`/qa-pairs/${id}/approve`),
  reject: (id) => http.post(`/qa-pairs/${id}/reject`),
  disable: (id) => http.post(`/qa-pairs/${id}/disable`),
  enable: (id) => http.post(`/qa-pairs/${id}/enable`),
  normalize: (id) => http.post(`/qa-pairs/${id}/normalize`),
  versions: (id) => http.get(`/qa-pairs/${id}/versions`),
  rollback: (id, version) => http.post(`/qa-pairs/${id}/versions/${version}/rollback`)
}

export const extractApi = {
  create: (data) => http.post('/extract/tasks', data),
  list: () => http.get('/extract/tasks'),
  get: (id) => http.get(`/extract/tasks/${id}`),
  results: (id) => http.get(`/extract/tasks/${id}/results`),
  retry: (id) => http.post(`/extract/tasks/${id}/retry`)
}

export const workspaceApi = {
  info: () => http.get('/workspace'),
  create: (data) => http.post('/workspace', data),
  rename: (data) => http.put('/workspace', data),
  deleteWorkspace: () => http.delete('/workspace'),
  members: () => http.get('/workspace/members'),
  createMember: (data) => http.post('/workspace/members', data),
  inviteMember: (data) => http.post('/workspace/members/invite', data),
  updateRole: (id, role) => http.put(`/workspace/members/${id}/role`, { role }),
  removeMember: (id) => http.delete(`/workspace/members/${id}`),
  transferOwnership: (memberId) => http.post('/workspace/owner/transfer', { memberId })
}

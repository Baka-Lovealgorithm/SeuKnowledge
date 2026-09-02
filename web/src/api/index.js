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

export const docApi = {
  upload: (kbId, files, replace = false, reuseCache = false, curateGate = false) => {
    const fd = new FormData()
    files.forEach((f) => fd.append('files', f))
    return http.post(`/kb/${kbId}/documents`, fd, { params: { replace, reuseCache, curateGate } })
  },
  list: (kbId) => http.get(`/kb/${kbId}/documents`),
  remove: (id) => http.delete(`/documents/${id}`),
  chunks: (id, filter) => http.get(`/documents/${id}/chunks`, { params: { filter } }),
  retry: (id) => http.post(`/documents/${id}/retry`)
}

/** 文档初洗/精修（原"文档策展"）：初洗（读队列/md/chunk + 写 md/接受）与精修（edit/drop/keep/unkeep/merge/confirm） */
export const curateApi = {
  info: (id) => http.get(`/documents/${id}/curate`),
  queue: (kbId) => http.get(`/kb/${kbId}/curate/queue`),
  getMd: (id) => http.get(`/documents/${id}/curate/md`),
  saveMd: (id, content) => http.post(`/documents/${id}/curate/md`, { content }),
  chunks: (id) => http.get(`/documents/${id}/curate/chunks`),
  accept: (id) => http.post(`/documents/${id}/curate/accept`),
  confirm: (id) => http.post(`/documents/${id}/curate/confirm`),
  editChunk: (id, chunkId, data) => http.post(`/documents/${id}/curate/chunks/${chunkId}/edit`, data),
  dropChunk: (id, chunkId) => http.post(`/documents/${id}/curate/chunks/${chunkId}/drop`),
  keepChunk: (id, chunkId) => http.post(`/documents/${id}/curate/chunks/${chunkId}/keep`),
  unkeepChunk: (id, chunkId) => http.post(`/documents/${id}/curate/chunks/${chunkId}/unkeep`),
  mergeChunk: (id, data) => http.post(`/documents/${id}/curate/chunks/merge`, data)
}

/** 文档精修（原"清洗复核"）：待审核（SUSPECT）块 保留/编辑/删除/回退待审核/批量 */
export const reviewApi = {
  suspectQueue: (kbId) => http.get(`/kb/${kbId}/chunks/suspect`),
  keep: (id) => http.post(`/chunks/${id}/review/keep`),
  drop: (id) => http.post(`/chunks/${id}/review/drop`),
  unkeep: (id) => http.post(`/chunks/${id}/review/unkeep`),
  edit: (id, data) => http.post(`/chunks/${id}/edit`, data),
  batch: (ids, action) => http.post('/chunks/review/batch', { ids, action })
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
  cancelAsk: (id) => http.post(`/chat/session/${id}/ask/cancel`)
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

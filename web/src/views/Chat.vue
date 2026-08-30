<template>
  <el-container class="chat-page">
    <el-aside v-show="!sidebarCollapsed" width="280px" class="chat-aside">
      <div class="aside-top">
        <el-select v-model="kbId" placeholder="选择知识库" style="width: 100%" @change="onKbChange">
          <el-option v-for="kb in kbs" :key="kb.id" :label="kb.name" :value="kb.id" />
        </el-select>
        <el-button type="primary" style="width: 100%; margin-top: 10px"
                  :disabled="!kbId || isSendingCurrent" @click="newChat">
          ＋ 新建对话
        </el-button>
      </div>
      <div v-if="sessions.length" class="manage-bar">
        <el-button text size="small" @click="toggleManage">
          {{ manageMode ? '完成' : '管理' }}
        </el-button>
        <el-button v-if="manageMode" type="danger" size="small"
                  :disabled="selectedIds.size === 0" @click="deleteSelected">
          删除所选({{ selectedIds.size }})
        </el-button>
      </div>
      <div class="session-list">
        <div
          v-for="s in sessions" :key="s.id"
          class="session-item" :class="{ active: s.id === sessionId }"
          @click="manageMode ? toggleSelect(s) : selectSession(s)"
        >
          <el-checkbox v-if="manageMode" :model-value="selectedIds.has(s.id)" class="session-check" @click.stop @change="toggleSelect(s)" />
          <div class="session-title">
            <span class="session-name">{{ s.title && s.title !== '新会话' ? s.title : '会话 #' + s.id }}</span>
            <span class="session-ops" @click.stop>
              <el-icon class="op" @click="openRename(s)"><Edit /></el-icon>
              <el-icon class="op danger" @click="removeSession(s)"><Delete /></el-icon>
            </span>
          </div>
        </div>
      </div>
    </el-aside>
    <el-container>
      <el-header class="chat-header-bar">
        <el-button class="toggle-sidebar-btn" text @click="toggleSidebar" :title="sidebarCollapsed ? '展开对话列表' : '收起对话列表'">
          <el-icon :size="18"><Expand v-if="sidebarCollapsed" /><Fold v-else /></el-icon>
        </el-button>
        <span class="chat-title">{{ currentSessionTitle }}</span>
      </el-header>
      <el-main class="chat-main" ref="mainRef">
        <div v-if="!sessionId" class="empty-tip">选择一个知识库并新建会话，开始提问</div>
        <div v-for="(m, i) in messages" :key="i" class="msg-row" :class="m.role.toLowerCase()">
          <div class="msg-bubble">
            <div class="msg-role">{{ m.role === 'USER' ? '我' : '助手' }}</div>
            <div class="msg-content"><MdContent :content="m.content" /></div>
            <el-tag v-if="m.role === 'ASSISTANT' && m.interrupted" type="info" size="small" style="margin-top:6px">已停止</el-tag>
            <div v-if="m.role === 'ASSISTANT' && refsList[i]" class="msg-refs">
              <div v-for="(r, j) in refsList[i]" :key="j" class="ref-item">
                <div class="ref-meta">
                  <span class="ref-index">[{{ j + 1 }}]</span>
                  <el-tag size="small" :type="refTypeTag(r.sourceType)">{{ refTypeLabel(r.sourceType) }}</el-tag>
                  {{ refTitle(r) }}{{ r.page ? ` · 第 ${r.page} 页` : '' }} <span class="ref-chunk">#{{ r.chunkId }}</span>
                  <el-button v-if="canExpandRef(r)" link type="primary" size="small" class="ref-expand-btn"
                             @click="openRefDetail(r)">{{ refDetailLoading.has(r.chunkId) ? '加载中…' : '查看全文' }}</el-button>
                </div>
                <div v-if="r.content" class="ref-snippet"><MdContent :content="r.content" /></div>
              </div>
            </div>
          </div>
        </div>
      </el-main>
      <el-footer class="chat-footer">
        <div v-if="currentStage" class="stage-bar">
          <div class="stage-steps">
            <div
              v-for="(s, i) in STAGES" :key="s.id"
              class="stage-item"
              :class="{ done: i < stageIdx || (stageIdx === i && stageDone), active: i === stageIdx && !stageDone }"
            >
              <span class="stage-dot">{{ i < stageIdx || (stageIdx === i && stageDone) ? '✓' : i + 1 }}</span>
              <span class="stage-label">{{ s.label }}</span>
            </div>
          </div>
          <div class="stage-detail">
            <span v-if="!stageDone" class="stage-spinner"></span>
            <span v-else class="stage-ok">✓</span>
            {{ stageText }}
          </div>
        </div>
        <el-input
          v-model="input" placeholder="输入问题，回车发送"
          :disabled="(!sessionId && !draft) || isSendingCurrent"
          @keyup.enter="isSendingCurrent ? null : send()"
        >
          <template #append>
            <el-button v-if="isSendingCurrent" type="danger" @click="stopCurrent">⏹ 停止</el-button>
            <el-button v-else @click="send">发送</el-button>
          </template>
        </el-input>
      </el-footer>
    </el-container>
  </el-container>

  <el-dialog v-model="renameVisible" title="重命名会话" width="420px">
    <el-input v-model="renameTitle" maxlength="255" placeholder="输入会话标题" @keyup.enter="confirmRename" />
    <template #footer>
      <el-button @click="renameVisible = false">取消</el-button>
      <el-button type="primary" @click="confirmRename">保存</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="refDetailVisible" :title="refDetailTitle" width="720px" class="ref-detail-dialog">
    <div class="ref-detail-body"><MdContent :content="refDetailContent" /></div>
  </el-dialog>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Edit, Delete, Expand, Fold } from '@element-plus/icons-vue'
import { chatApi, docApi, kbApi } from '../api'
import { useAuthStore } from '../stores/auth'
import MdContent from '../components/MdContent.vue'

const auth = useAuthStore()

const kbs = ref([])
const kbId = ref(null)
const sessions = ref([])
const sessionId = ref(null)
const messages = ref([])
const refsList = ref([])
const input = ref('')
const draft = ref(false)
const stageStore = reactive(new Map())
const sendingSessions = reactive(new Set())
const aborters = reactive(new Map())
const stoppedSessions = reactive(new Set())
const manageMode = ref(false)
const selectedIds = reactive(new Set())
const mainRef = ref(null)
const renameVisible = ref(false)
const renameTitle = ref('')
const renamingSession = ref(null)
const sidebarCollapsed = ref(false)

function toggleSidebar() {
  sidebarCollapsed.value = !sidebarCollapsed.value
}

const currentSessionTitle = computed(() => {
  if (draft.value || !sessionId.value) return '新对话'
  const s = sessions.value.find((x) => x.id === sessionId.value)
  if (s && s.title && s.title !== '新会话') return s.title
  return '会话 #' + sessionId.value
})

// ===== 问答阶段状态（SSE stage 事件 → 流程条）=====
const STAGES = [
  { id: 'INTENT_ROUTE', label: '意图分析' },
  { id: 'QUERY_REWRITE', label: '问题改写' },
  { id: 'KNOWLEDGE_RECALL', label: '证据召回' },
  { id: 'RERANK', label: '证据重排' },
  { id: 'ANSWER_COMPOSE', label: '答案生成' },
  { id: 'ANSWER_VERIFY', label: '自检' },
  { id: 'DONE', label: '完成' }
]
const STAGE_INDEX = Object.fromEntries(STAGES.map((s, i) => [s.id, i]))
const currentStage = computed(() => sessionId.value ? (stageStore.get(sessionId.value) || null) : null)
const stageDone = computed(() => currentStage.value?.done ?? false)
const isSendingCurrent = computed(() => sessionId.value ? sendingSessions.has(sessionId.value) : false)
const stageIdx = computed(() => {
  if (!currentStage.value) return -1
  return STAGE_INDEX[currentStage.value.id] ?? -1
})
const stageText = computed(() => (currentStage.value ? currentStage.value.text : ''))

function resetStage() {
  if (sessionId.value) stageStore.delete(sessionId.value)
}

function fmt(t) { return t ? t.replace('T', ' ').slice(5, 16) : '' }

/** 错误消息可读化：超时/网络异常等后端消息原样展示，空则兜底 */
function friendlyError(raw) {
  const msg = (raw || '').trim()
  if (!msg) return '问答失败，请检查模型配置'
  return msg
}

// ===== 上次使用状态恢复（知识库 + 会话，按工作空间隔离的 localStorage 持久化）=====
const lastKbKey = () => `seuknowledge.lastKbId.${auth.workspaceId || 'default'}`
const lastSessionKey = () => `seuknowledge.lastSessionId.${auth.workspaceId || 'default'}`

async function loadKbs() {
  kbs.value = await kbApi.list()
  await restoreLastState()
}

/**
 * 恢复上次使用的知识库与会话（刷新/离开页面再进入时调用）。
 * 降级链：上次知识库不可用 → 第一个知识库；上次会话不存在 → 该库第一个会话；无会话 → 新建。
 */
async function restoreLastState() {
  if (!kbs.value.length) return
  const lastKbId = Number(localStorage.getItem(lastKbKey()) || '')
  const lastSessionId = Number(localStorage.getItem(lastSessionKey()) || '')
  let target = kbs.value.find((k) => k.id === lastKbId)
  if (!target) target = kbs.value[0] // 降级：上次知识库不存在/已归档 → 第一个
  kbId.value = target.id
  await onKbChange()
  // 降级链：上次会话存在则优先选中
  if (lastSessionId) {
    const s = sessions.value.find((x) => x.id === lastSessionId)
    if (s) await selectSession(s)
  }
}

async function onKbChange() {
  draft.value = false
  manageMode.value = false
  selectedIds.clear()
  localStorage.setItem(lastKbKey(), String(kbId.value))
  localStorage.removeItem(lastSessionKey())
  sessions.value = await chatApi.listSessions()
  const matched = sessions.value.filter((s) => s.kbId === kbId.value)
  sessions.value = matched
  sessionId.value = null
  messages.value = []
  refsList.value = []
  resetStage()
  if (matched.length === 0) {
    draft.value = true
  } else {
    selectSession(matched[0])
  }
}

function newChat() {
  if (isSendingCurrent.value) return
  draft.value = true
  sessionId.value = null
  messages.value = []
  refsList.value = []
  resetStage()
  localStorage.removeItem(lastSessionKey())
  scrollBottom()
}

async function selectSession(s) {
  if (manageMode.value) return
  draft.value = false
  sessionId.value = s.id
  localStorage.setItem(lastKbKey(), String(kbId.value))
  localStorage.setItem(lastSessionKey(), String(s.id))
  if (sendingSessions.has(s.id)) {
    // 正在处理中：保留当前视图，不重载消息
    return
  }
  const msgs = await chatApi.messages(s.id)
  messages.value = msgs
  refsList.value = msgs.map((m) => parseRefs(m.refs))
  scrollBottom()
}

function parseRefs(json) {
  if (!json) return []
  try {
    const arr = JSON.parse(json)
    return Array.isArray(arr) ? arr : []
  } catch { return [] }
}

// 多源引用展示：文档分块 / 业务知识 / 问答对
function refTypeLabel(t) {
  if (t === 'BUSINESS') return '业务知识'
  if (t === 'QA') return '问答对'
  return '文档'
}
function refTypeTag(t) {
  if (t === 'BUSINESS') return 'warning'
  if (t === 'QA') return 'success'
  return 'primary'
}
function refTitle(r) {
  if (r.sourceType && r.sourceType !== 'CHUNK' && r.title) return r.title
  return r.docName || ''
}

// ===== 引用片段展开详情（仅 CHUNK 来源有 docId；复用 /documents/{id}/chunks 按 chunkId 取全文）=====
const refDetailVisible = ref(false)
const refDetailTitle = ref('')
const refDetailContent = ref('')
const refDetailLoading = reactive(new Map())
const chunkCache = reactive(new Map()) // docId -> chunks 列表

function canExpandRef(r) {
  return (!r.sourceType || r.sourceType === 'CHUNK') && r.docId
}

async function openRefDetail(r) {
  if (refDetailLoading.has(r.chunkId)) return
  refDetailVisible.value = true
  refDetailTitle.value = (r.docName || '') + (r.page ? ` · 第 ${r.page} 页` : '')
  refDetailContent.value = ''
  refDetailLoading.set(r.chunkId, true)
  try {
    let chunks = chunkCache.get(r.docId)
    if (!chunks) {
      chunks = await docApi.chunks(r.docId)
      chunkCache.set(r.docId, chunks)
    }
    const hit = chunks.find((c) => c.id === r.chunkId)
    refDetailContent.value = hit ? hit.content : '(未找到对应分块，可能已被重新解析)'
  } catch (e) {
    refDetailContent.value = '加载失败：' + ((e && e.message) || '请稍后重试')
  } finally {
    refDetailLoading.delete(r.chunkId)
  }
}

async function send() {
  const q = input.value.trim()
  if (!q || isSendingCurrent.value) return
  input.value = ''
  // 惰性创建：首条消息才落库
  let sid = sessionId.value
  if (draft.value) {
    if (!kbId.value) return
    const s = await chatApi.createSession(kbId.value)
    sessions.value.unshift(s)
    sid = s.id
    sessionId.value = s.id
    draft.value = false
    localStorage.setItem(lastKbKey(), String(kbId.value))
    localStorage.setItem(lastSessionKey(), String(s.id))
  }
  sendingSessions.add(sid)
  const sentSessionId = sid
  const cur = () => sessionId.value === sentSessionId
  resetStage()
  messages.value.push({ role: 'USER', content: q })
  refsList.value.push([])
  const idx = messages.value.length
  messages.value.push({ role: 'ASSISTANT', content: '' })
  refsList.value.push([])
  scrollBottom()
  const ctrl = new AbortController()
  aborters.set(sentSessionId, ctrl)
  try {
    const token = localStorage.getItem('token')
    const resp = await fetch(`/api/chat/session/${sentSessionId}/ask/stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({ question: q }),
      signal: ctrl.signal
    })
    if (!resp.ok || !resp.body) {
      let msg = '请求失败（HTTP ' + resp.status + '）'
      try { const body = await resp.json(); if (body && body.message) msg = body.message } catch {}
      throw new Error(msg)
    }
    const reader = resp.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let receivedDone = false
    let receivedStop = false
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let nl
      while ((nl = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, nl).trim()
        buffer = buffer.slice(nl + 1)
        if (!line.startsWith('data:')) continue
        const data = JSON.parse(line.slice(5).trim())
        if (data.type === 'stage') {
          stageStore.set(sentSessionId, { id: data.stage, text: data.content || '', done: false })
        } else if (data.type === 'delta') {
          if (cur()) { messages.value[idx].content += data.content; scrollBottom() }
        } else if (data.type === 'refs') {
          if (cur()) { refsList.value[idx] = parseRefs(data.content) }
        } else if (data.type === 'done') {
          receivedDone = true
          stageStore.set(sentSessionId, { id: 'DONE', text: '回答完成', done: true })
        } else if (data.type === 'stopped') {
          receivedStop = true
          stageStore.set(sentSessionId, { id: 'DONE', text: '已停止', done: true })
        } else if (data.type === 'error') {
          throw new Error(data.content || '问答失败')
        }
      }
    }
    if (!receivedDone && !receivedStop) throw new Error('连接中断，请稍后重试')
    stageStore.set(sentSessionId, { id: 'DONE', text: receivedStop ? '已停止' : '回答完成', done: true })
    setTimeout(async () => {
      stageStore.delete(sentSessionId)
      if (cur()) await refreshSessions()
    }, 1600)
    const s = sessions.value.find((x) => x.id === sentSessionId)
    if (s) s.messageCount += 2
  } catch (e) {
    stageStore.delete(sentSessionId)
    const stopped = stoppedSessions.has(sentSessionId) || (e && e.name === 'AbortError')
    if (stopped) {
      stoppedSessions.delete(sentSessionId)
      if (cur() && messages.value[idx]) {
        messages.value[idx].interrupted = true
      }
      stageStore.set(sentSessionId, { id: 'DONE', text: '已停止', done: true })
      setTimeout(async () => {
        stageStore.delete(sentSessionId)
        if (cur()) await refreshSessions()
      }, 1600)
    } else {
      const msg = friendlyError((e && e.message) || '')
      if (cur()) {
        if (!messages.value[idx].content) messages.value[idx].content = msg
        ElMessage.error(msg)
      }
    }
  } finally {
    aborters.delete(sentSessionId)
    sendingSessions.delete(sentSessionId)
    scrollBottom()
  }
}

async function stopCurrent() {
  const sid = sessionId.value
  if (!sid) return
  stoppedSessions.add(sid)
  aborters.get(sid)?.abort()
  chatApi.cancelAsk(sid).catch(() => {})
}

/** 刷新会话列表（服务端按最后对话时间倒序），用于展示自动生成的标题与最新排序 */
async function refreshSessions() {
  try {
    const all = await chatApi.listSessions()
    sessions.value = all.filter((s) => s.kbId === kbId.value)
  } catch {
    // 刷新失败不影响当前对话
  }
}

function scrollBottom() {
  nextTick(() => {
    const el = mainRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

function openRename(s) {
  renamingSession.value = s
  renameTitle.value = s.title && s.title !== '新会话' ? s.title : ''
  renameVisible.value = true
}

async function confirmRename() {
  if (!renameTitle.value.trim()) { ElMessage.warning('请输入标题'); return }
  await chatApi.rename(renamingSession.value.id, renameTitle.value.trim())
  renamingSession.value.title = renameTitle.value.trim()
  ElMessage.success('已重命名')
  renameVisible.value = false
}

async function removeSession(s) {
  if (manageMode.value) return
  await ElMessageBox.confirm(`确定删除会话 #${s.id}？会话中的消息将一并删除。`, '提示', { type: 'warning' })
  await chatApi.remove(s.id)
  sessions.value = sessions.value.filter((x) => x.id !== s.id)
  if (s.id === sessionId.value) {
    localStorage.removeItem(lastSessionKey())
    sessionId.value = null
    messages.value = []
    refsList.value = []
    if (sessions.value.length) {
      await selectSession(sessions.value[0])
    } else {
      draft.value = true
    }
  }
  ElMessage.success('已删除')
}

function toggleManage() {
  manageMode.value = !manageMode.value
  selectedIds.clear()
}

function toggleSelect(s) {
  if (selectedIds.has(s.id)) {
    selectedIds.delete(s.id)
  } else {
    selectedIds.add(s.id)
  }
}

async function deleteSelected() {
  if (selectedIds.size === 0) return
  await ElMessageBox.confirm(`确定删除选中的 ${selectedIds.size} 个会话？`, '提示', { type: 'warning' })
  for (const id of selectedIds) {
    await chatApi.remove(id).catch(() => {})
  }
  selectedIds.clear()
  manageMode.value = false
  const all = await chatApi.listSessions()
  sessions.value = all.filter((s) => s.kbId === kbId.value)
  if (sessionId.value && !sessions.value.find((s) => s.id === sessionId.value)) {
    localStorage.removeItem(lastSessionKey())
    sessionId.value = null
    messages.value = []
    refsList.value = []
    if (sessions.value.length) {
      await selectSession(sessions.value[0])
    } else {
      draft.value = true
    }
  }
}

onMounted(loadKbs)
</script>

<style scoped>
.chat-page { height: calc(100vh - 110px); border: 1px solid #e6e6e6; background: #fff; }
.chat-aside { border-right: 1px solid #e6e6e6; display: flex; flex-direction: column; }
.chat-header-bar { display: flex; align-items: center; gap: 8px; background: #fff; border-bottom: 1px solid #e6e6e6; padding: 0 12px; height: 48px; }
.toggle-sidebar-btn { padding: 4px; color: #606266; }
.toggle-sidebar-btn:hover { color: #409eff; }
.chat-title { font-weight: 600; font-size: 14px; color: #303133; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.aside-top { padding: 12px; border-bottom: 1px solid #f0f0f0; }
.manage-bar { padding: 6px 14px; border-bottom: 1px solid #f0f0f0; display: flex; align-items: center; gap: 8px; }
.session-list { flex: 1; overflow: auto; }
.session-item { padding: 10px 14px; cursor: pointer; border-bottom: 1px solid #f5f5f5; display: flex; align-items: flex-start; gap: 8px; }
.session-item:hover { background: #f5f7fa; }
.session-item.active { background: #ecf5ff; }
.session-check { flex-shrink: 0; margin-top: 2px; }
.session-title { display: flex; align-items: center; justify-content: space-between; gap: 8px; font-weight: 600; font-size: 14px; }
.session-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.session-ops { display: inline-flex; gap: 6px; opacity: 0; transition: opacity 0.2s; flex-shrink: 0; }
.session-item:hover .session-ops { opacity: 1; }
.op { cursor: pointer; color: #909399; }
.op:hover { color: #409eff; }
.op.danger:hover { color: #f56c6c; }
.chat-main { overflow-y: auto; background: #f5f7fa; }
.empty-tip { text-align: center; color: #909399; margin-top: 80px; }
.msg-row { display: flex; margin-bottom: 16px; }
.msg-row.user { justify-content: flex-end; }
.msg-row.assistant { justify-content: flex-start; }
.msg-bubble { max-width: 72%; padding: 12px 16px; border-radius: 10px; background: #fff; box-shadow: 0 1px 3px rgba(0,0,0,0.08); }
.msg-row.user .msg-bubble { background: #409eff; color: #fff; }
.msg-role { font-size: 12px; color: #909399; margin-bottom: 6px; }
.msg-row.user .msg-role { color: #d9ecff; }
.msg-content { white-space: pre-wrap; word-break: break-word; }
.msg-refs { margin-top: 10px; border-top: 1px dashed #dcdfe6; padding-top: 8px; }
.msg-row.user .msg-refs { border-color: rgba(255,255,255,0.4); }
.ref-item { font-size: 12px; color: #606266; padding: 3px 0; }
.ref-meta { display: flex; align-items: center; gap: 4px; flex-wrap: wrap; }
.ref-expand-btn { margin-left: 2px; }
.ref-snippet { margin-top: 6px; background: #f5f7fa; border: 1px solid #ebeef5; border-radius: 6px; padding: 8px 10px; font-size: 12px; max-height: 180px; overflow: auto; }
.ref-detail-body { max-height: 60vh; overflow: auto; font-size: 13px; }
.ref-index { display: inline-block; min-width: 22px; font-weight: 700; color: #409eff; font-family: Consolas, Menlo, monospace; margin-right: 2px; }
.msg-row.user .ref-index { color: #fff; }
.ref-chunk { color: #409eff; }
.msg-row.user .ref-chunk { color: #fff; }
.chat-footer { padding: 12px; background: #fff; border-top: 1px solid #e6e6e6; }

/* ===== 问答阶段流程条 ===== */
.stage-bar { display: flex; flex-direction: column; gap: 6px; margin-bottom: 8px; padding: 8px 12px; background: #f5f7fa; border: 1px solid #e6e6e6; border-radius: 8px; }
.stage-steps { display: flex; flex-wrap: wrap; gap: 4px 14px; }
.stage-item { display: inline-flex; align-items: center; gap: 4px; font-size: 12px; color: #c0c4cc; transition: color 0.2s; }
.stage-item.done { color: #67c23a; }
.stage-item.active { color: #409eff; font-weight: 600; }
.stage-dot { width: 18px; height: 18px; border-radius: 50%; background: #e4e7ed; display: inline-flex; align-items: center; justify-content: center; font-size: 11px; color: #fff; flex-shrink: 0; }
.stage-item.done .stage-dot { background: #67c23a; }
.stage-item.active .stage-dot { background: #409eff; }
.stage-detail { display: flex; align-items: center; gap: 6px; font-size: 12px; color: #606266; }
.stage-ok { color: #67c23a; font-weight: 700; }
.stage-spinner { width: 10px; height: 10px; border: 2px solid #409eff; border-top-color: transparent; border-radius: 50%; animation: stage-spin 0.8s linear infinite; }
@keyframes stage-spin { to { transform: rotate(360deg); } }
</style>

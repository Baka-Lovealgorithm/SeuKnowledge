<template>
  <el-container class="chat-page">
    <el-aside width="280px" class="chat-aside">
      <div class="aside-top">
        <el-select v-model="kbId" placeholder="选择知识库" style="width: 100%" @change="onKbChange">
          <el-option v-for="kb in kbs" :key="kb.id" :label="kb.name" :value="kb.id" />
        </el-select>
      </div>
      <div class="session-list">
        <div
          v-for="s in sessions" :key="s.id"
          class="session-item" :class="{ active: s.id === sessionId }"
          @click="selectSession(s)"
        >
          <div class="session-title">
            {{ s.title && s.title !== '新会话' ? s.title : '会话 #' + s.id }}
            <span class="session-ops" @click.stop>
              <el-icon class="op" @click="openRename(s)"><Edit /></el-icon>
              <el-icon class="op danger" @click="removeSession(s)"><Delete /></el-icon>
            </span>
          </div>
          <div class="session-meta">{{ s.messageCount }} 条消息 · {{ fmt(s.lastMessageAt || s.createdAt) }}</div>
        </div>
      </div>
    </el-aside>
    <el-container>
      <el-main class="chat-main" ref="mainRef">
        <div v-if="!sessionId" class="empty-tip">选择一个知识库并新建会话，开始提问</div>
        <div v-for="(m, i) in messages" :key="i" class="msg-row" :class="m.role.toLowerCase()">
          <div class="msg-bubble">
            <div class="msg-role">{{ m.role === 'USER' ? '我' : '助手' }}</div>
            <div class="msg-content">{{ m.content }}</div>
            <div v-if="m.role === 'ASSISTANT' && refsList[i]" class="msg-refs">
              <div v-for="(r, j) in refsList[i]" :key="j" class="ref-item">
                <span class="ref-index">[{{ j + 1 }}]</span>
                <el-tag size="small" :type="refTypeTag(r.sourceType)">{{ refTypeLabel(r.sourceType) }}</el-tag>
                {{ refTitle(r) }}{{ r.page ? ` · 第 ${r.page} 页` : '' }} <span class="ref-chunk">#{{ r.chunkId }}</span>
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
          :disabled="!sessionId || sending"
          @keyup.enter="send"
        >
          <template #append>
            <el-button :loading="sending" @click="send">发送</el-button>
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
</template>

<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Edit, Delete } from '@element-plus/icons-vue'
import { chatApi, kbApi } from '../api'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()

const kbs = ref([])
const kbId = ref(null)
const sessions = ref([])
const sessionId = ref(null)
const messages = ref([])
const refsList = ref([])
const input = ref('')
const sending = ref(false)
const mainRef = ref(null)
const renameVisible = ref(false)
const renameTitle = ref('')
const renamingSession = ref(null)

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
const currentStage = ref(null) // { id, text }
const stageDone = ref(false)
const stageIdx = computed(() => {
  if (!currentStage.value) return -1
  return STAGE_INDEX[currentStage.value.id] ?? -1
})
const stageText = computed(() => (currentStage.value ? currentStage.value.text : ''))

function resetStage() {
  currentStage.value = null
  stageDone.value = false
}

function fmt(t) { return t ? t.replace('T', ' ').slice(5, 16) : '' }

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
  // 手动切换知识库：记录新知识库、作废旧会话记录
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
    await createSession()
  } else {
    selectSession(matched[0])
  }
}

async function createSession() {
  if (!kbId.value) return
  const s = await chatApi.createSession(kbId.value)
  sessions.value.unshift(s)
  selectSession(s)
}

async function selectSession(s) {
  sessionId.value = s.id
  // 记录上次使用的知识库与会话（按工作空间）
  localStorage.setItem(lastKbKey(), String(kbId.value))
  localStorage.setItem(lastSessionKey(), String(s.id))
  resetStage()
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

async function send() {
  const q = input.value.trim()
  if (!q || !sessionId.value || sending.value) return
  input.value = ''
  sending.value = true
  const sentSessionId = sessionId.value
  resetStage()
  messages.value.push({ role: 'USER', content: q })
  refsList.value.push([])
  // 预置 assistant 气泡，流式填充
  const idx = messages.value.length
  messages.value.push({ role: 'ASSISTANT', content: '' })
  refsList.value.push([])
  scrollBottom()
  try {
    const token = localStorage.getItem('token')
    const resp = await fetch(`/api/chat/session/${sessionId.value}/ask/stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({ question: q })
    })
    if (!resp.ok || !resp.body) throw new Error('请求失败')
    const reader = resp.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
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
          // 问答阶段状态：更新流程条
          currentStage.value = { id: data.stage, text: data.content || '' }
          stageDone.value = false
        } else if (data.type === 'delta') {
          messages.value[idx].content += data.content
          scrollBottom()
        } else if (data.type === 'refs') {
          refsList.value[idx] = parseRefs(data.content)
        } else if (data.type === 'error') {
          throw new Error(data.content || '问答失败')
        }
      }
    }
    // 流结束：显示完成态，短暂保留后消失；同时刷新会话列表（标题已由后端生成、按最后对话时间重排）
    currentStage.value = { id: 'DONE', text: '回答完成' }
    stageDone.value = true
    setTimeout(async () => {
      resetStage()
      if (sessionId.value === sentSessionId) await refreshSessions()
    }, 1600)
    const s = sessions.value.find((x) => x.id === sessionId.value)
    if (s) s.messageCount += 2
  } catch (e) {
    if (!messages.value[idx].content) messages.value[idx].content = '问答失败，请检查模型配置'
    ElMessage.error('问答失败，请检查模型配置')
  } finally {
    sending.value = false
    scrollBottom()
  }
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
  await ElMessageBox.confirm(`确定删除会话 #${s.id}？会话中的消息将一并删除。`, '提示', { type: 'warning' })
  await chatApi.remove(s.id)
  sessions.value = sessions.value.filter((x) => x.id !== s.id)
  if (s.id === sessionId.value) {
    localStorage.removeItem(lastSessionKey())
    sessionId.value = null
    messages.value = []
    refsList.value = []
    // 删除当前会话后：仍有会话则自动选中第一个，否则保持空
    if (sessions.value.length) {
      await selectSession(sessions.value[0])
    }
  }
  ElMessage.success('已删除')
}

onMounted(loadKbs)
</script>

<style scoped>
.chat-page { height: calc(100vh - 110px); border: 1px solid #e6e6e6; background: #fff; }
.chat-aside { border-right: 1px solid #e6e6e6; display: flex; flex-direction: column; }
.aside-top { padding: 12px; border-bottom: 1px solid #f0f0f0; }
.session-list { flex: 1; overflow: auto; }
.session-item { padding: 10px 14px; cursor: pointer; border-bottom: 1px solid #f5f5f5; }
.session-item:hover { background: #f5f7fa; }
.session-item.active { background: #ecf5ff; }
.session-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; font-size: 14px; }
.session-ops { display: inline-flex; gap: 6px; opacity: 0; transition: opacity 0.2s; }
.session-item:hover .session-ops { opacity: 1; }
.op { cursor: pointer; color: #909399; }
.op:hover { color: #409eff; }
.op.danger:hover { color: #f56c6c; }
.session-meta { color: #909399; font-size: 12px; margin-top: 4px; }
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

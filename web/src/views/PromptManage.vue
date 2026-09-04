<template>
  <div class="prompt-page">
    <el-tabs v-model="activeTab">
      <!-- ===== 区块一：节点提示词模板 ===== -->
      <el-tab-pane label="节点提示词" name="templates">
        <div class="toolbar">
          <span class="muted">问答链路 / 抽取 / 记忆等节点的 AI 提示词模板（平台级，全部知识库生效）。编辑保存后立即生效，无需重启。</span>
        </div>
        <el-table :data="templates" v-loading="loading" border>
          <el-table-column prop="name" label="名称" width="140" />
          <el-table-column prop="key" label="模板 key" width="200">
            <template #default="{ row }"><code>{{ row.key }}</code></template>
          </el-table-column>
          <el-table-column prop="desc" label="用途" min-width="220" show-overflow-tooltip />
          <el-table-column label="版本" width="70">
            <template #default="{ row }">{{ row.version }}</template>
          </el-table-column>
          <el-table-column label="更新时间" width="170">
            <template #default="{ row }">{{ fmt(row.updatedAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="160" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button link type="warning" @click="resetDefault(row)">重置默认</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ===== 区块二：Agent 配置（原知识库 → Agent 配置页并入） ===== -->
      <el-tab-pane label="Agent 配置" name="agent">
        <div class="agent-section">
          <el-form :model="agentForm" label-width="130px" class="agent-form" v-loading="agentLoading" :disabled="!selectedKb">
            <el-form-item label="选择知识库" required>
              <el-select v-model="selectedKb" filterable placeholder="选择知识库后配置其 Agent" style="width: 360px" @change="loadAgent">
                <el-option v-for="kb in kbs" :key="kb.id" :label="kb.name" :value="kb.id" />
              </el-select>
              <span class="muted" style="margin-left: 8px">每个知识库绑定一个 Agent，配置即刻生效于该知识库的后续问答</span>
            </el-form-item>

            <el-divider content-position="left">基本信息</el-divider>
            <el-form-item label="Agent 名称">
              <el-input v-model="agentForm.name" maxlength="128" />
            </el-form-item>
            <el-form-item label="描述">
              <el-input v-model="agentForm.description" type="textarea" :rows="2" maxlength="500" />
            </el-form-item>
            <el-form-item label="系统提示词">
              <el-input
                v-model="agentForm.systemPrompt"
                type="textarea"
                :rows="6"
                placeholder="定义 Agent 的角色、知识库场景与回答要求；将注入意图路由 / 问题改写 / 答案生成 / 自检各节点"
              />
              <div class="tip">动态修改提示词即刻生效于后续问答；留空使用默认提示词。</div>
            </el-form-item>

            <el-divider content-position="left">答案与记忆策略</el-divider>
            <el-form-item label="自检阈值">
              <el-input-number v-model="agentForm.verifyThreshold" :min="0" :max="1" :step="0.05" /> <span class="tip">低于阈值触发重试（0~1）</span>
            </el-form-item>
            <el-form-item label="重试上限">
              <el-input-number v-model="agentForm.maxRetry" :min="0" :max="5" /> <span class="tip">自检不通过时的重试次数</span>
            </el-form-item>
            <el-form-item label="记忆窗口">
              <el-input-number v-model="agentForm.memoryWindow" :min="1" :max="100" /> <span class="tip">带入上下文的历史消息条数</span>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="agentSaving" :disabled="!selectedKb" @click="saveAgent">保存配置</el-button>
            </el-form-item>
          </el-form>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- ===== 编辑模板弹窗（含渲染预览） ===== -->
    <el-dialog v-model="editVisible" :title="`编辑提示词：${editing ? editing.name : ''}`" width="820px" top="5vh">
      <el-form label-width="110px">
        <el-form-item label="模板 key">
          <code>{{ editing ? editing.key : '' }}</code>
          <span class="muted" style="margin-left: 8px">版本 v{{ editing ? editing.version : '' }}</span>
        </el-form-item>
        <el-form-item label="可用变量">
          <span class="muted">{{ (editing ? editing.vars || [] : []).map(v => '{' + v + '}').join('　') }}</span>
        </el-form-item>
        <el-form-item label="模板内容">
          <el-input v-model="editContent" type="textarea" :rows="16" class="code-area" />
        </el-form-item>
        <el-form-item label="渲染预览">
          <div class="preview-row">
            <el-input
              v-for="v in (editing ? editing.vars || [] : [])"
              :key="v"
              :model-value="previewVars[v] || ''"
              :placeholder="`${v} 示例值`"
              class="preview-input"
              @update:model-value="(val) => setPreviewVar(v, val)"
            />
            <el-button :loading="previewing" @click="doPreview">预览</el-button>
            <el-button v-if="previewResult !== null" @click="previewResult = null">收起</el-button>
          </div>
          <div v-if="previewResult !== null" class="preview-result">
            <pre>{{ previewResult }}</pre>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveTemplate">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { agentApi, kbApi, promptApi } from '../api'

const activeTab = ref('templates')

// ===== 模板元数据：key → 中文名 / 用途 / 可用变量（渲染预览用） =====
const META = {
  'intent-route': { name: '意图路由', desc: '将问题拆分为原子片段并判定业务/闲聊/注入类别（多意图入口）', vars: ['kbName', 'agentPrompt', 'recentJson', 'question'] },
  'query-rewrite-rules': { name: '问题改写规则', desc: '检索查询改写器的系统规则（结合摘要与最近对话补全省略）', vars: ['agentPrompt'] },
  'query-rewrite-input': { name: '问题改写输入', desc: '改写节点的数据区：摘要 / 最近对话 / 原问题 / 重试提示', vars: ['summaryText', 'recentJson', 'rawQuestion', 'hint'] },
  'answer-compose-rules': { name: '答案生成规则', desc: '答案生成节点的系统规则（仅依据证据、引用编号约束）', vars: ['agentPrompt'] },
  'answer-compose-input': { name: '答案生成输入', desc: '生成节点的数据区：证据 JSON + 用户问题', vars: ['evidenceJson', 'question'] },
  'answer-verify-rules': { name: '答案自检规则', desc: '阶段一相关性/完整性评估的评分标准与引用校验', vars: ['agentPrompt'] },
  'answer-verify-input': { name: '答案自检输入', desc: '阶段一数据区：上一轮自检 / 上一轮回答 / 证据 / 问题 / 回答', vars: ['prevContext', 'prevAnswerText', 'evidence', 'question', 'answer'] },
  'answer-faithfulness-rules': { name: '事实一致性规则', desc: '阶段二断言级事实校验（SUPPORTED/CONTRADICTED/UNSUPPORTED）', vars: ['agentPrompt'] },
  'answer-faithfulness-input': { name: '事实一致性输入', desc: '阶段二数据区：证据 / 问题 / 回答', vars: ['evidence', 'question', 'answer'] },
  'chat-only': { name: '闲聊回复', desc: '闲聊节点（问候/无关话题/情绪表达）的回复模板', vars: ['question'] },
  'merge-answer': { name: '答案合并', desc: '混合场景（业务+闲聊）合并为一条最终回答（业务回答原文保留）', vars: ['chitchatReply', 'businessAnswer'] },
  'extract-business': { name: '业务知识抽取', desc: 'AI 抽取：从 chunk 抽取业务知识（术语/规则/关系）', vars: ['chunkContent'] },
  'extract-qa': { name: '问答对抽取', desc: 'AI 抽取：从 chunk 抽取高频问答对', vars: ['chunkContent'] },
  'normalize-qa': { name: '问答归一化', desc: '问答对 AI 归一化 + 同义问法扩展', vars: ['question'] },
  'summary-memory': { name: '会话记忆摘要', desc: '滚动摘要压缩（每 10 条消息触发）', vars: ['oldSummary', 'historyJson'] }
}

const templates = ref([])
const loading = ref(false)

const editVisible = ref(false)
const editing = ref(null)
const editContent = ref('')
const saving = ref(false)
const previewVars = reactive({})
const previewing = ref(false)
const previewResult = ref(null)

function fmt(t) {
  return t ? t.replace('T', ' ').slice(0, 19) : ''
}

async function loadTemplates() {
  loading.value = true
  try {
    const list = await promptApi.list()
    templates.value = list
      .map((t) => ({
        ...t,
        name: (META[t.key] || {}).name || t.key,
        desc: (META[t.key] || {}).desc || '',
        vars: (META[t.key] || {}).vars || []
      }))
      .sort((a, b) => (META[a.key] ? 0 : 1) - (META[b.key] ? 0 : 1) || a.key.localeCompare(b.key))
  } finally {
    loading.value = false
  }
}

function openEdit(row) {
  editing.value = row
  editContent.value = row.content
  Object.keys(previewVars).forEach((k) => delete previewVars[k])
  previewResult.value = null
  editVisible.value = true
}

function setPreviewVar(name, val) {
  previewVars[name] = val
}

async function doPreview() {
  if (!editing.value) return
  previewing.value = true
  try {
    const result = await promptApi.preview(editing.value.key, { ...previewVars })
    previewResult.value = result
  } finally {
    previewing.value = false
  }
}

async function saveTemplate() {
  if (!editContent.value.trim()) { ElMessage.warning('模板内容不能为空'); return }
  saving.value = true
  try {
    await promptApi.update(editing.value.key, editContent.value)
    ElMessage.success('提示词已保存，后续问答立即生效')
    editVisible.value = false
    loadTemplates()
  } finally {
    saving.value = false
  }
}

async function resetDefault(row) {
  await ElMessageBox.confirm(`重置「${row.name}」（${row.key}）为出厂默认模板？当前修改将丢失。`, '提示', { type: 'warning' })
  await promptApi.reset(row.key)
  ElMessage.success('已重置为默认模板')
  loadTemplates()
}

// ===== Agent 配置区块 =====

const kbs = ref([])
const selectedKb = ref(null)
const agentLoading = ref(false)
const agentSaving = ref(false)
const agentForm = reactive({
  name: '', description: '', systemPrompt: '',
  verifyThreshold: 0.7, maxRetry: 2, memoryWindow: 20
})

async function loadKbs() {
  try {
    kbs.value = await kbApi.list()
  } catch (e) { /* 拦截器已提示 */ }
}

async function loadAgent() {
  if (!selectedKb.value) return
  agentLoading.value = true
  try {
    const data = await agentApi.get(selectedKb.value)
    Object.assign(agentForm, {
      name: data.name || '',
      description: data.description || '',
      systemPrompt: data.systemPrompt || '',
      verifyThreshold: data.verifyThreshold ?? 0.7,
      maxRetry: data.maxRetry ?? 2,
      memoryWindow: data.memoryWindow ?? 20
    })
  } finally {
    agentLoading.value = false
  }
}

async function saveAgent() {
  agentSaving.value = true
  try {
    await agentApi.update(selectedKb.value, {
      name: agentForm.name,
      description: agentForm.description,
      systemPrompt: agentForm.systemPrompt,
      verifyThreshold: agentForm.verifyThreshold,
      maxRetry: agentForm.maxRetry,
      memoryWindow: agentForm.memoryWindow
    })
    ElMessage.success('Agent 配置已保存，后续问答立即生效')
  } finally {
    agentSaving.value = false
  }
}

onMounted(() => {
  loadTemplates()
  loadKbs()
})
</script>

<style scoped>
.prompt-page { background: #fff; padding: 16px 24px; border-radius: 6px; }
.toolbar { margin-bottom: 12px; }
.muted { color: #909399; font-size: 12px; }
.tip { color: #909399; font-size: 12px; margin-left: 8px; }
code { background: #f4f4f5; padding: 2px 6px; border-radius: 4px; font-size: 12px; }
.code-area :deep(textarea) { font-family: Consolas, Monaco, monospace; font-size: 13px; line-height: 1.6; }
.preview-row { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; width: 100%; }
.preview-input { width: 200px; }
.preview-result { margin-top: 8px; background: #f8f8f8; border: 1px solid #ebeef5; border-radius: 4px; padding: 8px 12px; max-height: 300px; overflow: auto; }
.preview-result pre { margin: 0; white-space: pre-wrap; word-break: break-all; font-size: 13px; }
.agent-section { max-width: 860px; }
</style>

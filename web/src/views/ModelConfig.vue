<template>
  <div>
    <div class="toolbar">
      <el-button type="primary" @click="openCreate">新增模型配置</el-button>
      <span class="muted">模型按类型分组展示：文本 / 识图 / 向量 / 重排 / 标题</span>
    </div>

    <el-tabs v-model="activeType" class="model-tabs">
      <el-tab-pane v-for="g in GROUPS" :key="g.type" :name="g.type">
        <template #label>
          {{ g.label }}
          <span class="tab-count">({{ grouped(g.type).length }})</span>
        </template>
        <el-table :data="grouped(g.type)" v-loading="loading" border>
          <el-table-column prop="name" label="名称" min-width="130" />
          <el-table-column prop="provider" label="供应商" width="120" />
          <el-table-column prop="usage" label="用途" width="100">
            <template #default="{ row }">
              <el-tag v-if="row.usage" type="info" size="small">{{ usageLabel(row.usage) }}</el-tag>
              <span v-else class="muted">通用</span>
            </template>
          </el-table-column>
          <el-table-column prop="modelName" label="模型名" min-width="160" />
          <el-table-column prop="baseUrl" label="Base URL" min-width="200" show-overflow-tooltip />
          <el-table-column label="默认" width="70">
            <template #default="{ row }">{{ row.isDefault ? '✓' : '' }}</template>
          </el-table-column>
          <el-table-column label="启用" width="70">
            <template #default="{ row }">
              <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '是' : '否' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="220" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="test(row)">测试</el-button>
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button link type="danger" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑模型配置' : '新增模型配置'" width="560px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="配置名称" required>
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="供应商" required>
          <el-select v-model="form.provider" style="width: 100%">
            <el-option label="阿里云 DashScope" value="DASHSCOPE" />
            <el-option label="OpenAI 兼容" value="OPENAI_COMPAT" />
          </el-select>
        </el-form-item>
        <el-form-item label="模型类型" required>
          <el-radio-group v-model="form.modelType">
            <el-radio value="CHAT">文本模型</el-radio>
            <el-radio value="EMBEDDING">向量模型</el-radio>
            <el-radio value="VISION">识图模型</el-radio>
            <el-radio value="RERANK">重排模型</el-radio>
            <el-radio value="TITLE">标题模型</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="用途绑定">
          <el-select v-model="form.usage" style="width: 100%" clearable placeholder="通用（不绑定用途）">
            <el-option v-if="features.aiExtraction && form.modelType === 'CHAT'" label="抽取 EXTRACT（AI 抽取/归一化）" value="EXTRACT" />
            <el-option v-if="form.modelType === 'CHAT'" label="生成 GENERATE（问答生成/意图/改写）" value="GENERATE" />
            <el-option v-if="form.modelType === 'CHAT'" label="校验 VERIFY（答案自检/事实核对）" value="VERIFY" />
            <el-option v-if="form.modelType === 'CHAT'" label="路由 ROUTER（意图路由/问题改写）" value="ROUTER" />
            <el-option v-if="form.modelType === 'CHAT'" label="记忆 MEMORY（会话摘要/压缩，未配置复用 ROUTER）" value="MEMORY" />
            <el-option v-if="form.modelType === 'CHAT'" label="闲聊 CHITCHAT（闲聊回复，未配置复用通用）" value="CHITCHAT" />
            <el-option v-if="form.modelType === 'EMBEDDING'" label="检索 RETRIEVE（向量化/召回）" value="RETRIEVE" />
            <el-option v-if="form.modelType === 'VISION'" label="识图 VISION（PDF 图片/OCR）" value="VISION" />
            <el-option v-if="form.modelType === 'RERANK'" label="重排 RERANK（交叉编码器精排）" value="RERANK" />
            <el-option v-if="form.modelType === 'TITLE'" label="标题 TITLE（会话标题概括）" value="TITLE" />
          </el-select>
        </el-form-item>
        <el-form-item label="模型名" required>
          <el-input v-model="form.modelName" :placeholder="form.modelType === 'RERANK' ? '如 gte-rerank-v2（重排模型，DASHSCOPE / OpenAI 兼容）' : '如 qwen-plus / text-embedding-v3 / deepseek-chat / qwen-vl-plus / gpt-4o'" />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="form.apiKey" placeholder="真实 key 或 env:环境变量名（推荐，避免落库）" show-password />
        </el-form-item>
        <el-form-item v-if="form.provider === 'OPENAI_COMPAT'" label="Base URL" required>
          <el-input v-model="form.baseUrl" :placeholder="form.modelType === 'RERANK' ? '如 https://api.siliconflow.cn/v1（需支持 /v1/rerank，/v1 必需）' : '如 https://api.deepseek.com（不要带 /v1，系统自动拼接 /v1/chat/completions）'" />
        </el-form-item>
        <el-form-item v-if="form.modelType === 'RERANK' && form.provider === 'DASHSCOPE'" label="Base URL">
          <el-input v-model="form.baseUrl" placeholder="留空使用 DashScope 官方 text-rerank 端点" />
        </el-form-item>
        <el-form-item label="Temperature">
          <el-input-number v-model="form.temperature" :min="0" :max="2" :step="0.1" />
        </el-form-item>
        <el-form-item label="Max Tokens">
          <el-input-number v-model="form.maxTokens" :min="1" :max="32000" />
        </el-form-item>
        <el-form-item label="设为默认">
          <el-switch v-model="form.isDefault" />
        </el-form-item>
        <el-form-item label="关闭思考">
          <el-switch v-model="form.disableThinking" />
          <span class="muted" style="margin-left: 8px">deepseek 等带 reasoning 的模型建议开启，避免思考 token 占满输出上限导致自检失效</span>
        </el-form-item>
        <el-form-item v-if="form.disableThinking" label="思考参数(JSON)">
          <el-input v-model="form.thinkingParams" type="textarea" :rows="2" placeholder='留空用默认：{"thinking":{"type":"disabled"}}' />
          <div class="muted" style="margin-top: 4px; line-height: 1.5">默认关闭思考参数为 <code>{"thinking":{"type":"disabled"}}</code>（deepseek 系）；qwen3 可填 <code>{"enable_thinking":false}</code>；其它模型按官方文档填 JSON 模板</div>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { modelApi } from '../api'
import { features } from '../config/features'

const list = ref([])
const loading = ref(false)
const dialogVisible = ref(false)
const saving = ref(false)
const editing = ref(null)

// ===== 按类型分组：文本 / 识图 / 向量 / 重排 =====
const GROUPS = [
  { type: 'CHAT', label: '文本模型' },
  { type: 'VISION', label: '识图模型' },
  { type: 'EMBEDDING', label: '向量模型' },
  { type: 'RERANK', label: '重排模型' },
  { type: 'TITLE', label: '标题模型' }
]
const activeType = ref('CHAT')
// AI 抽取关闭时，不暴露已有的 EXTRACT 配置；数据仍保留在后端，可随开关恢复。
const grouped = (type) => list.value.filter((m) =>
  m.modelType === type && (features.aiExtraction || m.usage !== 'EXTRACT')
)

const emptyForm = () => ({
  name: '', provider: 'DASHSCOPE', modelType: 'CHAT', usage: '', modelName: '',
  apiKey: '', baseUrl: '', temperature: undefined, maxTokens: undefined,
  isDefault: false, enabled: true, disableThinking: false, thinkingParams: ''
})
const form = reactive(emptyForm())

const USAGE = { EXTRACT: '抽取', GENERATE: '生成', RETRIEVE: '检索', VISION: '识图', RERANK: '重排', VERIFY: '校验', TITLE: '标题', ROUTER: '路由', MEMORY: '记忆', CHITCHAT: '闲聊' }
const usageLabel = (u) => USAGE[u] || u

// 模型类型切换时清掉不兼容的用途绑定（如从 CHAT 切到 RERANK 时残留 GENERATE）
watch(() => form.modelType, (t) => {
  const valid = {
    CHAT: [...(features.aiExtraction ? ['EXTRACT'] : []), 'GENERATE', 'VERIFY', 'ROUTER', 'MEMORY', 'CHITCHAT'],
    EMBEDDING: ['RETRIEVE'], VISION: ['VISION'], RERANK: ['RERANK'], TITLE: ['TITLE']
  }[t] || []
  if (form.usage && !valid.includes(form.usage)) form.usage = ''
})

async function load() {
  loading.value = true
  try { list.value = await modelApi.list() } finally { loading.value = false }
}

function openCreate() {
  editing.value = null
  Object.assign(form, emptyForm())
  form.modelType = activeType.value // 新增时默认落在当前分组类型
  dialogVisible.value = true
}

function openEdit(row) {
  editing.value = row
  Object.assign(form, {
    name: row.name, provider: row.provider, modelType: row.modelType, usage: row.usage || '',
    modelName: row.modelName,
    apiKey: '', baseUrl: row.baseUrl || '', temperature: row.temperature, maxTokens: row.maxTokens,
    isDefault: row.isDefault, enabled: row.enabled,
    disableThinking: !!row.disableThinking, thinkingParams: row.thinkingParams || ''
  })
  dialogVisible.value = true
}

async function save() {
  if (!form.name || !form.modelName) { ElMessage.warning('请填写必填项'); return }
  saving.value = true
  try {
    const payload = {
      name: form.name, provider: form.provider, modelType: form.modelType, usage: form.usage || undefined,
      modelName: form.modelName,
      apiKey: form.apiKey || undefined, baseUrl: form.baseUrl || undefined,
      temperature: form.temperature, maxTokens: form.maxTokens,
      isDefault: form.isDefault, enabled: form.enabled,
      disableThinking: form.disableThinking, thinkingParams: form.thinkingParams || undefined
    }
    if (editing.value) await modelApi.update(editing.value.id, payload)
    else await modelApi.create(payload)
    ElMessage.success('保存成功')
    dialogVisible.value = false
    load()
  } finally { saving.value = false }
}

async function test(row) {
  const res = await modelApi.test(row.id)
  if (res.success) ElMessage.success(`「${row.name}」连接成功`)
  else ElMessage.error(`「${row.name}」${res.message}`)
}

async function remove(row) {
  await ElMessageBox.confirm(`确定删除模型配置「${row.name}」？`, '提示', { type: 'warning' })
  await modelApi.remove(row.id)
  ElMessage.success('已删除')
  load()
}

onMounted(load)
</script>

<style scoped>
.toolbar { margin-bottom: 16px; display: flex; align-items: center; gap: 12px; }
.muted { color: #909399; font-size: 12px; }
.tab-count { color: #909399; font-size: 12px; margin-left: 2px; }
.model-tabs :deep(.el-tabs__content) { padding-top: 12px; }
</style>

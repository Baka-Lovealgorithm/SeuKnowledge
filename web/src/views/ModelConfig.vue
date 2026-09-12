<template>
  <div>
    <div class="toolbar">
      <el-button type="primary" @click="openCreate">新增模型配置</el-button>
    </div>

    <el-tabs v-model="activeType" class="model-tabs">
      <el-tab-pane v-for="g in GROUPS" :key="g.type" :name="g.type">
        <template #label>
          {{ g.label }}
          <span class="tab-count">({{ grouped(g.type).length }})</span>
        </template>
        <el-table :data="grouped(g.type)" v-loading="loading" border>
          <el-table-column prop="name" label="名称" min-width="150">
            <template #default="{ row }">
              {{ row.name }}
              <el-tooltip v-if="isLegacyTitleRow(row)" placement="top"
                          content="历史 model_type=TITLE 的存量配置，由解析链兜底继续生效；在此编辑保存后自动并入文本模型的「标题」用途，无需迁移数据">
                <el-tag size="small" type="warning">旧·标题类型</el-tag>
              </el-tooltip>
            </template>
          </el-table-column>
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

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="560px">
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
          <el-radio-group v-model="form.modelType" @change="onTypeChange">
            <el-radio value="CHAT">文本模型</el-radio>
            <el-radio value="VISION">识图模型</el-radio>
            <el-radio value="EMBEDDING">向量模型</el-radio>
            <el-radio value="RERANK">重排模型</el-radio>
          </el-radio-group>
          <div class="muted form-hint">识图与文本是不同能力，需单独配置：混在文本模型里会把图片发给不支持图像的模型</div>
        </el-form-item>
        <el-form-item v-if="form.modelType === 'CHAT'" label="用途绑定">
          <el-select v-model="form.usage" style="width: 100%" clearable placeholder="通用（不绑定用途，作为文本模型的兜底）">
            <el-option v-if="features.aiExtraction" label="抽取 EXTRACT（业务知识/问答对抽取）" value="EXTRACT" />
            <el-option label="生成 GENERATE（问答答案生成）" value="GENERATE" />
            <el-option label="校验 VERIFY（答案自检/事实核对）" value="VERIFY" />
            <el-option label="路由 ROUTER（意图路由/问题改写）" value="ROUTER" />
            <el-option label="记忆 MEMORY（会话摘要/压缩）" value="MEMORY" />
            <el-option label="闲聊 CHITCHAT（闲聊回复）" value="CHITCHAT" />
            <el-option label="标题 TITLE（会话标题概括）" value="TITLE" />
          </el-select>
          <div class="muted form-hint">同一个模型要服务多个用途，请另建一条配置</div>
        </el-form-item>
        <el-form-item v-else label="用途绑定">
          <span class="muted">{{ singleUsageOf(form.modelType) }}（该类型仅此一个用途，无需选择）</span>
        </el-form-item>
        <el-form-item label="模型名" required>
          <el-input v-model="form.modelName" :placeholder="namePlaceholder" />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="form.apiKey" placeholder="推荐填 env:环境变量名；也可直接填写 Key" show-password />
          <div v-if="editing && !form.apiKey" class="muted form-hint">留空表示保留已保存的 Key 不变</div>
        </el-form-item>
        <el-form-item v-if="form.provider === 'OPENAI_COMPAT'" label="Base URL" required>
          <el-input v-model="form.baseUrl" :placeholder="baseUrlPlaceholder" />
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
        <el-form-item v-if="showDefaultSwitch" label="设为默认">
          <el-switch v-model="form.isDefault" />
          <span class="muted" style="margin-left: 8px">该类型的通用兜底配置</span>
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
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { modelApi } from '../api'
import { features } from '../config/features'

const list = ref([])
const loading = ref(false)
const dialogVisible = ref(false)
const saving = ref(false)
const editing = ref(null)

// ===== 按调用契约分组：文本 / 识图 / 向量 / 重排 =====
// 「标题」不再是类型：它只是文本契约下的一个用途（历史 model_type=TITLE 的存量行由解析链兜底，见下方 isLegacyTitleRow）
const GROUPS = [
  { type: 'CHAT', label: '文本模型' },
  { type: 'VISION', label: '识图模型' },
  { type: 'EMBEDDING', label: '向量模型' },
  { type: 'RERANK', label: '重排模型' }
]
const activeType = ref('CHAT')

// 唯一用途与类型同名的三类：表单不显示用途下拉，保存时按此值提交
const SINGLE_USAGE = { VISION: 'VISION', EMBEDDING: 'RETRIEVE', RERANK: 'RERANK' }
const USAGE = { EXTRACT: '抽取', GENERATE: '生成', RETRIEVE: '检索', VISION: '识图', RERANK: '重排', VERIFY: '校验', TITLE: '标题', ROUTER: '路由', MEMORY: '记忆', CHITCHAT: '闲聊' }
const usageLabel = (u) => USAGE[u] || u
const singleUsageOf = (type) => USAGE[SINGLE_USAGE[type]] || SINGLE_USAGE[type] || '通用'

/** 历史 model_type=TITLE 的存量行：并入文本 tab，编辑保存即自愈为 CHAT + TITLE 用途 */
const isLegacyTitleRow = (row) => row.modelType === 'TITLE'
// AI 抽取关闭时，不暴露已有的 EXTRACT 配置；数据仍保留在后端，可随开关恢复。
const grouped = (type) => list.value.filter((m) =>
  (m.modelType === type || (type === 'CHAT' && isLegacyTitleRow(m)))
  && (features.aiExtraction || m.usage !== 'EXTRACT')
)

const emptyForm = () => ({
  name: '', provider: 'DASHSCOPE', modelType: 'CHAT', usage: '', modelName: '',
  apiKey: '', baseUrl: '', temperature: undefined, maxTokens: undefined,
  isDefault: false, enabled: true, disableThinking: false, thinkingParams: ''
})
const form = reactive(emptyForm())

const legacyTitle = ref(false)
const dialogTitle = computed(() => {
  if (!editing.value) return '新增模型配置'
  return legacyTitle.value ? '编辑模型配置（历史标题类型 → 文本模型·标题用途）' : '编辑模型配置'
})
// 「默认」是解析链的第 3 档，只有通用行（用途留空）才用得上；已绑定用途的配置由第 1 档直接命中
const showDefaultSwitch = computed(() => !form.usage)

const namePlaceholder = computed(() => ({
  CHAT: '如 qwen-plus / deepseek-chat',
  VISION: '如 qwen-vl-plus / qwen3-omni-flash（须支持图像输入）',
  EMBEDDING: '如 text-embedding-v4（维度需与 KB_ES_DIMENSIONS 一致）',
  RERANK: '如 gte-rerank-v2'
}[form.modelType] || ''))

const baseUrlPlaceholder = computed(() => {
  if (form.modelType === 'RERANK') return '如 https://api.siliconflow.cn/v1（需支持 /v1/rerank，/v1 必需）'
  return '如 https://api.deepseek.com（不要带 /v1，系统自动拼接 /v1/chat/completions）'
})

// 切换类型时把用途重算成目标类型的合法值：非文本类型锁唯一用途，文本类型回落「通用」
function onTypeChange(type) {
  form.usage = SINGLE_USAGE[type] || ''
}

async function load() {
  loading.value = true
  try { list.value = await modelApi.list() } finally { loading.value = false }
}

function openCreate() {
  editing.value = null
  legacyTitle.value = false
  Object.assign(form, emptyForm())
  // 新增时默认落在当前分组类型；非文本类型直接带上它唯一的用途
  const type = activeType.value
  form.modelType = type
  form.usage = SINGLE_USAGE[type] || ''
  dialogVisible.value = true
}

function openEdit(row) {
  editing.value = row
  legacyTitle.value = isLegacyTitleRow(row)
  Object.assign(form, {
    name: row.name, provider: row.provider,
    // 历史 TITLE 行按文本模型编辑（自愈）；用途沿用行上的值
    modelType: legacyTitle.value ? 'CHAT' : row.modelType,
    usage: row.usage || '',
    modelName: row.modelName,
    apiKey: '', baseUrl: row.baseUrl || '', temperature: row.temperature, maxTokens: row.maxTokens,
    isDefault: legacyTitle.value ? false : row.isDefault, enabled: row.enabled,
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
      // 已绑定用途的配置不占「默认」档（否则会挤掉该类型真正的通用默认行）
      isDefault: form.usage ? false : form.isDefault, enabled: form.enabled,
      disableThinking: form.disableThinking, thinkingParams: form.thinkingParams || undefined
    }
    if (editing.value) await modelApi.update(editing.value.id, payload)
    else await modelApi.create(payload)
    ElMessage.success(legacyTitle.value ? '保存成功：已并入文本模型的「标题」用途' : '保存成功')
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
.form-hint { margin-top: 2px; line-height: 1.5; }
.model-tabs :deep(.el-tabs__content) { padding-top: 12px; }
</style>

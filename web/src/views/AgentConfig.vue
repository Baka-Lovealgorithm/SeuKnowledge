<template>
  <div class="agent-page">
    <div class="toolbar">
      <span class="page-title">Agent 配置</span>
      <el-tooltip placement="bottom-start">
        <template #content>
          <div class="agent-tip">
            <div>每个知识库绑定一个 Agent：系统提示词与答案/记忆策略参数（仅作用于该知识库的后续问答）。</div>
            <div>编辑保存后立即生效，无需重启。</div>
          </div>
        </template>
        <el-icon class="tip-icon"><QuestionFilled /></el-icon>
      </el-tooltip>
    </div>

    <el-form :model="agentForm" label-width="130px" class="agent-form" v-loading="agentLoading">
      <el-form-item label="选择知识库" required>
        <el-select v-model="selectedKb" filterable placeholder="选择知识库后配置其 Agent" style="width: 360px" @change="loadAgent">
          <el-option v-for="kb in kbs" :key="kb.id" :label="kb.name" :value="kb.id" />
        </el-select>
        <el-tooltip placement="top">
          <template #content>
            <div class="agent-tip">配置即刻生效于该知识库的后续问答</div>
          </template>
          <el-icon class="tip-icon inline"><QuestionFilled /></el-icon>
        </el-tooltip>
      </el-form-item>

      <el-divider content-position="left">基本信息</el-divider>
      <el-form-item label="Agent 名称">
        <el-input v-model="agentForm.name" maxlength="128" :disabled="!selectedKb" />
      </el-form-item>
      <el-form-item label="描述">
        <el-input v-model="agentForm.description" type="textarea" :rows="2" maxlength="500" :disabled="!selectedKb" />
      </el-form-item>
      <el-form-item label="系统提示词">
        <el-input
          v-model="agentForm.systemPrompt"
          type="textarea"
          :rows="6"
          :disabled="!selectedKb"
          placeholder="定义 Agent 的角色、知识库场景与回答要求"
        />
        <div class="row-tip">
          <el-tooltip placement="top">
            <template #content>
              <div class="agent-tip">动态修改提示词即刻生效于后续问答；留空使用默认提示词。</div>
            </template>
            <el-icon class="tip-icon"><QuestionFilled /></el-icon>
          </el-tooltip>
        </div>
      </el-form-item>

      <el-divider content-position="left">答案与记忆策略</el-divider>
      <el-form-item label="自检阈值">
        <el-input-number v-model="agentForm.verifyThreshold" :min="0" :max="1" :step="0.05" :disabled="!selectedKb" />
        <el-tooltip placement="top">
          <template #content>
            <div class="agent-tip">低于阈值触发重试（0~1）</div>
          </template>
          <el-icon class="tip-icon inline"><QuestionFilled /></el-icon>
        </el-tooltip>
      </el-form-item>
      <el-form-item label="重试上限">
        <el-input-number v-model="agentForm.maxRetry" :min="0" :max="5" :disabled="!selectedKb" />
        <el-tooltip placement="top">
          <template #content>
            <div class="agent-tip">自检不通过时的重试次数</div>
          </template>
          <el-icon class="tip-icon inline"><QuestionFilled /></el-icon>
        </el-tooltip>
      </el-form-item>
      <el-form-item label="最近对话轮数">
        <el-input-number v-model="agentForm.recentRounds" :min="1" :max="maxRecentRounds" :disabled="!selectedKb" @change="onRecentRoundsChange" />
        <el-tooltip placement="top">
          <template #content>
            <div class="agent-tip">注入原文的最近对话轮数（1~{{ maxRecentRounds }}）：越大上下文越全，token 成本越高</div>
          </template>
          <el-icon class="tip-icon inline"><QuestionFilled /></el-icon>
        </el-tooltip>
      </el-form-item>
      <el-form-item label="压缩间隔轮数">
        <el-input-number v-model="agentForm.summaryIntervalRounds" :min="1" :max="agentForm.recentRounds" :disabled="!selectedKb" />
        <el-tooltip placement="top">
          <template #content>
            <div class="agent-tip">每多少轮把更早的对话压缩为摘要（须 ≤ 最近对话轮数，否则中间的对话会丢失）</div>
          </template>
          <el-icon class="tip-icon inline"><QuestionFilled /></el-icon>
        </el-tooltip>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="agentSaving" :disabled="!selectedKb" @click="saveAgent">保存配置</el-button>
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { QuestionFilled } from '@element-plus/icons-vue'
import { agentApi, kbApi } from '../api'

const kbs = ref([])
const selectedKb = ref(null)
const agentLoading = ref(false)
const agentSaving = ref(false)
// 近窗轮数上限 = 全局 qa.message-window / 2（默认 20 条 → 10 轮）；后端 AgentService 会再校验一次
const maxRecentRounds = 10
const agentForm = reactive({
  name: '', description: '', systemPrompt: '',
  verifyThreshold: 0.7, maxRetry: 2, recentRounds: 3, summaryIntervalRounds: 3
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
      recentRounds: data.recentRounds ?? 3,
      summaryIntervalRounds: data.summaryIntervalRounds ?? 3
    })
  } finally {
    agentLoading.value = false
  }
}

/** 近窗轮数下调时同步收紧压缩间隔上限（间隔 > 近窗会让两次压缩之间的对话丢失） */
function onRecentRoundsChange(v) {
  if (agentForm.summaryIntervalRounds > v) {
    agentForm.summaryIntervalRounds = v
  }
}

async function saveAgent() {
  // 防空洞：后端 AgentService.validateMemoryPolicy 是权威校验，这里先给即时反馈
  if (agentForm.summaryIntervalRounds > agentForm.recentRounds) {
    ElMessage.warning('压缩间隔轮数不能大于最近对话轮数，否则两次压缩之间的对话会丢失')
    return
  }
  agentSaving.value = true
  try {
    await agentApi.update(selectedKb.value, {
      name: agentForm.name,
      description: agentForm.description,
      systemPrompt: agentForm.systemPrompt,
      verifyThreshold: agentForm.verifyThreshold,
      maxRetry: agentForm.maxRetry,
      recentRounds: agentForm.recentRounds,
      summaryIntervalRounds: agentForm.summaryIntervalRounds
    })
    ElMessage.success('Agent 配置已保存，后续问答立即生效')
  } finally {
    agentSaving.value = false
  }
}

onMounted(() => {
  loadKbs()
})
</script>

<style scoped>
.agent-page { background: #fff; padding: 16px 24px; border-radius: 6px; max-width: 860px; }
.toolbar { margin-bottom: 12px; display: flex; align-items: center; gap: 8px; }
.page-title { font-size: 15px; font-weight: 600; }
.tip-icon { color: #909399; font-size: 16px; cursor: help; }
.tip-icon.inline { font-size: 14px; margin-left: 8px; vertical-align: middle; }
.agent-tip { line-height: 1.7; max-width: 420px; }
.row-tip { margin-top: 6px; }
</style>

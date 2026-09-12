<template>
  <div class="agent-page">
    <div class="toolbar">
      <span class="muted">每个知识库绑定一个 Agent：系统提示词与答案/记忆策略参数（仅作用于该知识库的后续问答）。编辑保存后立即生效，无需重启。</span>
    </div>

    <el-form :model="agentForm" label-width="130px" class="agent-form" v-loading="agentLoading">
      <el-form-item label="选择知识库" required>
        <el-select v-model="selectedKb" filterable placeholder="选择知识库后配置其 Agent" style="width: 360px" @change="loadAgent">
          <el-option v-for="kb in kbs" :key="kb.id" :label="kb.name" :value="kb.id" />
        </el-select>
        <span class="tip">配置即刻生效于该知识库的后续问答</span>
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
        <div class="tip">动态修改提示词即刻生效于后续问答；留空使用默认提示词。</div>
      </el-form-item>

      <el-divider content-position="left">答案与记忆策略</el-divider>
      <el-form-item label="自检阈值">
        <el-input-number v-model="agentForm.verifyThreshold" :min="0" :max="1" :step="0.05" :disabled="!selectedKb" /> <span class="tip">低于阈值触发重试（0~1）</span>
      </el-form-item>
      <el-form-item label="重试上限">
        <el-input-number v-model="agentForm.maxRetry" :min="0" :max="5" :disabled="!selectedKb" /> <span class="tip">自检不通过时的重试次数</span>
      </el-form-item>
      <el-form-item label="记忆窗口">
        <el-input-number v-model="agentForm.memoryWindow" :min="1" :max="100" :disabled="!selectedKb" /> <span class="tip">带入上下文的历史消息条数</span>
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
import { agentApi, kbApi } from '../api'

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
  loadKbs()
})
</script>

<style scoped>
.agent-page { background: #fff; padding: 16px 24px; border-radius: 6px; max-width: 860px; }
.toolbar { margin-bottom: 12px; }
.muted { color: #909399; font-size: 12px; }
.tip { color: #909399; font-size: 12px; margin-left: 8px; }
</style>

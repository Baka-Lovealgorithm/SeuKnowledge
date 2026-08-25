<template>
  <div class="agent-page">
    <div class="toolbar">
      <el-button @click="goBack">← 返回知识库</el-button>
      <span class="title">知识库「{{ kbName }}」的 Agent 配置</span>
      <el-button v-if="auth.canWrite" type="primary" :loading="saving" @click="save">保存配置</el-button>
    </div>

    <el-form :model="form" label-width="130px" v-loading="loading" class="agent-form" :disabled="!auth.canWrite">
      <el-divider content-position="left">基本信息</el-divider>
      <el-form-item label="Agent 名称">
        <el-input v-model="form.name" maxlength="128" />
      </el-form-item>
      <el-form-item label="描述">
        <el-input v-model="form.description" type="textarea" :rows="2" maxlength="500" />
      </el-form-item>
      <el-form-item label="系统提示词">
        <el-input
          v-model="form.systemPrompt"
          type="textarea"
          :rows="6"
          placeholder="定义 Agent 的角色、知识库场景与回答要求；将注入意图路由 / 问题改写 / 答案生成 / 自检各节点"
        />
        <div class="tip">动态修改提示词即刻生效于后续问答；留空使用默认提示词。</div>
      </el-form-item>

      <el-divider content-position="left">检索策略</el-divider>
      <el-form-item label="召回条数（TopK）">
        <el-input-number v-model="form.topK" :min="1" :max="20" /> <span class="tip">每个候选查询每源召回的条数</span>
      </el-form-item>
      <el-form-item label="证据条数（TopN）">
        <el-input-number v-model="form.topN" :min="1" :max="20" /> <span class="tip">重排后作为答案证据的条数</span>
      </el-form-item>
      <el-form-item label="文档权重">
        <el-input-number v-model="form.chunkWeight" :min="0" :max="5" :step="0.1" />
      </el-form-item>
      <el-form-item label="业务知识权重">
        <el-input-number v-model="form.businessWeight" :min="0" :max="5" :step="0.1" />
      </el-form-item>
      <el-form-item label="问答对权重">
        <el-input-number v-model="form.qaWeight" :min="0" :max="5" :step="0.1" />
      </el-form-item>

      <el-divider content-position="left">答案与记忆策略</el-divider>
      <el-form-item label="自检阈值">
        <el-input-number v-model="form.verifyThreshold" :min="0" :max="1" :step="0.05" /> <span class="tip">低于阈值触发重试（0~1）</span>
      </el-form-item>
      <el-form-item label="重试上限">
        <el-input-number v-model="form.maxRetry" :min="0" :max="5" /> <span class="tip">自检不通过时的重试次数</span>
      </el-form-item>
      <el-form-item label="记忆窗口">
        <el-input-number v-model="form.memoryWindow" :min="1" :max="100" /> <span class="tip">带入上下文的历史消息条数</span>
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { agentApi } from '../api'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const kbId = Number(route.params.kbId)
const kbName = route.query.kbName || `#${kbId}`

const loading = ref(false)
const saving = ref(false)
const form = reactive({
  name: '', description: '', systemPrompt: '',
  topK: 5, topN: 5, verifyThreshold: 0.5, maxRetry: 2, memoryWindow: 20,
  chunkWeight: 1.0, businessWeight: 1.2, qaWeight: 1.2
})

async function load() {
  loading.value = true
  try {
    const data = await agentApi.get(kbId)
    Object.assign(form, {
      name: data.name || '',
      description: data.description || '',
      systemPrompt: data.systemPrompt || '',
      topK: data.topK ?? 5,
      topN: data.topN ?? 5,
      verifyThreshold: data.verifyThreshold ?? 0.5,
      maxRetry: data.maxRetry ?? 2,
      memoryWindow: data.memoryWindow ?? 20,
      chunkWeight: data.chunkWeight ?? 1.0,
      businessWeight: data.businessWeight ?? 1.2,
      qaWeight: data.qaWeight ?? 1.2
    })
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    await agentApi.update(kbId, {
      name: form.name,
      description: form.description,
      systemPrompt: form.systemPrompt,
      topK: form.topK,
      topN: form.topN,
      verifyThreshold: form.verifyThreshold,
      maxRetry: form.maxRetry,
      memoryWindow: form.memoryWindow,
      chunkWeight: form.chunkWeight,
      businessWeight: form.businessWeight,
      qaWeight: form.qaWeight
    })
    ElMessage.success('Agent 配置已保存，后续问答立即生效')
  } finally {
    saving.value = false
  }
}

function goBack() {
  router.push('/kb')
}

onMounted(load)
</script>

<style scoped>
.agent-page { background: #fff; padding: 16px 24px; border-radius: 6px; }
.toolbar { display: flex; align-items: center; gap: 16px; margin-bottom: 8px; }
.title { font-size: 16px; font-weight: 600; flex: 1; }
.agent-form { max-width: 860px; }
.tip { color: #909399; font-size: 12px; margin-left: 8px; }
</style>

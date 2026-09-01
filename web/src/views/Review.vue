<template>
  <div>
    <div class="toolbar">
      <el-select v-model="kbId" placeholder="选择知识库" style="width: 220px" @change="load">
        <el-option v-for="kb in kbs" :key="kb.id" :label="kb.name" :value="kb.id" />
      </el-select>
      <el-tag v-if="kbId && queue.length" type="warning" size="small" effect="plain">
        待审核 {{ queue.length }} 块
      </el-tag>
      <el-button v-if="auth.canWrite && kbId && selected.length" type="primary" size="small" @click="batchKeep">
        批量保留 ({{ selected.length }})
      </el-button>
      <el-button v-if="auth.canWrite && kbId && selected.length" type="danger" size="small" @click="batchDrop">
        批量删除 ({{ selected.length }})
      </el-button>
    </div>

    <el-table :data="queue" v-loading="loading" border @selection-change="onSelection">
      <el-table-column v-if="auth.canWrite" type="selection" width="50" />
      <el-table-column prop="docName" label="文档" min-width="150" show-overflow-tooltip />
      <el-table-column prop="pageNum" label="页码" width="70" />
      <el-table-column prop="title" label="所属标题" min-width="130" show-overflow-tooltip />
      <el-table-column label="内容" min-width="280">
        <template #default="{ row }">
          <div class="content-cell">{{ row.content }}</div>
        </template>
      </el-table-column>
      <el-table-column label="命中规则" min-width="160">
        <template #default="{ row }">
          <el-tooltip :content="row.cleanReason || ''" placement="top">
            <span class="reason-text">{{ ruleId(row) }}</span>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="操作" :width="auth.canWrite ? 260 : 80" fixed="right">
        <template #default="{ row }">
          <el-button link type="info" @click="openDetail(row)">详情</el-button>
          <template v-if="auth.canWrite">
            <el-button link type="success" @click="keep(row)">保留</el-button>
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="drop(row)">删除</el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && kbId && !queue.length" description="暂无待审核的 SUSPECT 分块" />

    <el-dialog v-model="editVisible" title="编辑并保留分块" width="720px">
      <div class="edit-reason" v-if="editing && editing.cleanReason">
        <b>命中原因：</b>{{ editing.cleanReason }}
      </div>
      <el-form label-width="80px">
        <el-form-item label="所属标题">
          <el-input v-model="editTitle" placeholder="小节标题（可修改）" />
        </el-form-item>
        <el-form-item label="内容" required>
          <el-input v-model="editContent" type="textarea" :rows="10" placeholder="修改分块内容（保存后重新向量化）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!editContent || !editContent.trim()" @click="saveEdit">
          保存并保留
        </el-button>
      </template>
    </el-dialog>

    <!-- chunk 详情（只读，完整内容） -->
    <ChunkDetail v-model="detailVisible" :row="detailRow" />
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { kbApi, reviewApi } from '../api'
import { useAuthStore } from '../stores/auth'
import ChunkDetail from '../components/ChunkDetail.vue'

const auth = useAuthStore()
const kbs = ref([])
const kbId = ref(null)
const queue = ref([])
const loading = ref(false)
const selected = ref([])
const editVisible = ref(false)
const editing = ref(null)
const editTitle = ref('')
const editContent = ref('')
const saving = ref(false)
const detailVisible = ref(false)
const detailRow = ref(null)

async function loadKbs() {
  kbs.value = await kbApi.list()
}

async function load() {
  if (!kbId.value) return
  loading.value = true
  try { queue.value = await reviewApi.suspectQueue(kbId.value) } finally { loading.value = false }
}

function ruleId(row) {
  const r = (row.cleanReason || '').split(' ')[0] || ''
  return r || row.status || ''
}

function onSelection(rows) {
  selected.value = rows
}

function openDetail(row) {
  detailRow.value = row
  detailVisible.value = true
}

async function keep(row) {
  await reviewApi.keep(row.chunkId)
  ElMessage.success('已保留并向量化')
  load()
}

async function drop(row) {
  await ElMessageBox.confirm(
    `确定删除该分块？删除后从向量库移除（MySQL 记录保留）。\n\n${(row.content || '').slice(0, 120)}`,
    '删除分块',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
  )
  await reviewApi.drop(row.chunkId)
  ElMessage.success('已删除')
  load()
}

function openEdit(row) {
  editing.value = row
  editTitle.value = row.title || ''
  editContent.value = row.content || ''
  editVisible.value = true
}

async function saveEdit() {
  saving.value = true
  try {
    await reviewApi.edit(editing.value.chunkId, { content: editContent.value, title: editTitle.value })
    ElMessage.success('已保存并重新向量化')
    editVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function batchKeep() {
  const ids = selected.value.map((r) => r.chunkId)
  await ElMessageBox.confirm(`批量保留 ${ids.length} 个分块并向量化？`, '批量保留', { type: 'info' })
  await reviewApi.batch(ids, 'keep')
  ElMessage.success('批量保留完成')
  load()
}

async function batchDrop() {
  const ids = selected.value.map((r) => r.chunkId)
  await ElMessageBox.confirm(`确定批量删除 ${ids.length} 个分块？`, '批量删除', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  await reviewApi.batch(ids, 'drop')
  ElMessage.success('批量删除完成')
  load()
}

onMounted(async () => {
  await loadKbs()
})
</script>

<style scoped>
.toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.content-cell {
  max-height: 72px; overflow: hidden; text-overflow: ellipsis;
  display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical;
  white-space: pre-line; word-break: break-all; font-size: 13px;
}
.reason-text { color: #e6a23c; font-size: 13px; cursor: default; }
.edit-reason { background: #fdf6ec; border: 1px solid #faecd8; border-radius: 4px; padding: 8px 12px; margin-bottom: 12px; font-size: 13px; color: #8a6d3b; }
</style>

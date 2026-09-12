<template>
  <div>
    <div class="toolbar">
      <el-button type="primary" @click="openCreate">新建组</el-button>
      <span class="tip">组用于知识库的组级别授权：把知识库授权给一个组后，组内所有成员共享该权限（取最高权限，EDIT 覆盖 VIEW）。组内成员仅限当前工作空间成员。</span>
    </div>

    <el-table :data="groups" v-loading="loading" border>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="组名" min-width="140" />
      <el-table-column prop="description" label="描述" min-width="180" show-overflow-tooltip />
      <el-table-column prop="memberCount" label="成员数" width="90" />
      <el-table-column prop="createdAt" label="创建时间" width="170">
        <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openMembers(row)">成员</el-button>
          <el-button link type="primary" size="small" @click="openRename(row)">改名</el-button>
          <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新建组 -->
    <el-dialog v-model="createVisible" title="新建组" width="420px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="组名">
          <el-input v-model="form.name" maxlength="64" placeholder="如：研发组" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" maxlength="200" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 改名 -->
    <el-dialog v-model="renameVisible" title="修改组" width="420px">
      <el-form :model="renameForm" label-width="80px">
        <el-form-item label="组名">
          <el-input v-model="renameForm.name" maxlength="64" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="renameForm.description" type="textarea" :rows="2" maxlength="200" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="renameVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitRename">保存</el-button>
      </template>
    </el-dialog>

    <!-- 组成员管理 -->
    <el-dialog v-model="membersVisible" :title="`组内成员：${currentGroup?.name || ''}`" width="520px">
      <div class="member-add">
        <el-select v-model="newMemberUserId" placeholder="选择空间成员" filterable style="width: 240px">
          <el-option v-for="m in memberOptions" :key="m.userId" :label="m.username" :value="m.userId" />
        </el-select>
        <el-button type="primary" style="margin-left: 8px" :disabled="!newMemberUserId" @click="addMember">添加</el-button>
      </div>
      <el-table :data="memberList" border size="small" max-height="300">
        <el-table-column prop="username" label="用户名" min-width="140" />
        <el-table-column prop="createdAt" label="加入时间" width="170">
          <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="80" align="center">
          <template #default="{ row }">
            <el-button link type="danger" size="small" @click="removeMember(row)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { groupApi, workspaceApi } from '../api'

const groups = ref([])
const loading = ref(false)
const saving = ref(false)

const createVisible = ref(false)
const form = reactive({ name: '', description: '' })

const renameVisible = ref(false)
const renameForm = reactive({ name: '', description: '' })
const renamingId = ref(null)

const membersVisible = ref(false)
const currentGroup = ref(null)
const memberList = ref([])
const allMembers = ref([])
const newMemberUserId = ref(null)

const memberOptions = computed(() =>
  allMembers.value.filter((m) => !memberList.value.some((gm) => gm.userId === m.userId))
)

const fmt = (t) => (t ? t.replace('T', ' ').slice(0, 19) : '')

async function load() {
  loading.value = true
  try {
    groups.value = await groupApi.list()
  } finally {
    loading.value = false
  }
}

function openCreate() {
  form.name = ''
  form.description = ''
  createVisible.value = true
}

async function submitCreate() {
  if (!form.name || !form.name.trim()) {
    ElMessage.warning('请填写组名')
    return
  }
  saving.value = true
  try {
    await groupApi.create({ name: form.name.trim(), description: form.description })
    ElMessage.success('组已创建')
    createVisible.value = false
    load()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    saving.value = false
  }
}

function openRename(row) {
  renamingId.value = row.id
  renameForm.name = row.name
  renameForm.description = row.description || ''
  renameVisible.value = true
}

async function submitRename() {
  if (!renameForm.name || !renameForm.name.trim()) {
    ElMessage.warning('请填写组名')
    return
  }
  saving.value = true
  try {
    await groupApi.rename(renamingId.value, {
      name: renameForm.name.trim(),
      description: renameForm.description
    })
    ElMessage.success('组已更新')
    renameVisible.value = false
    load()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    saving.value = false
  }
}

async function remove(row) {
  await ElMessageBox.confirm(
    `确定删除组「${row.name}」？其成员关系与该组的所有知识库授权记录将一并清除。`,
    '删除组',
    { type: 'warning' }
  )
  await groupApi.remove(row.id)
  ElMessage.success('组已删除')
  load()
}

async function openMembers(row) {
  currentGroup.value = row
  memberList.value = []
  newMemberUserId.value = null
  membersVisible.value = true
  const [ms, gms] = await Promise.all([workspaceApi.members(), groupApi.members(row.id)])
  allMembers.value = ms
  memberList.value = gms
}

async function addMember() {
  await groupApi.addMember(currentGroup.value.id, { userId: newMemberUserId.value })
  ElMessage.success('成员已添加')
  newMemberUserId.value = null
  memberList.value = await groupApi.members(currentGroup.value.id)
  // 同步刷新主表成员数
  load()
}

async function removeMember(row) {
  await ElMessageBox.confirm(`确定将「${row.username}」移出组？`, '移除成员', { type: 'warning' })
  await groupApi.removeMember(currentGroup.value.id, row.userId)
  ElMessage.success('已移除')
  memberList.value = await groupApi.members(currentGroup.value.id)
  load()
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }
.tip { color: #909399; font-size: 12px; }
.member-add { display: flex; align-items: center; margin-bottom: 12px; }
</style>

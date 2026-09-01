<template>
  <el-dialog
    :model-value="modelValue"
    :title="`chunk 详情${row && row.seq != null ? ' #' + row.seq : ''}`"
    width="760px"
    top="5vh"
    @update:model-value="(v) => emit('update:modelValue', v)"
  >
    <template v-if="row">
      <div class="meta">
        <el-tag :type="tagType" size="small" effect="plain">{{ tagText }}</el-tag>
        <span class="meta-item">页码：{{ row.pageNum ?? '-' }}</span>
        <span class="meta-item">所属标题：{{ row.title || '-' }}</span>
        <span v-if="row.docName" class="meta-item">文档：{{ row.docName }}</span>
      </div>
      <div v-if="row.cleanReason" class="reason">
        <b>命中规则：</b>{{ row.cleanReason }}
      </div>
      <div class="content">{{ row.content }}</div>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  row: { type: Object, default: null }
})
const emit = defineEmits(['update:modelValue'])

const tagText = computed(() => props.row?.cleanStatus || props.row?.status || 'KEEP')
const tagType = computed(() => {
  const s = props.row?.cleanStatus
  if (s === 'SUSPECT') return 'warning'
  if (s === 'FILTERED') return 'info'
  return 'success'
})
</script>

<style scoped>
.meta { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; margin-bottom: 12px; font-size: 13px; color: #606266; }
.meta-item { color: #909399; }
.reason {
  background: #fdf6ec; border: 1px solid #faecd8; border-radius: 4px;
  padding: 8px 12px; margin-bottom: 12px; font-size: 13px; color: #8a6d3b;
  max-height: 96px; overflow: auto; white-space: pre-wrap; word-break: break-all;
}
.content {
  border: 1px solid #e4e7ed; border-radius: 4px; padding: 12px; background: #fff;
  max-height: 58vh; overflow: auto;
  font-size: 14px; line-height: 1.7; color: #303133;
  white-space: pre-line; word-break: break-all;
}
</style>

<template>
  <el-pagination
    v-model:current-page="page"
    v-model:page-size="pageSize"
    :total="total"
    :page-sizes="pageSizes"
    layout="total, sizes, prev, pager, next"
    @current-change="(p) => emit('page-change', p)"
    @size-change="(s) => emit('size-change', s)"
  />
</template>

<script setup>
/**
 * 分页条：统一 layout 与页长档位，避免每个列表页各写一份。
 *
 * page 为 1 基（对齐 el-pagination 的 current-page）；请求用的 0 基页码由父页面自行换算。
 * page/pageSize 用 v-model 双向绑定，事件只是额外通知——父页面若只依赖 v-model（如前端分页）
 * 可以不监听事件，行为与直接绑 el-pagination 一致。
 */
const page = defineModel('page', { type: Number, required: true })
const pageSize = defineModel('pageSize', { type: Number, required: true })
defineProps({
  total: { type: Number, default: 0 },
  /** 页长档位：文档分块/初洗用完整档位，文档管理只需 20/50 */
  pageSizes: { type: Array, default: () => [20, 50, 100, 200] }
})
const emit = defineEmits(['page-change', 'size-change'])
</script>

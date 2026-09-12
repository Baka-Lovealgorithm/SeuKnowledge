<template>
  <div class="stats-page">
    <div class="toolbar">
      <el-select v-model="days" style="width: 120px" @change="reload">
        <el-option :value="7" label="近 7 天" />
        <el-option :value="30" label="近 30 天" />
        <el-option :value="90" label="近 90 天" />
      </el-select>
      <el-button :loading="loading" @click="reload">刷新</el-button>
    </div>

    <el-alert type="info" :closable="false" class="notice">
      点踩由提问者本人提交；本页仅空间 OWNER / ADMIN 可见。
    </el-alert>

    <div class="cards">
      <el-card v-for="c in cards" :key="c.label" shadow="never" class="card">
        <div class="card-label">{{ c.label }}</div>
        <div class="card-value">{{ c.value }}</div>
        <div v-if="c.hint" class="card-hint">{{ c.hint }}</div>
      </el-card>
    </div>

    <el-row :gutter="12" class="row">
      <el-col :span="9">
        <el-card shadow="never" class="block">
          <template #header>点踩原因分布</template>
          <div v-if="!overview || !overview.reasonBreakdown.length" class="empty">该窗口内还没有点踩</div>
          <div v-for="r in (overview ? overview.reasonBreakdown : [])" :key="r.code || 'NULL'" class="reason-row">
            <span class="reason-name">{{ r.label }}</span>
            <el-progress :percentage="pctOf(r.count)" :stroke-width="12" class="reason-bar" :show-text="false" />
            <span class="reason-num">{{ r.count }}</span>
          </div>
        </el-card>
      </el-col>
      <el-col :span="15">
        <el-card shadow="never" class="block">
          <template #header>
            自检快照视角
            <span class="sub-tip">（只统计含快照的回答，共 {{ overview?.snapshotCount ?? 0 }} 条；采集上线前的存量行不参与）</span>
          </template>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="全部回答的自检分均值">{{ num(overview?.avgVerifyScore) }}</el-descriptions-item>
            <el-descriptions-item label="被踩答案的自检分均值">{{ num(overview?.avgVerifyScoreOfDisliked) }}</el-descriptions-item>
            <el-descriptions-item label="事实一致性均值">{{ num(overview?.avgFaithfulness) }}</el-descriptions-item>
          </el-descriptions>
          <div class="gap-tip">
            两个均值差距越大，说明用户踩得越"准"（低质量答案被踩）；若被踩答案的均值不低于整体，
            更可能是检索没覆盖到用户真正想问的内容，而不是生成质量差。
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="block">
      <template #header>
        点踩明细
        <span class="sub-tip">（共 {{ total }} 条）</span>
      </template>
      <el-table :data="items" :loading="tableLoading" size="small" border>
        <el-table-column prop="question" label="问题" min-width="180" show-overflow-tooltip />
        <el-table-column prop="answerExcerpt" label="答案摘要" min-width="220" show-overflow-tooltip />
        <el-table-column label="原因" width="120">
          <template #default="{ row }">
            {{ row.reasonLabel || '未填写' }}
            <el-tag v-if="row.interrupted" size="small" type="info">已停止</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="note" label="补充" min-width="140" show-overflow-tooltip />
        <el-table-column label="自检/一致性" width="120">
          <template #default="{ row }">{{ num(row.verifyScore) }} / {{ num(row.faithfulnessScore) }}</template>
        </el-table-column>
        <el-table-column label="重试" width="70">
          <template #default="{ row }">{{ row.retryCount ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="missingInfo" label="缺失项" min-width="180" show-overflow-tooltip />
        <el-table-column prop="sessionTitle" label="会话" width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.sessionTitle || ('会话 #' + row.sessionId) }}</template>
        </el-table-column>
        <el-table-column label="反馈时间" width="150">
          <template #default="{ row }">{{ fmt(row.feedbackAt) }}</template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-if="total > size"
        layout="prev, pager, next, total"
        :total="total"
        :page-size="size"
        :current-page="page + 1"
        class="pager"
        @current-change="onPage"
      />
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { statsApi } from '../api'

const days = ref(30)
const overview = ref(null)
const items = ref([])
const total = ref(0)
const page = ref(0)
const size = ref(20)
const loading = ref(false)
const tableLoading = ref(false)


const cards = computed(() => {
  const o = overview.value
  return [
    { label: '回答数', value: o ? o.answerCount : '—', hint: '窗口内智能体回答条数' },
    { label: '点踩数', value: o ? o.dislikeCount : '—', hint: o && o.likeCount ? `点赞 ${o.likeCount} 条` : '点赞仅作对照，不参与踩率分母' },
    {
      label: '踩率',
      value: o ? pct(o.dislikeRate) : '—',
      hint: o && o.ratedCount ? `按 ${o.ratedCount} 条已评价回答计算` : '尚无人评价'
    },
    {
      label: '无证据回答',
      value: o ? pct(o.answerCount ? o.noEvidenceCount / o.answerCount : 0) : '—',
      hint: o ? `${o.noEvidenceCount} 条未引用任何知识（多为拒答/闲聊）` : ''
    },
    {
      label: '被中途停止',
      value: o ? pct(o.answerCount ? o.interruptedCount / o.answerCount : 0) : '—',
      hint: o ? `${o.interruptedCount} 条用户等不及点了停止` : ''
    }
  ]
})

function pct(v) {
  if (v === null || v === undefined || Number.isNaN(v)) return '0.0%'
  return (v * 100).toFixed(1) + '%'
}

/** 快照列对存量行是 null，显示成「—」而不是 0，避免读数字的人以为分数真的为 0 */
function num(v) {
  return v === null || v === undefined ? '—' : Number(v).toFixed(2)
}

function fmt(t) {
  return t ? String(t).replace('T', ' ').slice(0, 16) : '—'
}

function pctOf(count) {
  const down = overview.value?.dislikeCount || 0
  return down ? Math.round((count / down) * 100) : 0
}

async function loadOverview() {
  loading.value = true
  try {
    overview.value = await statsApi.overview(days.value)
  } catch (e) {
    overview.value = null
  } finally {
    loading.value = false
  }
}

async function loadDislikes() {
  tableLoading.value = true
  try {
    const res = await statsApi.dislikes(days.value, page.value, size.value)
    items.value = res?.items || []
    total.value = res?.total || 0
  } catch (e) {
    items.value = []
    total.value = 0
  } finally {
    tableLoading.value = false
  }
}

function reload() {
  page.value = 0
  loadOverview()
  loadDislikes()
}

function onPage(p) {
  page.value = p - 1
  loadDislikes()
}

onMounted(reload)
</script>

<style scoped>
.stats-page { padding: 16px; }
.toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.notice { margin-bottom: 12px; }
.cards { display: flex; gap: 12px; flex-wrap: wrap; }
.card { flex: 1 1 160px; }
.card-label { font-size: 13px; color: #606266; }
.card-value { font-size: 26px; font-weight: 600; margin: 4px 0; }
.card-hint { font-size: 12px; color: #a8abb2; }
.row { margin-top: 12px; }
.block { margin-top: 12px; }
.sub-tip { color: #a8abb2; font-size: 12px; font-weight: 400; }
.reason-row { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.reason-name { width: 96px; font-size: 13px; color: #606266; }
.reason-bar { flex: 1; }
.reason-num { width: 32px; text-align: right; font-size: 13px; }
.empty { color: #a8abb2; font-size: 13px; padding: 12px 0; }
.gap-tip { margin-top: 10px; font-size: 12px; color: #909399; line-height: 1.6; }
.pager { margin-top: 10px; justify-content: flex-end; }
</style>

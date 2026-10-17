<template>
  <div class="admin-ai-usage">
    <a-page-header title="Token 流水" sub-title="查看 AI 调用的模型、使用者与 Token 消耗流向">
      <template #extra>
        <a-button type="primary" @click="refresh">
          <ReloadOutlined /> 刷新
        </a-button>
      </template>
    </a-page-header>

    <a-row :gutter="16" style="margin-top:16px">
      <a-col :span="6">
        <a-card :bordered="false" size="small">
          <a-statistic title="区间调用次数" :value="summary.totals.calls || 0" />
        </a-card>
      </a-col>
      <a-col :span="6">
        <a-card :bordered="false" size="small">
          <a-statistic title="区间 Token 总量" :value="summary.totals.totalTokens || 0" />
        </a-card>
      </a-col>
      <a-col :span="6">
        <a-card :bordered="false" size="small">
          <a-statistic title="估算成本 ($)" :value="summary.totals.totalCost || 0" :precision="5" />
        </a-card>
      </a-col>
      <a-col :span="6">
        <a-card :bordered="false" size="small">
          <a-statistic title="本月预算使用率" :value="summary.budget.usagePercent || 0" suffix="%" />
        </a-card>
      </a-col>
    </a-row>

    <a-card :bordered="false" style="margin-top:16px">
      <a-form layout="inline" class="filter-form">
        <a-form-item label="模型">
          <a-input v-model:value="filters.model" placeholder="如 deepseek-v4-pro"
                   allow-clear style="width:180px" />
        </a-form-item>
        <a-form-item label="阶段">
          <a-select v-model:value="filters.stage" placeholder="全部" allow-clear
                    style="width:170px" :options="stageOptions" />
        </a-form-item>
        <a-form-item label="使用者">
          <a-input v-model:value="filters.username" placeholder="用户名"
                   allow-clear style="width:140px" />
        </a-form-item>
        <a-form-item label="状态">
          <a-select v-model:value="filters.success" placeholder="全部" allow-clear
                    style="width:110px" :options="successOptions" />
        </a-form-item>
        <a-form-item label="时间">
          <a-range-picker v-model:value="filters.timeRange" show-time
                          value-format="YYYY-MM-DD HH:mm:ss" style="width:360px" />
        </a-form-item>
        <a-form-item>
          <a-button type="primary" @click="onSearch">查询</a-button>
          <a-button style="margin-left:8px" @click="onReset">重置</a-button>
        </a-form-item>
      </a-form>
    </a-card>

    <a-card :bordered="false" style="margin-top:16px">
      <a-table
        :columns="columns"
        :data-source="records"
        :loading="loading"
        :pagination="pagination"
        row-key="id"
        @change="onTableChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'createdAt'">
            {{ formatDate(record.createdAt) }}
          </template>
          <template v-else-if="column.key === 'username'">
            {{ record.username || '-' }}
          </template>
          <template v-else-if="column.key === 'stage'">
            <a-tag :color="stageColor(record.stage)">{{ stageLabel(record.stage) }}</a-tag>
          </template>
          <template v-else-if="column.key === 'projectId'">
            {{ record.projectId || '-' }}
          </template>
          <template v-else-if="column.key === 'cost'">
            {{ formatCost(record.cost) }}
          </template>
          <template v-else-if="column.key === 'success'">
            <a-tag :color="record.success ? 'green' : 'red'">
              {{ record.success ? '成功' : '失败' }}
            </a-tag>
            <a-tooltip v-if="record.errorMessage" :title="record.errorMessage">
              <InfoCircleOutlined style="color:#999;margin-left:4px" />
            </a-tooltip>
          </template>
          <template v-else-if="column.key === 'promptTokens' || column.key === 'completionTokens'
                                || column.key === 'totalTokens' || column.key === 'latencyMs'">
            {{ formatNumber(record[column.key]) }}
          </template>
        </template>
      </a-table>
    </a-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ReloadOutlined, InfoCircleOutlined } from '@ant-design/icons-vue'
import api from '../../api'

const STAGE_LABELS = {
  ARCHITECTURE_STORY: '架构叙事',
  PATTERN_ANALYSIS: '架构模式',
  ANTI_PATTERN: '反模式检测',
  CONSTITUTION_CHECK: '规约检查',
  IMPACT_ANALYSIS: '影响分析',
  CONTEXT_QA: '上下文问答',
  AGENTIC_SEARCH: '智能检索'
}

const STAGE_COLORS = {
  ARCHITECTURE_STORY: 'blue',
  PATTERN_ANALYSIS: 'purple',
  ANTI_PATTERN: 'orange',
  CONSTITUTION_CHECK: 'geekblue',
  IMPACT_ANALYSIS: 'cyan',
  CONTEXT_QA: 'green',
  AGENTIC_SEARCH: 'magenta'
}

const stageOptions = Object.entries(STAGE_LABELS).map(([value, label]) => ({ value, label }))
const successOptions = [
  { value: true, label: '成功' },
  { value: false, label: '失败' }
]

const loading = ref(false)
const records = ref([])
const summary = ref({ totals: {}, budget: {} })

const filters = reactive({
  model: '',
  stage: undefined,
  username: '',
  success: undefined,
  timeRange: null
})

const pagination = reactive({
  current: 1,
  pageSize: 20,
  total: 0,
  showSizeChanger: true,
  showTotal: (total) => `共 ${total} 条`
})

const columns = [
  { title: '调用时间', dataIndex: 'createdAt', key: 'createdAt', width: 165 },
  { title: '使用者', dataIndex: 'username', key: 'username', width: 110 },
  { title: '模型', dataIndex: 'model', key: 'model', width: 150 },
  { title: '阶段', dataIndex: 'stage', key: 'stage', width: 140 },
  { title: '项目', dataIndex: 'projectId', key: 'projectId', width: 70 },
  { title: 'Prompt', dataIndex: 'promptTokens', key: 'promptTokens', width: 90, align: 'right' },
  { title: 'Completion', dataIndex: 'completionTokens', key: 'completionTokens', width: 105, align: 'right' },
  { title: '总 Token', dataIndex: 'totalTokens', key: 'totalTokens', width: 100, align: 'right' },
  { title: '估算成本($)', dataIndex: 'cost', key: 'cost', width: 100, align: 'right' },
  { title: '延迟(ms)', dataIndex: 'latencyMs', key: 'latencyMs', width: 95, align: 'right' },
  { title: '状态', dataIndex: 'success', key: 'success', width: 90 }
]

function stageLabel(stage) {
  return STAGE_LABELS[stage] || stage || '-'
}

function stageColor(stage) {
  return STAGE_COLORS[stage] || 'default'
}

function formatDate(val) {
  if (!val) return '-'
  const d = new Date(val)
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

function formatNumber(val) {
  if (val === null || val === undefined) return '-'
  return Number(val).toLocaleString('zh-CN')
}

function formatCost(val) {
  if (val === null || val === undefined) return '-'
  return Number(val).toFixed(5)
}

function buildFilterParams() {
  const params = {}
  if (filters.model && filters.model.trim()) params.model = filters.model.trim()
  if (filters.stage) params.stage = filters.stage
  if (filters.username && filters.username.trim()) params.username = filters.username.trim()
  if (filters.success !== undefined && filters.success !== null) params.success = filters.success
  if (filters.timeRange && filters.timeRange.length === 2) {
    params.startTime = filters.timeRange[0]
    params.endTime = filters.timeRange[1]
  }
  return params
}

async function loadUsage() {
  loading.value = true
  try {
    const res = await api.get('/admin/ai-usage', {
      params: {
        page: pagination.current,
        size: pagination.pageSize,
        ...buildFilterParams()
      }
    })
    const data = res.data.data
    records.value = data.records || []
    pagination.total = data.total || 0
  } catch (e) {
    // error handled by interceptor
  } finally {
    loading.value = false
  }
}

async function loadSummary() {
  try {
    const res = await api.get('/admin/ai-usage/summary', { params: buildFilterParams() })
    const data = res.data.data || {}
    summary.value = {
      totals: data.totals || {},
      budget: data.budget || {}
    }
  } catch (e) {
    // error handled by interceptor
  }
}

function refresh() {
  loadUsage()
  loadSummary()
}

function onSearch() {
  pagination.current = 1
  refresh()
}

function onReset() {
  filters.model = ''
  filters.stage = undefined
  filters.username = ''
  filters.success = undefined
  filters.timeRange = null
  pagination.current = 1
  refresh()
}

function onTableChange(pag) {
  pagination.current = pag.current
  pagination.pageSize = pag.pageSize
  loadUsage()
}

onMounted(() => {
  refresh()
})
</script>

<style scoped>
.filter-form :deep(.ant-form-item) {
  margin-bottom: 8px;
}
</style>

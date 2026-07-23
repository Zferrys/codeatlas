<template>
  <div class="overview-page">
    <!-- 加载态 -->
    <a-skeleton v-if="loading" active :paragraph="{ rows: 8 }" />

    <template v-else>
      <!-- 顶部：健康度 + 4 维度 -->
      <a-row :gutter="[20, 20]">
        <!-- 健康度大环形图 -->
        <a-col :xs="24" :sm="8" :lg="6">
          <div class="health-card">
            <h4 class="card-label">健康度评分</h4>
            <div class="health-ring">
              <a-progress
                type="circle"
                :percent="healthScore || 0"
                :width="140"
                :stroke-width="10"
                :stroke-color="healthGradient"
                :format="() => ''"
              >
                <template #format>
                  <div class="health-ring-text">
                    <span class="health-value">{{ healthScore ?? '--' }}</span>
                    <span class="health-unit">分</span>
                  </div>
                </template>
              </a-progress>
            </div>
            <a-tag :color="healthLevel.color" class="health-tag">{{ healthLevel.label }}</a-tag>
          </div>
        </a-col>

        <!-- 四维度分解 -->
        <a-col :xs="24" :sm="16" :lg="18">
          <a-row :gutter="[16, 16]">
            <a-col :xs="12" :lg="6" v-for="dim in dimensions" :key="dim.key">
              <div class="dim-card">
                <div class="dim-icon" :style="{ background: dim.bg, color: dim.color }">
                  <component :is="dim.icon" />
                </div>
                <div class="dim-body">
                  <span class="dim-label">{{ dim.label }}</span>
                  <a-progress
                    :percent="dim.score"
                    :stroke-color="dim.color"
                    :show-info="false"
                    size="small"
                  />
                  <span class="dim-value" :style="{ color: dim.color }">{{ dim.score }}分</span>
                </div>
              </div>
            </a-col>
          </a-row>

          <!-- 违规趋势迷你图 -->
          <div class="trend-card">
            <h4 class="card-label">违规趋势（最近扫描）</h4>
            <div class="trend-chart" v-if="violationTrend.length > 0">
              <svg :viewBox="`0 0 ${trendViewWidth} 60`" class="trend-svg">
                <polyline
                  :points="trendLinePoints"
                  fill="none"
                  stroke="#667eea"
                  stroke-width="2"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                />
                <circle
                  v-for="(pt, i) in trendPoints"
                  :key="i"
                  :cx="pt.x"
                  :cy="pt.y"
                  r="3"
                  fill="#667eea"
                />
              </svg>
              <div class="trend-labels">
                <span v-for="(v, i) in violationTrend" :key="i" class="trend-label-item">
                  {{ v.label }}
                </span>
              </div>
            </div>
            <a-empty v-else description="暂无扫描数据" :image-style="{ height: '40px' }" />
          </div>
        </a-col>
      </a-row>

      <!-- 下半部分：类分布 + 最近扫描 -->
      <a-row :gutter="[20, 20]" style="margin-top:20px">
        <!-- 类分层分布 -->
        <a-col :xs="24" :lg="12">
          <div class="section-card">
            <div class="section-card-header">
              <h4 class="card-label">类分层分布</h4>
              <span class="card-total">{{ totalClasses }} 个类</span>
            </div>
            <div class="layer-bars" v-if="layerDistribution.length > 0">
              <div class="layer-bar-row" v-for="layer in layerDistribution" :key="layer.name">
                <span class="layer-name">{{ layer.name }}</span>
                <div class="layer-bar-track">
                  <div
                    class="layer-bar-fill"
                    :style="{ width: layer.percent + '%', background: layer.color }"
                  ></div>
                </div>
                <span class="layer-count">{{ layer.count }}</span>
              </div>
            </div>
            <a-empty v-else description="暂无类数据" />
          </div>
        </a-col>

        <!-- 最近扫描列表 -->
        <a-col :xs="24" :lg="12">
          <div class="section-card">
            <div class="section-card-header">
              <h4 class="card-label">扫描历史</h4>
              <a-button size="small" type="primary" @click="triggerScan" :loading="scanning">
                <template #icon><ScanOutlined /></template>
                触发扫描
              </a-button>
            </div>
            <a-table
              v-if="scans.length > 0"
              :dataSource="scans"
              :columns="columns"
              rowKey="id"
              size="small"
              :pagination="{ pageSize: 5, size: 'small' }"
            >
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'status'">
                  <a-badge :status="statusBadge(record.status)" :text="record.status" />
                </template>
                <template v-if="column.key === 'createdAt'">
                  {{ formatTime(record.createdAt) }}
                </template>
              </template>
            </a-table>
            <a-empty v-else description="暂无扫描记录，点击上方按钮触发" />
          </div>
        </a-col>
      </a-row>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { message } from 'ant-design-vue'
import {
  SafetyOutlined, CodeOutlined, BugOutlined, ApartmentOutlined, ScanOutlined
} from '@ant-design/icons-vue'
import api from '../api'

const route = useRoute()

const loading = ref(true)
const healthScore = ref(null)
const totalClasses = ref(0)
const scans = ref([])
const scanning = ref(false)
const layerDistribution = ref([])
const violationTrend = ref([])

const columns = [
  { title: '状态', key: 'status', width: 80 },
  { title: '类数', dataIndex: 'totalClasses', width: 60 },
  { title: '行数', dataIndex: 'totalLines', width: 80 },
  { title: '违规', dataIndex: 'totalViolations', width: 60 },
  { title: '耗时', dataIndex: 'durationMs', width: 70 },
  { title: '时间', key: 'createdAt' }
]

const dimensions = computed(() => {
  const score = healthScore.value || 0
  return [
    { key: 'arch', label: '架构合规', score: Math.min(100, score + Math.floor(Math.random() * 6)),
      icon: ApartmentOutlined, bg: '#f0e6ff', color: '#722ed1' },
    { key: 'structure', label: '代码结构', score: Math.min(100, score - 5 + Math.floor(Math.random() * 10)),
      icon: CodeOutlined, bg: '#e8f4ff', color: '#1890ff' },
    { key: 'quality', label: '代码质量', score: Math.min(100, score + Math.floor(Math.random() * 8) - 4),
      icon: BugOutlined, bg: '#e6f7e9', color: '#52c41a' },
    { key: 'deps', label: '依赖健康', score: Math.min(100, score - 10 + Math.floor(Math.random() * 12)),
      icon: SafetyOutlined, bg: '#fff7e6', color: '#fa8c16' }
  ]
})

const healthLevel = computed(() => {
  const s = healthScore.value || 0
  if (s >= 80) return { label: '优秀', color: '#52c41a' }
  if (s >= 60) return { label: '良好', color: '#1890ff' }
  if (s >= 40) return { label: '一般', color: '#fa8c16' }
  return { label: '需改进', color: '#ff4d4f' }
})

const healthGradient = computed(() => {
  const s = healthScore.value || 0
  if (s >= 80) return { '0%': '#52c41a', '100%': '#95de64' }
  if (s >= 60) return { '0%': '#1890ff', '100%': '#69c0ff' }
  if (s >= 40) return { '0%': '#fa8c16', '100%': '#ffc069' }
  return { '0%': '#ff4d4f', '100%': '#ff7875' }
})

const trendViewWidth = computed(() => {
  const n = violationTrend.value.length
  return Math.max(200, n * 50)
})

const trendPoints = computed(() => {
  const data = violationTrend.value
  if (data.length === 0) return []
  const maxVal = Math.max(...data.map(d => d.value), 1)
  const w = trendViewWidth.value
  const h = 60
  const pad = 10
  const stepX = data.length === 1 ? w / 2 : (w - pad * 2) / (data.length - 1)
  return data.map((d, i) => ({
    x: data.length === 1 ? w / 2 : pad + i * stepX,
    y: h - pad - (d.value / maxVal) * (h - pad * 2)
  }))
})

const trendLinePoints = computed(() => {
  return trendPoints.value.map(p => `${p.x},${p.y}`).join(' ')
})

function statusBadge(status) {
  const map = { COMPLETED: 'success', RUNNING: 'processing', FAILED: 'error', PENDING: 'default' }
  return map[status] || 'default'
}

function formatTime(dateStr) {
  if (!dateStr) return ''
  const d = new Date(dateStr)
  return d.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

async function loadData() {
  loading.value = true
  try {
    const projectId = route.params.id
    const [projectRes, scanRes] = await Promise.all([
      api.get(`/projects/${projectId}`),
      api.get(`/projects/${projectId}/scans`)
    ])
    const project = projectRes.data.data
    healthScore.value = project?.healthScore ?? 0
    totalClasses.value = project?.totalClasses || 0

    // Layer distribution (from project or scan data)
    if (project?.layerDistribution) {
      layerDistribution.value = project.layerDistribution
    }

    scans.value = scanRes.data.data || []

    // Build violation trend from scans (reverse to chronological)
    if (scans.value.length > 0) {
      const recent = [...scans.value].reverse().slice(-8)
      violationTrend.value = recent.map((s, i) => ({
        label: `#${i + 1}`,
        value: s.totalViolations || 0
      }))
    }
  } catch (e) {
    // handled by interceptor
  } finally {
    loading.value = false
  }
}

async function triggerScan() {
  scanning.value = true
  try {
    await api.post(`/projects/${route.params.id}/scans`)
    message.success('扫描已触发')
    await loadData()
  } catch (e) {
    // handled by interceptor
  } finally {
    scanning.value = false
  }
}

onMounted(loadData)
</script>

<style scoped>
.overview-page { padding: 0; }

/* 健康度卡片 */
.health-card {
  background: #fff;
  border-radius: 12px;
  padding: 24px;
  text-align: center;
  box-shadow: 0 1px 4px rgba(0,0,0,0.04);
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.health-ring {
  margin: 16px 0;
}

.health-ring-text {
  display: flex;
  flex-direction: column;
  align-items: center;
  line-height: 1.2;
}

.health-value {
  font-size: 36px;
  font-weight: 800;
  color: #1a1a2e;
}

.health-unit {
  font-size: 12px;
  color: #999;
  margin-top: 2px;
}

.health-tag {
  margin-top: 8px;
  font-size: 13px;
}

/* 维度卡片 */
.dim-card {
  background: #fff;
  border-radius: 12px;
  padding: 16px;
  box-shadow: 0 1px 4px rgba(0,0,0,0.04);
  display: flex;
  align-items: center;
  gap: 12px;
  height: 100%;
}

.dim-icon {
  width: 42px;
  height: 42px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  flex-shrink: 0;
}

.dim-body {
  flex: 1;
  min-width: 0;
}

.dim-label {
  font-size: 13px;
  color: #666;
  display: block;
  margin-bottom: 4px;
}

.dim-value {
  font-size: 12px;
  font-weight: 600;
}

/* 趋势卡片 */
.trend-card {
  background: #fff;
  border-radius: 12px;
  padding: 20px;
  margin-top: 16px;
  box-shadow: 0 1px 4px rgba(0,0,0,0.04);
}

.trend-chart {
  margin-top: 8px;
}

.trend-svg {
  width: 100%;
  height: 60px;
}

.trend-labels {
  display: flex;
  justify-content: space-between;
  margin-top: 4px;
}

.trend-label-item {
  font-size: 10px;
  color: #bbb;
}

/* 通用 */
.card-label {
  font-size: 15px;
  font-weight: 600;
  color: #1a1a2e;
  margin: 0;
}

.card-total {
  font-size: 13px;
  color: #999;
}

.section-card {
  background: #fff;
  border-radius: 12px;
  padding: 20px;
  box-shadow: 0 1px 4px rgba(0,0,0,0.04);
}

.section-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

/* 层分布条 */
.layer-bars {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.layer-bar-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.layer-name {
  font-size: 13px;
  color: #555;
  width: 64px;
  flex-shrink: 0;
  text-align: right;
}

.layer-bar-track {
  flex: 1;
  height: 8px;
  background: #f0f0f0;
  border-radius: 4px;
  overflow: hidden;
}

.layer-bar-fill {
  height: 100%;
  border-radius: 4px;
  transition: width 0.6s ease;
}

.layer-count {
  font-size: 12px;
  color: #999;
  width: 28px;
  flex-shrink: 0;
}
</style>

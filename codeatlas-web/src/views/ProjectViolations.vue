<template>
  <div class="violations-page">
    <!-- 加载态 -->
    <a-skeleton v-if="loading" active :paragraph="{ rows: 6 }" />

    <!-- 错误态 -->
    <div v-else-if="error" class="state-wrap">
      <a-result status="error" title="加载失败" :sub-title="error">
        <template #extra><a-button type="primary" @click="fetchViolations">重试</a-button></template>
      </a-result>
    </div>

    <!-- 空状态：区分"扫描进行中 / 从未扫描 / 扫描失败 / 已扫描无违规" -->
    <div v-else-if="violations.length === 0" class="state-wrap">
      <!-- 扫描进行中（本页触发或他处触发、刷新后仍运行中）：优先于其他分支，避免闪现"恭喜" -->
      <div v-if="scanRunning" class="never-scanned">
        <a-empty>
          <template #description>
            <p style="font-size: 15px;">扫描进行中</p>
            <p style="color: #999; font-size: 13px;">扫描完成后，宪法规则检查结果（违规项）会在这里列出</p>
          </template>
        </a-empty>
        <div class="scan-progress-card">
          <div class="progress-header">
            <span class="progress-stage">
              <a-badge :status="stageStatus(scanProgress.stage)" />
              {{ stageLabel(scanProgress.stage) }}
            </span>
            <span class="progress-pct">{{ scanProgress.progress }}%</span>
          </div>
          <a-progress
            :percent="scanProgress.progress"
            :status="scanProgress.stage === 'FAILED' ? 'exception' : 'active'"
            size="small"
          />
          <p class="progress-msg" v-if="scanProgress.message">{{ scanProgress.message }}</p>
        </div>
      </div>
      <div v-else-if="hasScanned === false || latestScanStatus === 'FAILED'" class="never-scanned">
        <a-empty>
          <template #description>
            <p style="font-size: 15px;">{{ hasScanned === false ? '该项目还没有扫描记录' : '最近一次扫描未成功完成' }}</p>
            <p style="color: #999; font-size: 13px;">
              {{ hasScanned === false ? '扫描后，宪法规则检查结果（违规项）会在这里列出' : '扫描失败时不会产生违规检查结果，请重新触发扫描' }}
            </p>
          </template>
        </a-empty>
        <a-button type="primary" :loading="triggering" @click="triggerScan">
          {{ hasScanned === false ? '立即扫描' : '重新扫描' }}
        </a-button>
      </div>
      <a-empty v-else-if="hasScanned !== null">
        <template #description>
          <span style="color: #52c41a; font-size: 15px;">恭喜！所有类均通过宪法规则检查</span>
        </template>
      </a-empty>
      <!-- 扫描历史尚在查询（hasScanned 未知）：spinner 兜底，避免帧级"恭喜"闪现 -->
      <div v-else class="state-wrap" style="text-align: center;">
        <a-spin />
      </div>
    </div>

    <!-- 违规表格 -->
    <div v-else class="violations-card">
      <div class="card-header">
        <div class="card-header-left">
          <h3 class="card-title">违规列表</h3>
          <span class="card-hint">点击违规行可跳转到类关系图谱中对应节点</span>
        </div>
        <a-space>
          <a-tag color="red">{{ blockerCount }} 阻断</a-tag>
          <a-tag color="orange">{{ errorCount }} 错误</a-tag>
          <a-tag color="gold">{{ warnCount }} 警告</a-tag>
        </a-space>
      </div>
      <a-table :data-source="violations" :pagination="{ pageSize: 20, showSizeChanger: true, showTotal: t => `共 ${t} 条` }"
        row-key="id" size="middle"
        :custom-row="record => ({ onClick: () => goToClassNode(record), style: { cursor: 'pointer' } })">
        <a-table-column title="严重度" data-index="severity" :width="90">
          <template #default="{ record }">
            <a-tag :color="severityColor(record.severity)">{{ severityLabel(record.severity) }}</a-tag>
          </template>
        </a-table-column>
        <a-table-column title="类" data-index="classFqn" ellipsis>
          <template #default="{ record }">
            <code class="class-fqn">{{ shortName(record.classFqn) }}</code>
            <div class="class-fqn-full">{{ record.classFqn }}</div>
          </template>
        </a-table-column>
        <a-table-column title="违规说明" data-index="message" ellipsis />
        <a-table-column title="建议" data-index="suggestion" ellipsis />
        <a-table-column title="状态" data-index="isResolved" :width="90">
          <template #default="{ record }">
            <a-badge :status="record.isResolved ? 'success' : 'error'"
              :text="record.isResolved ? '已解决' : '未解决'" />
          </template>
        </a-table-column>
        <a-table-column title="操作" :width="90">
          <template #default="{ record }">
            <a-button v-if="!record.isResolved" type="link" size="small"
              @click.stop="resolveViolation(record.id)">标记解决</a-button>
          </template>
        </a-table-column>
      </a-table>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onActivated, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import api from '../api'

const route = useRoute()
const router = useRouter()
const projectId = computed(() => route.params.id)

const loading = ref(false)
const error = ref(null)
const violations = ref([])
const hasScanned = ref(null)
const latestScanStatus = ref(null)
const triggering = ref(false)
const scanProgress = reactive({ stage: '', progress: 0, message: '' })
const scanning = computed(() => triggering.value || (scanProgress.stage !== '' && scanProgress.stage !== 'COMPLETED' && scanProgress.stage !== 'FAILED'))
// 页面加载/切回时扫描可能已在他处运行：状态为 RUNNING 即视为进行中（SSE 未到达前先以占位进度展示）
const scanRunning = computed(() => scanning.value || latestScanStatus.value === 'RUNNING')
let sseAbortController = null

const blockerCount = computed(() => violations.value.filter(v => v.severity === 'BLOCKER').length)
const errorCount = computed(() => violations.value.filter(v => v.severity === 'ERROR').length)
const warnCount = computed(() => violations.value.filter(v => v.severity === 'WARN').length)

const stageLabels = {
  PENDING: '准备中', CLONING: '克隆仓库', PARSING: '解析代码结构', RULES: '执行规则检查',
  AI: 'AI 分析', COMPLETED: '扫描完成', FAILED: '扫描失败'
}

function stageLabel(stage) { return stageLabels[stage] || stage || '准备中' }

function stageStatus(stage) {
  if (stage === 'COMPLETED') return 'success'
  if (stage === 'FAILED') return 'error'
  return 'processing'
}

let firstActivation = true
onMounted(() => {
  fetchViolations()
  checkScanHistory()
  subscribeScanProgress()
})
onActivated(() => {
  // keep-alive 下切回本页时刷新（首次激活已由 onMounted 加载，避免重复请求）
  if (firstActivation) {
    firstActivation = false
    return
  }
  fetchViolations()
  checkScanHistory()
})
onBeforeUnmount(() => {
  if (sseAbortController) {
    sseAbortController.abort()
    sseAbortController = null
  }
})

async function fetchViolations() {
  loading.value = true; error.value = null
  try {
    const res = await api.get(`/projects/${projectId.value}/violations`)
    violations.value = res.data.data?.records || []
  } catch (e) {
    error.value = e.response?.data?.message || '加载违规列表失败'
  } finally {
    loading.value = false
  }
}

async function checkScanHistory() {
  try {
    const res = await api.get(`/projects/${projectId.value}/scans`, { params: { page: 1, size: 1 } })
    const records = res.data.data?.records || []
    hasScanned.value = records.length > 0
    latestScanStatus.value = records[0]?.status || null
    // 扫描运行中：SSE 快照到达前先设占位进度，避免违规列表为空时闪现"恭喜"；
    // 非终态快照在任意 SSE 连接建立时都会回放，随后的真实进度会覆盖占位值。
    // 上一次扫描终态后连接会被服务端关闭，切回本页发现仍在运行时需要重订阅（带 scanId 以兼容终态回放）
    if (latestScanStatus.value === 'RUNNING') {
      if (!scanProgress.stage || scanProgress.stage === 'COMPLETED' || scanProgress.stage === 'FAILED') {
        scanProgress.stage = 'PENDING'
        scanProgress.progress = 1
        scanProgress.message = '扫描进行中，正在同步进度...'
      }
      if (!sseAbortController) {
        subscribeScanProgress(records[0]?.id)
      }
    }
  } catch (e) {
    // 查询失败时不误报"从未扫描"，回退到通用空态
    hasScanned.value = true
    latestScanStatus.value = null
  }
}

async function triggerScan() {
  triggering.value = true
  scanProgress.stage = ''
  scanProgress.progress = 0
  scanProgress.message = ''
  try {
    const res = await api.post(`/projects/${projectId.value}/scans`)
    subscribeScanProgress(res.data?.data?.id)
  } catch (e) {
    // 错误提示由响应拦截器统一弹出；可能因"已有扫描正在运行"被拒，重新同步状态进入进度态
    triggering.value = false
    checkScanHistory()
  }
}

async function resolveViolation(id) {
  try {
    await api.put(`/projects/${projectId.value}/violations/${id}/resolve`)
    const v = violations.value.find(item => item.id === id)
    if (v) v.isResolved = true
    message.success('已标记为已解决')
  } catch (e) {
    message.error('操作失败')
  }
}

function severityColor(sev) {
  const map = { 'BLOCKER': 'red', 'ERROR': 'orange', 'WARN': 'gold', 'INFO': 'blue' }
  return map[sev] || 'default'
}

function severityLabel(sev) {
  const map = { 'BLOCKER': '阻断', 'ERROR': '错误', 'WARN': '警告', 'INFO': '建议' }
  return map[sev] || sev
}

function shortName(fqn) {
  if (!fqn) return ''
  const idx = fqn.lastIndexOf('.')
  return idx > 0 ? fqn.substring(idx + 1) : fqn
}

function goToClassNode(record) {
  if (!record?.classFqn) return
  router.push({ path: `/project/${projectId.value}/map`, query: { focus: record.classFqn } })
}

async function subscribeScanProgress(scanId) {
  if (sseAbortController) {
    sseAbortController.abort()
    sseAbortController = null
  }
  const token = localStorage.getItem('codeatlas_token')
  sseAbortController = new AbortController()
  // 携带 scanId：若扫描在连接建立前就已失败/完成，服务端会回放这一次扫描的终态事件
  const progressUrl = scanId
    ? `/api/v1/projects/${projectId.value}/scans/progress?scanId=${scanId}`
    : `/api/v1/projects/${projectId.value}/scans/progress`
  try {
    const response = await fetch(progressUrl, {
      headers: { Authorization: `Bearer ${token}` },
      signal: sseAbortController.signal
    })
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''
      for (const line of lines) {
        if (line.startsWith('event:')) continue
        if (line.startsWith('data:')) {
          try {
            const data = JSON.parse(line.substring(5).trim())
            if (data.stage) {
              scanProgress.stage = data.stage
              scanProgress.progress = data.progress || 0
              scanProgress.message = data.message || ''
            }
            if (data.stage === 'COMPLETED' || data.stage === 'FAILED') {
              reader.cancel()
              const completed = data.stage === 'COMPLETED'
              if (completed) {
                message.success('扫描完成')
              } else {
                message.error('扫描失败: ' + (data.message || '未知错误'))
              }
              setTimeout(async () => {
                triggering.value = false
                sseAbortController = null
                await fetchViolations()
                await checkScanHistory()
              }, 800)
              return
            }
          } catch (e) {}
        }
      }
    }
  } catch (e) {
    if (e.name !== 'AbortError') {
      // SSE 连接失败：复位状态并刷新一次，避免按钮卡在加载态（进度显示属非关键功能）
      triggering.value = false
      sseAbortController = null
      fetchViolations()
    }
  }
}
</script>

<style scoped>
.violations-page { padding: 0; }
.state-wrap { padding: 40px 0; }

.never-scanned { text-align: center; }

.scan-progress-card {
  max-width: 420px;
  margin: 0 auto;
  text-align: left;
  background: #fff;
  border-radius: 10px;
  padding: 16px 20px;
  box-shadow: 0 1px 4px rgba(0,0,0,0.05);
}

.progress-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.progress-stage {
  font-size: 14px;
  font-weight: 500;
  color: #333;
  display: flex;
  align-items: center;
  gap: 6px;
}

.progress-pct { font-size: 13px; color: #1677ff; font-weight: 600; }
.progress-msg { color: #999; font-size: 12px; margin: 8px 0 0; }

.violations-card {
  background: #fff;
  border-radius: 12px;
  padding: 20px;
  box-shadow: 0 1px 4px rgba(0,0,0,0.04);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.card-header-left {
  display: flex;
  align-items: baseline;
  gap: 12px;
}

.card-hint {
  font-size: 12px;
  color: #999;
}

.card-title {
  font-size: 16px;
  font-weight: 600;
  color: #1a1a2e;
  margin: 0;
}

.class-fqn {
  font-size: 13px;
  color: #1677ff;
  background: #f0f5ff;
  padding: 1px 6px;
  border-radius: 3px;
}

.class-fqn-full {
  font-size: 11px;
  color: #999;
  margin-top: 2px;
}
</style>

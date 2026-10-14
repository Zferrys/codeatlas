<template>
  <div class="report-panel">
    <a-spin :spinning="loading" tip="正在加载报告...">
      <!-- AI 生成中（尚无报告时整页等待） -->
      <div v-if="aiProcessing" class="report-processing">
        <a-spin>
          <template #tip>
            <span style="font-size: 15px;">AI 正在生成{{ title }}，请稍候...</span>
          </template>
        </a-spin>
        <p class="processing-hint">扫描完成后 AI 会自动生成，通常需要 1-3 分钟</p>
      </div>

      <div v-else-if="!loading && error" class="report-error">
        <a-result status="error" title="加载失败" :sub-title="error">
          <template #extra>
            <a-button type="primary" @click="fetchReport">重试</a-button>
          </template>
        </a-result>
      </div>

      <div v-else-if="!loading && !report" class="report-empty">
        <a-empty :description="'暂无' + title">
          <template #description>
            <p>{{ description }}</p>
          </template>
          <a-button type="primary" :loading="generating" @click="generate">
            {{ generating ? 'AI 分析中（约需 30–90 秒）...' : '立即生成' }}
          </a-button>
          <div v-if="generating" class="report-task-progress">
            <a-spin size="small" />
            <span class="task-stage">{{ activeTask?.stage || 'AI 正在生成报告…' }}</span>
            <span class="task-elapsed">已等待 {{ activeTask?.elapsedSec || 0 }} 秒</span>
            <a-button size="small" danger @click="cancelActiveAiTask">终止</a-button>
          </div>
        </a-empty>
      </div>

      <div v-else-if="report" class="report-content">
        <a-page-header :title="report.title || title" :sub-title="`AI 生成 · ${formatDate(report.createdAt)}`">
          <template #tags>
            <a-tag color="blue">{{ title }}</a-tag>
            <a-tag v-if="generating || regenerating" color="processing">AI 重新生成中...</a-tag>
            <a-tag v-if="report.confidence" :color="report.confidence >= 0.85 ? 'green' : 'orange'">
              置信度 {{ (report.confidence * 100).toFixed(0) }}%
            </a-tag>
          </template>
          <template #extra>
            <a-space>
              <a-dropdown :disabled="!report || generating">
                <a-button size="small" :disabled="!report || generating">
                  <template #icon><DownloadOutlined /></template>
                  导出
                </a-button>
                <template #overlay>
                  <a-menu @click="handleExport">
                    <a-menu-item key="pdf">
                      <FilePdfOutlined /> PDF 格式
                    </a-menu-item>
                    <a-menu-item key="html">
                      <FileTextOutlined /> HTML 格式
                    </a-menu-item>
                  </a-menu>
                </template>
              </a-dropdown>
              <a-button type="primary" size="small" :loading="generating" @click="generate">
                <template #icon><ReloadOutlined /></template>
                {{ generating ? 'AI 分析中...' : '重新生成' }}
              </a-button>
            </a-space>
          </template>
        </a-page-header>
        <div v-if="generating" class="report-task-progress">
          <a-spin size="small" />
          <span class="task-stage">{{ activeTask?.stage || 'AI 正在重新生成报告…' }}</span>
          <span class="task-elapsed">已等待 {{ activeTask?.elapsedSec || 0 }} 秒</span>
          <a-button size="small" danger @click="cancelActiveAiTask">终止</a-button>
        </div>
        <p class="report-hint">{{ description }}</p>
        <div class="markdown-body" v-html="renderMarkdown(report.content || '')"></div>
      </div>
    </a-spin>
  </div>
</template>

<script setup>
import { ref, watch, onMounted, onActivated, onBeforeUnmount } from 'vue'
import { message } from 'ant-design-vue'
import { ReloadOutlined, DownloadOutlined, FilePdfOutlined, FileTextOutlined } from '@ant-design/icons-vue'
import api, { handleUnauthorized } from '../api'
import { renderMarkdown } from '../utils/markdown'
import { runAiTask, AI_TASK_CANCELLED, cancelActiveAiTask, activeTask } from '../composables/useAiTask'

const props = defineProps({
  projectId: { type: String, required: true },
  type: { type: String, required: true },
  kind: { type: String, required: true },
  title: { type: String, required: true },
  description: { type: String, default: '' },
  autoPoll: { type: Boolean, default: false }
})

const loading = ref(false)
const error = ref(null)
const report = ref(null)
const generating = ref(false)
const aiProcessing = ref(false)
const regenerating = ref(false)
let pollTimer = null

watch(() => props.projectId, () => {
  report.value = null
  regenerating.value = false
  aiProcessing.value = false
  stopPolling()
  init()
})

let firstActivation = true
onMounted(init)
onActivated(() => {
  if (firstActivation) {
    firstActivation = false
    return
  }
  init()
})
onBeforeUnmount(() => {
  stopPolling()
  // 组件卸载（如切换报告 tab）时仍在生成：同步终止任务，避免出现看不见进度的后台任务
  if (generating.value) cancelActiveAiTask()
})

async function init() {
  await fetchReport()
  if (!props.autoPoll) return
  // 架构解读由扫描自动生成：扫描的 AI 阶段运行中时进入等待/轮询
  try {
    const res = await api.get(`/projects/${props.projectId}/scans/status`)
    const data = res.data.data
    if (data && data.aiAnalysisRunning) {
      if (report.value) {
        regenerating.value = true
      } else {
        aiProcessing.value = true
      }
      startPolling()
    }
  } catch (e) {
    // 状态查询失败不影响报告展示
  }
}

function startPolling() {
  stopPolling()
  const baseId = report.value?.id || null
  pollTimer = setInterval(async () => {
    try {
      const res = await api.get(`/projects/${props.projectId}/insights`, {
        params: { type: props.type, page: 1, size: 1 }
      })
      const list = res.data.data?.records || []
      if (list.length > 0 && list[0].id !== baseId) {
        report.value = list[0]
        aiProcessing.value = false
        regenerating.value = false
        stopPolling()
        return
      }
      const statusRes = await api.get(`/projects/${props.projectId}/scans/status`)
      if (!statusRes.data.data?.aiAnalysisRunning) {
        // AI 阶段已结束但仍无新报告（如分析被跳过）：停止等待，回到常规展示
        aiProcessing.value = false
        regenerating.value = false
        stopPolling()
      }
    } catch (e) {
      // 静默重试
    }
  }, 5000)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

async function fetchReport() {
  loading.value = true
  error.value = null
  try {
    const res = await api.get(`/projects/${props.projectId}/insights`, {
      params: { type: props.type, page: 1, size: 1 }
    })
    const list = res.data.data?.records || []
    report.value = list.length > 0 ? list[0] : null
  } catch (e) {
    error.value = e.response?.data?.message || '加载报告失败'
  } finally {
    loading.value = false
  }
}

async function generate() {
  generating.value = true
  try {
    const data = await runAiTask({
      projectId: props.projectId,
      label: props.title,
      execute: ({ taskId, signal }) =>
        api
          .post(`/projects/${props.projectId}/ai/${props.kind}`, null, {
            headers: { 'X-Ai-Task-Id': taskId },
            signal
          })
          .then(res => res.data)
    })
    if (data === AI_TASK_CANCELLED) return
    if (data.code === 200) {
      message.success(props.title + '生成完成')
      await fetchReport()
    } else {
      message.error(data.message || 'AI 分析失败')
    }
  } catch (e) {
    // 错误提示由响应拦截器统一弹出，此处不再重复
  } finally {
    generating.value = false
  }
}

async function handleExport({ key }) {
  if (!report.value) return
  try {
    const token = localStorage.getItem('codeatlas_token')
    const response = await fetch(
      `/api/v1/projects/${props.projectId}/insights/${props.type}/export?format=${key}`,
      { headers: { Authorization: `Bearer ${token}` } }
    )
    if (response.status === 401) {
      handleUnauthorized()
      return
    }
    if (!response.ok) {
      message.error('导出失败')
      return
    }
    const blob = await response.blob()
    const url = window.URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `codeatlas-${props.type.toLowerCase()}.${key}`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    window.URL.revokeObjectURL(url)
    message.success('报告已下载')
  } catch (e) {
    message.error('导出失败: ' + (e.message || '网络错误'))
  }
}

function formatDate(date) {
  if (!date) return ''
  const d = new Date(date)
  return d.toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}
</script>

<style scoped>
.report-panel { min-height: 200px; }
.report-processing { padding: 60px 0; text-align: center; }
.processing-hint { color: #999; font-size: 13px; margin-top: 20px; }
.report-error, .report-empty { padding: 40px 0; }
.report-task-progress {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 12px 0 16px;
  padding: 12px 14px;
  background: #fafafa;
  border-radius: 8px;
}
.report-empty .report-task-progress {
  max-width: 460px;
  margin: 16px auto 0;
}
.task-stage { flex: 1; min-width: 0; font-size: 13px; color: #333; text-align: left; }
.task-elapsed { font-size: 12px; color: #999; white-space: nowrap; }
.report-content { background: #fff; border-radius: 8px; padding: 24px 32px 48px; }
.report-hint { color: #999; font-size: 13px; margin: 0 0 20px; }
.markdown-body { line-height: 1.9; color: #333; font-size: 15px; }
.markdown-body :deep(h1) { font-size: 24px; border-bottom: 1px solid #eee; padding-bottom: 12px; }
.markdown-body :deep(h2) { font-size: 20px; margin-top: 32px; }
.markdown-body :deep(h3) { font-size: 16px; margin-top: 24px; }
.markdown-body :deep(h4) { font-size: 15px; margin-top: 20px; }
.markdown-body :deep(code) { background: #f5f5f5; padding: 2px 6px; border-radius: 3px; font-size: 13px; }
.markdown-body :deep(pre) { background: #f5f5f5; padding: 16px; border-radius: 6px; overflow-x: auto; }
.markdown-body :deep(blockquote) { border-left: 3px solid #1890ff; padding: 4px 16px; margin: 16px 0; color: #666; background: #fafafa; }
.markdown-body :deep(table) { border-collapse: collapse; margin: 16px 0; width: 100%; }
.markdown-body :deep(th), .markdown-body :deep(td) { border: 1px solid #e8e8e8; padding: 8px 16px; font-size: 14px; }
.markdown-body :deep(th) { background: #fafafa; font-weight: 600; }
.markdown-body :deep(ul), .markdown-body :deep(ol) { padding-left: 24px; }
.markdown-body :deep(li) { margin: 4px 0; }
.markdown-body :deep(strong) { font-weight: 600; }
</style>

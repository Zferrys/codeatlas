/**
 * AI 任务单例状态机：SSE 实时进度 + 主动终止。
 *
 * 全局同一时间只允许一个 AI 任务（问答 / 影响模拟 / 报告生成共用），
 * 冲突时返回 AI_TASK_CANCELLED 哨兵并提示。终止语义：前端断开 SSE 连接
 * 并中断 POST；服务端在检查点感知断开后立即停止、丢弃结果，已消耗 token 照常落审计。
 */
import { ref } from 'vue'
import { message } from 'ant-design-vue'

/** runAiTask 的哨兵返回值：任务被终止或与进行中任务冲突（调用方直接早退） */
export const AI_TASK_CANCELLED = Symbol('AI_TASK_CANCELLED')

/** 当前进行中的任务；仅供模板展示（{ id, label, stage, elapsedSec }），勿在外部修改 */
export const activeTask = ref(null)

let cancelRequested = false
let postAbortController = null
let sseAbortController = null
let elapsedTimer = null
let beforeUnloadHandler = null

function generateTaskId() {
  // 生产为 http（非安全上下文），crypto.randomUUID 不存在，需降级；格式满足服务端 [A-Za-z0-9_-]{8,64}
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return 'aitask-' + crypto.randomUUID()
  }
  return 'aitask-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 12)
}

export function hasActiveAiTask() {
  return activeTask.value !== null
}

/** 终止当前任务：立即断开 SSE（服务端在检查点感知）并中断 POST；收尾由 runAiTask 统一完成 */
export function cancelActiveAiTask() {
  if (!activeTask.value) return
  cancelRequested = true
  activeTask.value.stage = '正在终止…'
  if (sseAbortController) sseAbortController.abort()
  if (postAbortController) postAbortController.abort()
}

/**
 * 运行一次 AI 任务。
 * @param {Object} options
 * @param {string|number} options.projectId 项目 ID（SSE 订阅路径用）
 * @param {string} options.label 任务名（进度卡展示）
 * @param {Function} options.execute 执行体：({ taskId, signal }) => Promise<结果>；
 *   taskId 放请求头 X-Ai-Task-Id，signal 放 axios config.signal
 * @returns {Promise<结果|AI_TASK_CANCELLED 哨兵>}
 */
export async function runAiTask({ projectId, label, execute }) {
  if (activeTask.value) {
    message.warning('已有 AI 任务正在进行中，请等待完成或先终止它')
    return AI_TASK_CANCELLED
  }
  const taskId = generateTaskId()
  cancelRequested = false
  postAbortController = new AbortController()
  sseAbortController = new AbortController()
  activeTask.value = { id: taskId, label, stage: '正在连接 AI 服务…', startedAt: Date.now(), elapsedSec: 0 }

  elapsedTimer = setInterval(() => {
    if (activeTask.value?.id === taskId) {
      activeTask.value.elapsedSec = Math.floor((Date.now() - activeTask.value.startedAt) / 1000)
    }
  }, 1000)
  beforeUnloadHandler = (e) => {
    e.preventDefault()
    e.returnValue = ''
  }
  window.addEventListener('beforeunload', beforeUnloadHandler)

  subscribeProgress(projectId, taskId, sseAbortController.signal)

  try {
    return await execute({ taskId, signal: postAbortController.signal })
  } catch (e) {
    if (cancelRequested || e?.code === 'ERR_CANCELED' || e?.name === 'CanceledError') {
      message.info('已终止本次任务')
      return AI_TASK_CANCELLED
    }
    throw e
  } finally {
    cleanupAiTask()
  }
}

function cleanupAiTask() {
  if (elapsedTimer) {
    clearInterval(elapsedTimer)
    elapsedTimer = null
  }
  if (beforeUnloadHandler) {
    window.removeEventListener('beforeunload', beforeUnloadHandler)
    beforeUnloadHandler = null
  }
  if (sseAbortController) {
    sseAbortController.abort()
    sseAbortController = null
  }
  postAbortController = null
  cancelRequested = false
  activeTask.value = null
}

/** SSE 旁路订阅：fetch + ReadableStream（EventSource 无法携带 Authorization 头） */
async function subscribeProgress(projectId, taskId, signal) {
  const token = localStorage.getItem('codeatlas_token')
  try {
    const response = await fetch(
      `/api/v1/projects/${projectId}/ai/ai-progress?taskId=${encodeURIComponent(taskId)}`,
      { headers: { Authorization: `Bearer ${token}` }, signal }
    )
    if (!response.ok || !response.body) return
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
        if (!line.startsWith('data:')) continue
        try {
          const data = JSON.parse(line.substring(5).trim())
          if (data.stage === 'DONE') {
            reader.cancel()
            return
          }
          if (activeTask.value?.id !== taskId) continue
          if (data.message) {
            activeTask.value.stage = data.message
          } else if (data.taskId) {
            activeTask.value.stage = 'AI 服务已连接，处理中…'
          }
        } catch (e) {
          // 单条事件解析失败不影响后续
        }
      }
    }
  } catch (e) {
    // AbortError（正常终止）或网络异常：进度展示属非关键功能，静默降级
  }
}

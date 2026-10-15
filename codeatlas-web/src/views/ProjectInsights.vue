<template>
  <div class="qa-page">
    <div class="qa-container">
      <!-- 提问卡 -->
      <a-card class="qa-ask-card" :bordered="false">
        <template #title>
          <span class="card-title"><QuestionCircleOutlined /> 向代码库提问</span>
        </template>
        <p class="ask-hint">
          AI 会自动搜索代码库中的相关类来回答问题，并对回答做证据校验。问题中包含关键词（功能名、类名）效果更好。
        </p>
        <a-textarea
          v-model:value="question"
          placeholder="例如：支付相关的代码有哪些？项目中最核心的 Service 类是哪个？数据持久化用了哪些技术？"
          :rows="3"
          :maxlength="500"
          show-count
          @pressEnter="onPressEnter"
        />
        <div class="ask-actions">
          <a-button type="primary" :loading="asking" :disabled="asking || !question.trim()" @click="ask">
            {{ asking ? 'AI 搜索与分析中...' : '提问' }}
          </a-button>
          <span class="ask-tip">Enter 发送，Shift+Enter 换行 · 通常需要 10–60 秒</span>
        </div>

        <div v-if="asking" class="answer-loading">
          <a-spin size="small" />
          <div class="progress-body">
            <div class="progress-stage">{{ activeTask?.stage || 'AI 正在搜索代码库并组织回答…' }}</div>
            <div class="progress-meta">{{ activeTask?.label || '全局问答' }} · 已等待 {{ activeTask?.elapsedSec || 0 }} 秒</div>
          </div>
          <a-button size="small" danger @click="cancelActiveAiTask">终止</a-button>
        </div>
        <div v-else-if="answer" class="answer-box">
          <div class="markdown-body" v-html="renderMarkdown(answer)"></div>
          <div v-if="askVerify" class="verify-line">
            <a-tooltip :title="askVerify.detail">
              <a-tag :color="askVerify.color" size="small">{{ askVerify.text }}</a-tag>
            </a-tooltip>
            <span class="verify-saved">回答已保存到下方对话历史</span>
          </div>
        </div>
      </a-card>

      <!-- 统一对话历史（全局问答 / 类问答 / 影响模拟） -->
      <a-card class="qa-history-card" :bordered="false">
        <template #title>
          <div class="history-head">
            <span class="card-title"><HistoryOutlined /> 对话历史</span>
            <a-radio-group v-model:value="historyFilter" size="small" button-style="solid">
              <a-radio-button value="all">全部</a-radio-button>
              <a-radio-button value="GLOBAL">全局问答</a-radio-button>
              <a-radio-button value="CLASS">类问答</a-radio-button>
              <a-radio-button value="IMPACT">影响模拟</a-radio-button>
            </a-radio-group>
          </div>
        </template>
        <template #extra>
          <span class="history-count">{{ filteredHistory.length }} / {{ history.length }} 条</span>
        </template>

        <a-skeleton v-if="historyLoading" active :paragraph="{ rows: 3 }" />
        <a-empty v-else-if="filteredHistory.length === 0" description="暂无对话历史">
          <template #description>
            <p v-if="history.length === 0">在智能问答提问、或在类关系图谱中做影响模拟后，记录会自动保存在这里统一管理</p>
            <p v-else>当前筛选下没有记录</p>
          </template>
        </a-empty>
        <div v-else class="history-list">
          <div v-for="item in filteredHistory" :key="item.id" class="history-item">
            <div class="history-item-main" @click="openHistory(item)">
              <div class="history-item-top">
                <a-tag :color="scopeColor(item.scope)" size="small">{{ scopeLabel(item.scope) }}</a-tag>
                <a-tag v-if="item.contextFqn" color="purple" size="small">{{ shortName(item.contextFqn) }}</a-tag>
                <span class="history-question">{{ item.question }}</span>
              </div>
              <div class="history-preview">{{ previewText(item.answer) }}</div>
            </div>
            <div class="history-item-side">
              <span class="history-time">{{ formatDate(item.createdAt) }}</span>
              <a-button type="text" size="small" @click.stop="openHistory(item)">查看</a-button>
              <a-popconfirm title="删除这条历史记录？" ok-text="删除" cancel-text="取消" @confirm="removeHistory(item.id)">
                <a-button type="text" size="small" danger @click.stop>
                  <template #icon><DeleteOutlined /></template>
                </a-button>
              </a-popconfirm>
            </div>
          </div>
        </div>
      </a-card>
    </div>

    <!-- 历史详情弹窗 -->
    <a-modal
      v-model:open="detailVisible"
      :title="detailItem?.question || '历史详情'"
      width="760px"
      :footer="null"
    >
      <div v-if="detailItem" class="history-detail">
        <div class="detail-meta">
          <a-tag :color="scopeColor(detailItem.scope)">{{ scopeLabel(detailItem.scope) }}</a-tag>
          <a-tag v-if="detailItem.contextFqn" color="purple">{{ detailItem.contextFqn }}</a-tag>
          <span class="detail-time">{{ formatDate(detailItem.createdAt) }}</span>
        </div>
        <div v-if="detailImpactSnapshot" class="impact-snapshot">
          变更类型：{{ detailImpactSnapshot.changeType || '—' }}
          <span class="snapshot-sep">|</span>
          目标方法：{{ detailImpactSnapshot.targetMethod || '—' }}
          <span class="snapshot-sep">|</span>
          影响类数：{{ detailImpactSnapshot.totalImpacted || 0 }}
        </div>
        <div v-if="detailVerify" class="verify-line">
          <a-tooltip :title="detailVerify.detail">
            <a-tag :color="detailVerify.color" size="small">{{ detailVerify.text }}</a-tag>
          </a-tooltip>
        </div>
        <div class="markdown-body" v-html="renderMarkdown(detailItem.answer || '')"></div>
      </div>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onActivated } from 'vue'
import { useRoute } from 'vue-router'
import { message } from 'ant-design-vue'
import { QuestionCircleOutlined, HistoryOutlined, DeleteOutlined } from '@ant-design/icons-vue'
import api from '../api'
import { renderMarkdown } from '../utils/markdown'
import { runAiTask, AI_TASK_CANCELLED, cancelActiveAiTask, activeTask } from '../composables/useAiTask'

const route = useRoute()
const projectId = computed(() => route.params.id)

const question = ref('')
const asking = ref(false)
const answer = ref('')
const verification = ref(null)

const history = ref([])
const historyLoading = ref(false)
const historyFilter = ref('all')

const detailVisible = ref(false)
const detailItem = ref(null)

watch(projectId, () => {
  // 离开项目路由时 route.params.id 变为 undefined 也会触发 watch，
  // 不校验会以 projectId=undefined 请求历史接口（后端 400 并产生前端错误遥测）
  if (!projectId.value) return
  answer.value = ''
  verification.value = null
  detailVisible.value = false
  detailItem.value = null
  loadHistory()
})

let firstActivation = true
onMounted(loadHistory)
onActivated(() => {
  // keep-alive 下切回本页时刷新历史；首次激活已由 onMounted 加载，避免重复请求
  if (firstActivation) {
    firstActivation = false
    return
  }
  loadHistory()
})

async function ask() {
  const q = question.value.trim()
  if (!q) {
    message.warning('请输入问题')
    return
  }
  asking.value = true
  answer.value = ''
  verification.value = null
  try {
    const data = await runAiTask({
      projectId: projectId.value,
      label: '全局问答',
      execute: ({ taskId, signal }) =>
        api
          .post(
            `/projects/${projectId.value}/ai/context-qa`,
            { question: q },
            { headers: { 'X-Ai-Task-Id': taskId }, signal }
          )
          .then(res => res.data.data)
    })
    if (data === AI_TASK_CANCELLED) return
    answer.value = data?.answer || '未获取到回答'
    verification.value = data?.verification || null
    await loadHistory()
  } catch (e) {
    // 错误提示由响应拦截器统一弹出，此处不再重复
  } finally {
    asking.value = false
  }
}

function onPressEnter(e) {
  if (!e.shiftKey) {
    e.preventDefault()
    if (!asking.value) ask()
  }
}

async function loadHistory() {
  historyLoading.value = true
  try {
    const [globalRes, classRes, impactRes] = await Promise.all([
      api.get(`/projects/${projectId.value}/chat-history`, { params: { scope: 'GLOBAL' } }),
      api.get(`/projects/${projectId.value}/chat-history`, { params: { scope: 'CLASS' } }),
      api.get(`/projects/${projectId.value}/chat-history`, { params: { scope: 'IMPACT' } })
    ])
    const items = [
      ...(globalRes.data.data || []),
      ...(classRes.data.data || []),
      ...(impactRes.data.data || [])
    ]
    items.sort((a, b) => new Date(b.createdAt || 0) - new Date(a.createdAt || 0))
    history.value = items
  } catch (e) {
    history.value = []
  } finally {
    historyLoading.value = false
  }
}

function openHistory(item) {
  detailItem.value = item
  detailVisible.value = true
}

async function removeHistory(id) {
  try {
    await api.delete(`/projects/${projectId.value}/chat-history/${id}`)
    message.success('已删除')
    if (detailItem.value?.id === id) {
      detailVisible.value = false
      detailItem.value = null
    }
    await loadHistory()
  } catch (e) {
    // 错误提示由响应拦截器统一弹出，此处不再重复
  }
}

const filteredHistory = computed(() => {
  if (historyFilter.value === 'all') return history.value
  return history.value.filter(i => i.scope === historyFilter.value)
})

const askVerify = computed(() => verifySummary(verification.value))
const detailVerify = computed(() => (detailItem.value ? verifySummary(detailItem.value.verification) : null))
const detailImpactSnapshot = computed(() => {
  if (!detailItem.value || detailItem.value.scope !== 'IMPACT') return null
  return parseJson(detailItem.value.verification)
})

function parseJson(str) {
  if (!str) return null
  try {
    return JSON.parse(str)
  } catch (e) {
    return null
  }
}

// QA 记录带证据校验字段（unsourced/relationMismatches 等）；IMPACT 的 verification 列存的是结果快照，不算校验数据
function isQaVerification(v) {
  return !!v && ('unsourced' in v || 'relationMismatches' in v || 'corrected' in v || 'classRefsChecked' in v)
}

function verifySummary(v) {
  const parsed = typeof v === 'string' ? parseJson(v) : v
  if (!isQaVerification(parsed)) return null
  const issues = (parsed.unsourced?.length || 0) + (parsed.relationMismatches?.length || 0)
  let text
  let color
  if (parsed.corrected) {
    text = '证据校验：已自动修正'
    color = 'blue'
  } else if (issues > 0) {
    text = `证据校验：${issues} 项异常`
    color = 'orange'
  } else {
    text = '证据校验通过'
    color = 'green'
  }
  const parts = []
  if (parsed.unsourced?.length) parts.push('未溯源引用: ' + parsed.unsourced.join('、'))
  if (parsed.relationMismatches?.length) parts.push('关系声明存疑: ' + parsed.relationMismatches.join('、'))
  if (!parts.length) {
    parts.push(`已校验 ${parsed.classRefsChecked || 0} 个类引用、${parsed.relationClaimsChecked || 0} 条关系声明，均通过`)
  }
  return { text, color, detail: parts.join('；') }
}

function scopeLabel(scope) {
  return { GLOBAL: '全局问答', CLASS: '类问答', IMPACT: '影响模拟' }[scope] || scope
}

function scopeColor(scope) {
  return { GLOBAL: 'blue', CLASS: 'purple', IMPACT: 'orange' }[scope] || 'default'
}

function shortName(fqn) {
  if (!fqn) return ''
  const parts = String(fqn).split('.')
  return parts[parts.length - 1]
}

function previewText(content) {
  if (!content) return ''
  const text = content
    .replace(/<[^>]*>/g, '')
    .replace(/[#*`>_{}[\]()!|-]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
  return text.substring(0, 120) + (text.length > 120 ? '...' : '')
}

function formatDate(t) {
  if (!t) return ''
  const d = new Date(t)
  return d.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}
</script>

<style scoped>
.qa-page { padding: 0; }
.qa-container { max-width: 960px; margin: 0 auto; }
.qa-ask-card, .qa-history-card { border-radius: 12px; margin-bottom: 16px; }
.card-title {
  font-size: 15px;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.ask-hint { color: #888; font-size: 13px; margin-bottom: 12px; }
.ask-actions { display: flex; align-items: center; gap: 12px; margin-top: 12px; }
.ask-tip { color: #bbb; font-size: 12px; }
.answer-loading {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 20px;
  padding: 20px;
  background: #fafafa;
  border-radius: 8px;
  color: #888;
  font-size: 13px;
}
.progress-body { flex: 1; min-width: 0; }
.progress-stage { color: #333; font-size: 13px; margin-bottom: 4px; }
.progress-meta { color: #999; font-size: 12px; }
.answer-box {
  margin-top: 20px;
  padding: 20px 24px;
  background: #fafafa;
  border-radius: 8px;
  border: 1px solid #f0f0f0;
}
.verify-line {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed #e8e8e8;
}
.verify-saved { color: #bbb; font-size: 12px; }
.history-head { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; }
.history-count { color: #999; font-size: 13px; }
.history-list { display: flex; flex-direction: column; }
.history-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 8px;
  border-bottom: 1px solid #f5f5f5;
  transition: background 0.15s;
}
.history-item:hover { background: #fafafa; }
.history-item:last-child { border-bottom: none; }
.history-item-main { flex: 1; min-width: 0; cursor: pointer; }
.history-item-top {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}
.history-question {
  font-size: 14px;
  font-weight: 500;
  color: #333;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.history-preview {
  font-size: 12px;
  color: #999;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.history-item-side { display: flex; align-items: center; gap: 4px; flex-shrink: 0; }
.history-time { font-size: 12px; color: #bbb; margin-right: 4px; }
.history-detail .detail-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.detail-time { color: #bbb; font-size: 12px; }
.impact-snapshot {
  font-size: 13px;
  color: #666;
  background: #fafafa;
  border-radius: 6px;
  padding: 8px 12px;
  margin-bottom: 12px;
}
.snapshot-sep { color: #ddd; margin: 0 8px; }
.markdown-body { line-height: 1.8; font-size: 14px; color: #333; }
.markdown-body :deep(h1) { font-size: 22px; font-weight: 600; margin-top: 24px; }
.markdown-body :deep(h2) { font-size: 18px; font-weight: 600; margin-top: 24px; color: #1a1a2e; }
.markdown-body :deep(h3) { font-size: 15px; margin-top: 20px; }
.markdown-body :deep(p) { margin: 8px 0; }
.markdown-body :deep(ul), .markdown-body :deep(ol) { padding-left: 20px; }
.markdown-body :deep(li) { margin: 4px 0; }
.markdown-body :deep(blockquote) {
  border-left: 3px solid #1677ff;
  padding: 4px 16px;
  margin: 12px 0;
  background: #f8f9ff;
  color: #555;
}
.markdown-body :deep(code) {
  background: #f5f5f5;
  padding: 2px 6px;
  border-radius: 3px;
  font-size: 13px;
  font-family: 'Fira Code', 'Consolas', monospace;
}
.markdown-body :deep(pre) {
  background: #f6f8fa;
  padding: 16px;
  border-radius: 8px;
  overflow-x: auto;
  margin: 12px 0;
  border: 1px solid #e8e8e8;
}
.markdown-body :deep(pre code) { background: none; padding: 0; font-size: 13px; }
.markdown-body :deep(table) { border-collapse: collapse; margin: 12px 0; width: 100%; }
.markdown-body :deep(th), .markdown-body :deep(td) { border: 1px solid #e8e8e8; padding: 8px 12px; font-size: 13px; }
.markdown-body :deep(th) { background: #fafafa; font-weight: 600; }
.markdown-body :deep(a) { color: #1677ff; }
.markdown-body :deep(img) { max-width: 100%; }
</style>

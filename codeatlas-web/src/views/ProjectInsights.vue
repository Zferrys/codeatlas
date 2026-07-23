<template>
  <div class="insights-page">
    <!-- 加载态 -->
    <a-spin :spinning="loading" tip="AI 正在分析...">
      <a-skeleton v-if="loading" active :paragraph="{ rows: 6 }" />

      <!-- 错误态 -->
      <div v-else-if="error" class="state-wrap">
        <a-result status="error" title="加载失败" :sub-title="error">
          <template #extra>
            <a-button type="primary" @click="fetchInsights">重试</a-button>
          </template>
        </a-result>
      </div>

      <!-- 空状态 -->
      <div v-else-if="insights.length === 0" class="state-wrap">
        <a-empty description="暂无 AI 洞察">
          <template #description>
            <p>触发一次扫描后，AI 将自动分析代码并生成洞察</p>
          </template>
        </a-empty>
      </div>

      <!-- 洞察列表 -->
      <template v-else>
        <div class="insights-toolbar">
          <a-radio-group v-model:value="activeType" option-type="button" size="small" @change="onTypeChange">
            <a-radio-button value="all">全部</a-radio-button>
            <a-radio-button value="ARCH_STORY">架构叙事</a-radio-button>
            <a-radio-button value="ANTI_PATTERN">反模式</a-radio-button>
            <a-radio-button value="PATTERN">设计模式</a-radio-button>
            <a-radio-button value="IMPACT">影响分析</a-radio-button>
            <a-radio-button value="QA">问答</a-radio-button>
          </a-radio-group>
          <span class="insight-count">共 {{ filteredInsights.length }} 条</span>
        </div>

        <a-row :gutter="[16, 16]">
          <a-col v-for="item in filteredInsights" :key="item.id" :xs="24" :sm="12" :lg="8">
            <a-card hoverable class="insight-card" @click="openDetail(item)">
              <template #title>
                <div class="card-title-row">
                  <span class="card-type-icon" :style="{ background: typeColor(item.type) }">
                    {{ typeIcon(item.type) }}
                  </span>
                  <span class="card-title-text">{{ item.title }}</span>
                </div>
              </template>
              <template #extra>
                <a-tag :color="typeColor(item.type)" size="small">{{ typeLabel(item.type) }}</a-tag>
              </template>

              <div class="insight-preview">
                {{ renderPreview(item.content) }}
              </div>

              <div class="insight-confidence">
                <span class="confidence-label">置信度</span>
                <div class="confidence-bar-track">
                  <div
                    class="confidence-bar-fill"
                    :style="{ width: formatConfidence(item.confidence), background: confidenceColor(item.confidence) }"
                  ></div>
                </div>
                <span class="confidence-value" :style="{ color: confidenceColor(item.confidence) }">
                  {{ formatConfidence(item.confidence) }}
                </span>
              </div>

              <div class="insight-time">
                <ClockCircleOutlined style="font-size:12px;color:#bbb" />
                {{ formatDate(item.createdAt) }}
              </div>
            </a-card>
          </a-col>
        </a-row>
      </template>
    </a-spin>

    <!-- 详情弹窗 -->
    <a-modal
      v-model:open="modalVisible"
      :title="selectedInsight?.title || '洞察详情'"
      width="820px"
      :footer="null"
      @afterClose="onModalClose"
    >
      <div class="insight-detail" v-if="selectedInsight">
        <div class="insight-meta">
          <a-tag :color="typeColor(selectedInsight.type)">{{ typeLabel(selectedInsight.type) }}</a-tag>
          <span>
            置信度
            <strong :style="{ color: confidenceColor(selectedInsight.confidence) }">
              {{ formatConfidence(selectedInsight.confidence) }}
            </strong>
          </span>
          <span><ClockCircleOutlined /> {{ formatDate(selectedInsight.createdAt) }}</span>
          <span v-if="selectedInsight.relatedClass">相关类: <code>{{ selectedInsight.relatedClass }}</code></span>
        </div>
        <div class="insight-content markdown-body" v-html="renderMarkdown(selectedInsight.content || '')"></div>
      </div>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { marked } from 'marked'
import { markedHighlight } from 'marked-highlight'
import hljs from 'highlight.js'
import 'highlight.js/styles/github.css'
import { ClockCircleOutlined } from '@ant-design/icons-vue'
import api from '../api'

// Configure marked with highlight.js for code syntax highlighting
marked.use(markedHighlight({
  langPrefix: 'hljs language-',
  highlight(code, lang) {
    if (lang && hljs.getLanguage(lang)) {
      return hljs.highlight(code, { language: lang }).value
    }
    return hljs.highlightAuto(code).value
  }
}))

const route = useRoute()
const projectId = computed(() => route.params.id)

const loading = ref(false)
const error = ref(null)
const insights = ref([])
const activeType = ref('all')
const selectedInsight = ref(null)
const modalVisible = ref(false)

watch(selectedInsight, (val) => {
  modalVisible.value = val !== null
})

const filteredInsights = computed(() => {
  if (activeType.value === 'all') return insights.value
  return insights.value.filter(i => i.type === activeType.value)
})

onMounted(() => fetchInsights())

async function fetchInsights() {
  loading.value = true
  error.value = null
  try {
    const res = await api.get(`/projects/${projectId.value}/insights`)
    insights.value = res.data.data || []
  } catch (e) {
    error.value = e.response?.data?.message || '加载 AI 洞察失败'
  } finally {
    loading.value = false
  }
}

function onTypeChange() {
  selectedInsight.value = null
}

function openDetail(item) {
  selectedInsight.value = item
}

function onModalClose() {
  selectedInsight.value = null
}

function renderPreview(content) {
  if (!content) return ''
  const text = content.replace(/<[^>]*>/g, '').replace(/[#*`>_{}]/g, '')
  return text.substring(0, 140) + (text.length > 140 ? '...' : '')
}

function renderMarkdown(content) {
  if (!content) return ''
  return marked(content)
}

function typeIcon(type) {
  const map = {
    'ARCH_STORY': 'A',
    'ANTI_PATTERN': '!',
    'PATTERN': 'P',
    'IMPACT': 'I',
    'QA': '?'
  }
  return map[type] || '·'
}

function typeColor(type) {
  const map = {
    'ARCH_STORY': '#1890ff',
    'ANTI_PATTERN': '#ff4d4f',
    'PATTERN': '#52c41a',
    'IMPACT': '#fa8c16',
    'QA': '#722ed1'
  }
  return map[type] || '#666'
}

function typeLabel(type) {
  const map = {
    'ARCH_STORY': '架构叙事',
    'ANTI_PATTERN': '反模式',
    'PATTERN': '设计模式',
    'IMPACT': '影响分析',
    'QA': '问答'
  }
  return map[type] || type
}

function confidenceColor(val) {
  if (val == null) return '#d9d9d9'
  const n = Number(val)
  if (n >= 0.85) return '#52c41a'
  if (n >= 0.7) return '#1890ff'
  if (n >= 0.5) return '#fa8c16'
  return '#ff4d4f'
}

function formatConfidence(val) {
  if (val == null) return '0%'
  return (Number(val) * 100).toFixed(0) + '%'
}

function formatDate(date) {
  if (!date) return ''
  const d = new Date(date)
  return d.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}
</script>

<style scoped>
.insights-page { padding: 0; }
.state-wrap { padding: 40px 0; }

/* 工具栏 */
.insights-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
  flex-wrap: wrap;
  gap: 8px;
}

.insight-count {
  font-size: 13px;
  color: #999;
}

/* 卡片 */
.insight-card {
  border-radius: 12px;
  height: 100%;
  transition: transform 0.2s, box-shadow 0.2s;
}

.insight-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 20px rgba(0,0,0,0.08);
}

.card-title-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.card-type-icon {
  width: 28px;
  height: 28px;
  border-radius: 6px;
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.card-title-text {
  font-size: 14px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.insight-preview {
  color: #666;
  font-size: 13px;
  line-height: 1.6;
  height: 44px;
  overflow: hidden;
  margin-bottom: 16px;
}

/* 置信度条 */
.insight-confidence {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.confidence-label {
  font-size: 12px;
  color: #999;
  flex-shrink: 0;
}

.confidence-bar-track {
  flex: 1;
  height: 4px;
  background: #f0f0f0;
  border-radius: 2px;
  overflow: hidden;
}

.confidence-bar-fill {
  height: 100%;
  border-radius: 2px;
  transition: width 0.4s ease;
}

.confidence-value {
  font-size: 12px;
  font-weight: 600;
  flex-shrink: 0;
}

.insight-time {
  font-size: 12px;
  color: #bbb;
  display: flex;
  align-items: center;
  gap: 4px;
}

/* 详情弹窗 */
.insight-detail .insight-meta {
  display: flex;
  gap: 16px;
  align-items: center;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid #f0f0f0;
  color: #888;
  font-size: 13px;
  flex-wrap: wrap;
}

.insight-meta code {
  background: #f5f5f5;
  padding: 1px 6px;
  border-radius: 3px;
  font-size: 12px;
  color: #667eea;
}

.insight-content {
  padding: 8px 0;
}

/* Markdown 样式 (with highlight.js) */
.markdown-body { line-height: 1.8; font-size: 14px; color: #333; }
.markdown-body :deep(h1) { font-size: 22px; font-weight: 600; margin-top: 24px; }
.markdown-body :deep(h2) { font-size: 18px; font-weight: 600; margin-top: 24px; color: #1a1a2e; }
.markdown-body :deep(h3) { font-size: 15px; margin-top: 20px; }
.markdown-body :deep(p) { margin: 8px 0; }
.markdown-body :deep(ul), .markdown-body :deep(ol) { padding-left: 20px; }
.markdown-body :deep(li) { margin: 4px 0; }
.markdown-body :deep(blockquote) {
  border-left: 3px solid #667eea;
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
.markdown-body :deep(pre code) {
  background: none;
  padding: 0;
  font-size: 13px;
}
.markdown-body :deep(table) {
  border-collapse: collapse;
  margin: 12px 0;
  width: 100%;
}
.markdown-body :deep(th), .markdown-body :deep(td) {
  border: 1px solid #e8e8e8;
  padding: 8px 12px;
  font-size: 13px;
}
.markdown-body :deep(th) {
  background: #fafafa;
  font-weight: 600;
}
.markdown-body :deep(a) {
  color: #667eea;
}
.markdown-body :deep(img) {
  max-width: 100%;
}
</style>

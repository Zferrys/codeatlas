<template>
  <div class="story-page">
    <a-spin :spinning="loading" tip="加载架构叙事...">
      <div v-if="!loading && error" class="story-error">
        <a-result status="error" title="加载失败" :sub-title="error">
          <template #extra>
            <a-button type="primary" @click="fetchStory">重试</a-button>
          </template>
        </a-result>
      </div>

      <div v-else-if="!loading && !story" class="story-empty">
        <a-empty description="暂无架构叙事">
          <template #description>
            <p>触发一次扫描后，AI 将自动分析代码架构并生成叙事故事</p>
            <p style="color: #999; font-size: 12px;">你可以先查看"代码地图"标签了解当前代码的拓扑结构</p>
          </template>
        </a-empty>
      </div>

      <div v-else-if="story" class="story-content">
        <a-page-header :title="story.title" :sub-title="`AI 生成 · ${formatDate(story.createdAt)}`">
          <template #tags>
            <a-tag color="blue">架构叙事</a-tag>
            <a-tag v-if="story.confidence" :color="story.confidence >= 0.85 ? 'green' : 'orange'">
              置信度 {{ (story.confidence * 100).toFixed(0) }}%
            </a-tag>
          </template>
        </a-page-header>
        <div class="markdown-body" v-html="renderMarkdown(story.content || '')"></div>
      </div>
    </a-spin>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { marked } from 'marked'
import { markedHighlight } from 'marked-highlight'
import hljs from 'highlight.js'
import 'highlight.js/styles/github.css'
import api from '../api'

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
const story = ref(null)

onMounted(() => fetchStory())

async function fetchStory() {
  loading.value = true
  error.value = null
  try {
    const res = await api.get(`/projects/${projectId.value}/insights`, { params: { type: 'ARCH_STORY' } })
    const list = res.data.data?.records || []
    story.value = list.length > 0 ? list[0] : null
  } catch (e) {
    error.value = e.response?.data?.message || '加载架构叙事失败'
  } finally {
    loading.value = false
  }
}

function renderMarkdown(content) {
  if (!content) return ''
  return marked(content)
}

function formatDate(date) {
  if (!date) return ''
  const d = new Date(date)
  return d.toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}
</script>

<style scoped>
.story-page { padding: 0; max-width: 960px; margin: 0 auto; }
.story-error, .story-empty { padding: 40px 0; }
.story-content { background: #fff; border-radius: 8px; padding: 24px 32px 48px; }
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

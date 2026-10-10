<template>
  <div class="admin-ai-models">
    <div class="page-header">
      <h2 class="page-title">AI 模型</h2>
      <a-space>
        <a-tag v-if="listSource === 'fallback'" color="orange">服务商清单接口不可用，展示内置清单</a-tag>
        <a-button size="small" @click="fetchModels" :loading="loading">
          <template #icon><ReloadOutlined /></template>
          刷新
        </a-button>
      </a-space>
    </div>

    <a-spin :spinning="loading && !models.length" tip="加载中...">
      <!-- 当前模型 -->
      <a-card v-if="current" class="current-card" :bordered="false">
        <div class="current-row">
          <div class="current-icon"><ThunderboltOutlined /></div>
          <div class="current-info">
            <div class="current-label">当前使用模型</div>
            <div class="current-value">{{ currentName }}</div>
            <div class="current-id">{{ current }}</div>
          </div>
          <a-tag color="green">运行中</a-tag>
        </div>
        <div class="current-hint">切换模型后所有 AI 功能（架构分析 / 影响模拟 / Agentic Search）立即生效，无需重启</div>
      </a-card>

      <!-- 模型列表 -->
      <a-row :gutter="16">
        <a-col :xs="24" :md="12" :xl="8" v-for="model in models" :key="model.id">
          <a-card class="model-card" :bordered="false" :class="{ 'is-current': model.current }">
            <div class="model-head">
              <div class="model-name" :title="model.name">{{ model.name }}</div>
              <a-tag v-if="model.current" color="green">当前使用</a-tag>
            </div>
            <div class="model-id">{{ model.id }}</div>
            <div class="model-meta">
              <a-tag color="blue">{{ providerLabel(model.provider) }}</a-tag>
              <span v-if="model.contextWindow" class="meta-item">{{ formatContext(model.contextWindow) }} 上下文</span>
              <a-tag v-if="model.stale" color="orange">已不在服务商清单</a-tag>
            </div>

            <div class="model-actions">
              <a-button size="small" :loading="testing === model.id" @click="testModel(model)">
                测试连通性
              </a-button>
              <a-popconfirm
                v-if="!model.current"
                title="切换后所有 AI 功能将使用该模型，确认切换？"
                ok-text="切换"
                cancel-text="取消"
                @confirm="switchModel(model)"
              >
                <a-button size="small" type="primary" :disabled="!model.available" :loading="switching === model.id">
                  设为当前模型
                </a-button>
              </a-popconfirm>
            </div>

            <div v-if="testResults[model.id]" class="test-result">
              <a-tag :color="testResults[model.id].success ? 'success' : 'error'">
                {{ testResults[model.id].success ? `连通正常 ${testResults[model.id].latencyMs}ms` : '连通失败' }}
              </a-tag>
              <span v-if="!testResults[model.id].success" class="test-error">
                {{ testResults[model.id].error }}
              </span>
            </div>
          </a-card>
        </a-col>
      </a-row>

      <a-empty v-if="!loading && !models.length && !error" description="没有可用的 AI 模型（未配置 API Key）" />
    </a-spin>

    <a-result
      v-if="!loading && error"
      status="error"
      title="加载失败"
      :sub-title="error"
    >
      <template #extra>
        <a-button type="primary" @click="fetchModels">重试</a-button>
      </template>
    </a-result>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { ReloadOutlined, ThunderboltOutlined } from '@ant-design/icons-vue'
import api from '../../api'

const models = ref([])
const current = ref(null)
const listSource = ref('live')
const loading = ref(false)
const error = ref(null)
const testing = ref(null)
const switching = ref(null)
const testResults = ref({})

const currentName = computed(() => {
  const model = models.value.find(item => item.id === current.value)
  return model ? model.name : current.value
})

function providerLabel(provider) {
  return provider === 'claude' ? 'Claude' : 'DeepSeek'
}

function formatContext(tokens) {
  if (tokens >= 1048576) return Math.round(tokens / 1048576) + 'M'
  return Math.round(tokens / 1024) + 'K'
}

async function fetchModels() {
  loading.value = true
  error.value = null
  try {
    const res = await api.get('/admin/ai/models')
    if (res.data.code !== 200) throw new Error(res.data.message)
    const data = res.data.data
    models.value = data.models || []
    current.value = data.current
    listSource.value = data.listSource
  } catch (e) {
    error.value = e.response?.data?.message || e.message || '加载模型列表失败'
  } finally {
    loading.value = false
  }
}

async function testModel(model) {
  testing.value = model.id
  try {
    const res = await api.post('/admin/ai/models/test', { model: model.id })
    if (res.data.code === 200) {
      testResults.value = { ...testResults.value, [model.id]: res.data.data }
    } else {
      message.error(res.data.message || '测试失败')
    }
  } catch (e) {
    // error handled by interceptor
  } finally {
    testing.value = null
  }
}

async function switchModel(model) {
  switching.value = model.id
  try {
    const res = await api.put('/admin/ai/model', { model: model.id })
    if (res.data.code === 200) {
      message.success(`已切换到 ${model.name}`)
      await fetchModels()
    } else {
      message.error(res.data.message || '切换失败')
    }
  } catch (e) {
    // error handled by interceptor
  } finally {
    switching.value = null
  }
}

onMounted(fetchModels)
</script>

<style scoped>
.admin-ai-models {
  max-width: 1200px;
  margin: 0 auto;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.page-title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
}

.current-card {
  margin-bottom: 16px;
  border-radius: 8px;
  background: linear-gradient(135deg, #f0f7ff 0%, #f6ffed 100%);
}

.current-row {
  display: flex;
  align-items: center;
  gap: 16px;
}

.current-icon {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  flex-shrink: 0;
  background: #e6f4ff;
  color: #1677ff;
}

.current-info {
  flex: 1;
  min-width: 0;
}

.current-label {
  font-size: 13px;
  color: var(--color-text-tertiary);
  margin-bottom: 2px;
}

.current-value {
  font-size: 18px;
  font-weight: 600;
  color: var(--color-text-primary);
}

.current-id {
  font-size: 12px;
  color: var(--color-text-tertiary);
  font-family: monospace;
  margin-top: 2px;
}

.current-hint {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed var(--color-border-light);
  font-size: 12px;
  color: var(--color-text-tertiary);
}

.model-card {
  margin-bottom: 16px;
  border-radius: 8px;
  height: calc(100% - 16px);
}

.model-card.is-current {
  border: 1px solid #52c41a;
}

.model-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}

.model-name {
  font-size: 16px;
  font-weight: 600;
  color: var(--color-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.model-id {
  font-size: 12px;
  font-family: monospace;
  color: var(--color-text-tertiary);
  margin: 4px 0 10px;
}

.model-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}

.meta-item {
  font-size: 12px;
  color: var(--color-text-tertiary);
}

.model-actions {
  display: flex;
  gap: 8px;
}

.test-result {
  margin-top: 10px;
  display: flex;
  align-items: flex-start;
  gap: 8px;
  flex-wrap: wrap;
}

.test-error {
  font-size: 12px;
  color: #ff4d4f;
  word-break: break-all;
}
</style>

<template>
  <div class="settings-page">
    <!-- 加载态 -->
    <a-skeleton v-if="loading" active :paragraph="{ rows: 6 }" />

    <!-- 错误态 -->
    <div v-else-if="error" class="state-wrap">
      <a-result status="error" title="加载失败" :sub-title="error">
        <template #extra><a-button type="primary" @click="fetchProject">重试</a-button></template>
      </a-result>
    </div>

    <!-- 设置表单 -->
    <template v-else-if="project">
      <div class="settings-card">
        <h3 class="card-title">基本信息</h3>
        <a-form :model="formState" layout="vertical" style="max-width: 480px">
          <a-form-item label="项目名称">
            <a-input v-model:value="formState.name" placeholder="输入项目名称" size="large" />
          </a-form-item>
          <a-form-item label="项目描述">
            <a-textarea v-model:value="formState.description" placeholder="输入项目描述" :rows="3" />
          </a-form-item>
          <a-form-item>
            <a-button type="primary" :loading="saving" @click="saveSettings">保存修改</a-button>
          </a-form-item>
        </a-form>
      </div>

      <div class="settings-card" style="margin-top:16px">
        <h3 class="card-title">项目详情</h3>
        <a-descriptions :column="2" size="small" bordered>
          <a-descriptions-item label="语言">{{ project.language || 'Java' }}</a-descriptions-item>
          <a-descriptions-item label="源码类型">{{ project.sourceType || '-' }}</a-descriptions-item>
          <a-descriptions-item label="总类数">{{ project.totalClasses || 0 }}</a-descriptions-item>
          <a-descriptions-item label="健康度评分">{{ project.healthScore ?? 0 }} 分</a-descriptions-item>
          <a-descriptions-item label="创建时间">{{ formatDate(project.createdAt) }}</a-descriptions-item>
          <a-descriptions-item label="最近更新">{{ formatDate(project.updatedAt) }}</a-descriptions-item>
        </a-descriptions>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { message } from 'ant-design-vue'
import api from '../api'

const route = useRoute()
const projectId = computed(() => route.params.id)

const loading = ref(false)
const saving = ref(false)
const error = ref(null)
const project = ref(null)
const formState = reactive({ name: '', description: '' })

onMounted(() => fetchProject())

async function fetchProject() {
  loading.value = true; error.value = null
  try {
    const res = await api.get(`/projects/${projectId.value}`)
    project.value = res.data.data
    formState.name = project.value.name || ''
    formState.description = project.value.description || ''
  } catch (e) {
    error.value = e.response?.data?.message || '加载项目信息失败'
  } finally {
    loading.value = false
  }
}

async function saveSettings() {
  if (!formState.name.trim()) {
    message.warning('项目名称不能为空')
    return
  }
  saving.value = true
  try {
    await api.put(`/projects/${projectId.value}`, {
      name: formState.name,
      description: formState.description
    })
    message.success('项目信息已更新')
    if (project.value) {
      project.value.name = formState.name
      project.value.description = formState.description
    }
  } catch (e) {
    message.error(e.response?.data?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

function formatDate(date) {
  if (!date) return '-'
  const d = new Date(date)
  return d.toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}
</script>

<style scoped>
.settings-page { padding: 0; max-width: 680px; }
.state-wrap { padding: 40px 0; }

.settings-card {
  background: #fff;
  border-radius: 12px;
  padding: 24px;
  box-shadow: 0 1px 4px rgba(0,0,0,0.04);
}

.card-title {
  font-size: 16px;
  font-weight: 600;
  color: #1a1a2e;
  margin: 0 0 20px;
}
</style>

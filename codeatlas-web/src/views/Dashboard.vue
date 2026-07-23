<template>
  <div class="dashboard">
    <!-- 统计卡片 -->
    <a-row :gutter="[20, 20]" class="stat-row">
      <a-col :xs="12" :sm="12" :lg="6">
        <div class="stat-card">
          <div class="stat-icon" style="background:#e8f4ff;color:#1890ff">
            <ProjectOutlined />
          </div>
          <div class="stat-body">
            <span class="stat-value">{{ stats.projects }}</span>
            <span class="stat-label">项目总数</span>
          </div>
        </div>
      </a-col>
      <a-col :xs="12" :sm="12" :lg="6">
        <div class="stat-card">
          <div class="stat-icon" style="background:#e6f7e9;color:#52c41a">
            <FileTextOutlined />
          </div>
          <div class="stat-body">
            <span class="stat-value">{{ stats.classes }}</span>
            <span class="stat-label">总类数</span>
          </div>
        </div>
      </a-col>
      <a-col :xs="12" :sm="12" :lg="6">
        <div class="stat-card">
          <div class="stat-icon" style="background:#fff7e6;color:#fa8c16">
            <ScanOutlined />
          </div>
          <div class="stat-body">
            <span class="stat-value">{{ stats.scans }}</span>
            <span class="stat-label">活跃扫描</span>
          </div>
        </div>
      </a-col>
      <a-col :xs="12" :sm="12" :lg="6">
        <div class="stat-card">
          <div class="stat-icon" style="background:#f0e6ff;color:#722ed1">
            <BulbOutlined />
          </div>
          <div class="stat-body">
            <span class="stat-value">{{ stats.insights }}</span>
            <span class="stat-label">AI 洞察</span>
          </div>
        </div>
      </a-col>
    </a-row>

    <!-- 页面头部 + 操作 -->
    <div class="section-header">
      <h2 class="section-title">我的项目</h2>
      <div class="section-actions">
        <a-input-search
          v-model:value="searchText"
          placeholder="搜索项目..."
          style="width:240px"
        />
        <a-button type="primary" @click="showCreateModal = true">
          <template #icon><PlusOutlined /></template>
          新建项目
        </a-button>
      </div>
    </div>

    <!-- 加载骨架屏 -->
    <a-row v-if="loading" :gutter="[20, 20]">
      <a-col v-for="n in 3" :key="n" :xs="24" :sm="12" :lg="8">
        <a-skeleton active :title="true" :paragraph="{ rows: 3 }" />
      </a-col>
    </a-row>

    <!-- 项目卡片列表 -->
    <a-row v-else :gutter="[20, 20]">
      <a-col v-for="project in filteredProjects" :key="project.id" :xs="24" :sm="12" :lg="8">
        <a-card hoverable class="project-card" @click="goProject(project.id)">
          <div class="card-top">
            <div class="card-avatar" :style="{ background: langColor(project.language) }">
              {{ (project.name || 'P')[0].toUpperCase() }}
            </div>
            <div class="card-title-wrap">
              <h3 class="card-title">{{ project.name }}</h3>
              <a-tag :color="project.language === 'Java' ? 'blue' : 'green'" size="small">
                {{ project.language || 'Java' }}
              </a-tag>
            </div>
          </div>

          <p class="card-desc">{{ project.description || '暂无描述' }}</p>

          <div class="card-stats">
            <div class="card-stat-item">
              <FileTextOutlined />
              <span>{{ project.totalClasses || 0 }} 类</span>
            </div>
            <div class="card-stat-item">
              <ClockCircleOutlined />
              <span v-if="project.lastScanTime">{{ formatTime(project.lastScanTime) }}</span>
              <span v-else>未扫描</span>
            </div>
          </div>

          <!-- 健康度环形图 -->
          <div class="card-health" v-if="project.healthScore !== null && project.healthScore !== undefined">
            <a-progress
              type="circle"
              :percent="project.healthScore"
              :width="52"
              :stroke-color="healthColor(project.healthScore)"
              :format="p => p + '%'"
            />
            <span class="health-label">健康度</span>
          </div>
        </a-card>
      </a-col>
    </a-row>

    <!-- 空状态 -->
    <a-empty
      v-if="!loading && projects.length === 0"
      description="还没有项目，点击上方按钮创建一个吧"
    />

    <!-- 创建项目弹窗 -->
    <a-modal
      v-model:open="showCreateModal"
      title="新建项目"
      :confirm-loading="createLoading"
      @ok="handleCreateProject"
      @cancel="showCreateModal = false"
    >
      <a-form :model="createForm" layout="vertical">
        <a-form-item label="项目名称" required>
          <a-input v-model:value="createForm.name" placeholder="例如：order-service" />
        </a-form-item>
        <a-form-item label="源码来源" required>
          <a-select v-model:value="createForm.sourceType" placeholder="选择源码来源">
            <a-select-option value="GIT_URL">Git 仓库 URL</a-select-option>
            <a-select-option value="ZIP_UPLOAD">ZIP 文件上传</a-select-option>
            <a-select-option value="LOCAL_PATH">本地路径</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item v-if="createForm.sourceType === 'GIT_URL'" label="Git URL">
          <a-input v-model:value="createForm.sourceUrl" placeholder="https://github.com/user/repo.git" />
        </a-form-item>
        <a-form-item v-if="createForm.sourceType === 'ZIP_UPLOAD'" label="上传源码包">
          <a-upload-dragger
            :custom-request="handleZipUpload"
            :before-upload="beforeZipUpload"
            :show-upload-list="false"
            accept=".zip,.tar.gz,.tgz"
          >
            <p class="upload-icon">
              <InboxOutlined style="font-size:36px;color:#667eea" />
            </p>
            <p class="upload-text">点击或拖拽 ZIP / TAR.GZ 文件到此处</p>
            <p class="upload-hint">文件大小不超过 100MB</p>
          </a-upload-dragger>
          <div v-if="uploading" style="margin-top:12px">
            <a-progress :percent="uploadPercent" status="active" />
          </div>
        </a-form-item>
        <a-form-item label="项目描述">
          <a-textarea v-model:value="createForm.description" placeholder="可选描述" :rows="3" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { message } from 'ant-design-vue'
import {
  ProjectOutlined, FileTextOutlined, ScanOutlined, BulbOutlined,
  PlusOutlined, ClockCircleOutlined, InboxOutlined
} from '@ant-design/icons-vue'
import api from '../api'

const router = useRouter()
const authStore = useAuthStore()
const projects = ref([])
const loading = ref(true)
const showCreateModal = ref(false)
const createLoading = ref(false)
const searchText = ref('')

const stats = reactive({
  projects: 0,
  classes: 0,
  scans: 0,
  insights: 0
})

const createForm = reactive({
  name: '',
  sourceType: 'GIT_URL',
  sourceUrl: '',
  description: ''
})

const filteredProjects = computed(() => {
  if (!searchText.value) return projects.value
  const q = searchText.value.toLowerCase()
  return projects.value.filter(p =>
    p.name.toLowerCase().includes(q) ||
    (p.description || '').toLowerCase().includes(q)
  )
})

onMounted(async () => {
  try {
    const res = await api.get('/projects')
    const list = res.data.data?.records || []
    projects.value = list
    stats.projects = list.length
    stats.classes = list.reduce((sum, p) => sum + (p.totalClasses || 0), 0)
    stats.scans = list.reduce((sum, p) => sum + (p.totalScans || 0), 0)
    stats.insights = list.reduce((sum, p) => sum + (p.totalInsights || 0), 0)
  } catch (e) {
    // show empty state
  } finally {
    loading.value = false
  }
})

const uploading = ref(false)
const uploadPercent = ref(0)
const uploadFile = ref(null)

function beforeZipUpload(file) {
  const isValidType = file.name.endsWith('.zip') || file.name.endsWith('.tar.gz') || file.name.endsWith('.tgz')
  if (!isValidType) {
    message.error('仅支持 .zip / .tar.gz / .tgz 格式文件')
    return false
  }
  const isUnderSize = file.size / 1024 / 1024 < 100
  if (!isUnderSize) {
    message.error('文件大小不能超过 100MB')
    return false
  }
  return true
}

async function handleZipUpload({ file, onProgress, onSuccess, onError }) {
  uploading.value = true
  uploadPercent.value = 0
  try {
    const formData = new FormData()
    formData.append('file', file)
    const res = await api.post('/files/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      onUploadProgress: (e) => {
        if (e.total > 0) {
          uploadPercent.value = Math.round((e.loaded / e.total) * 100)
        }
      }
    })
    const newProject = res.data.data
    message.success('项目创建成功，即将开始扫描')
    showCreateModal.value = false
    createForm.name = ''
    createForm.sourceType = 'GIT_URL'
    createForm.sourceUrl = ''
    createForm.description = ''
    uploadFile.value = null
    if (newProject?.id) {
      router.push(`/project/${newProject.id}/overview`)
    }
    onSuccess(res.data, file)
  } catch (e) {
    message.error('上传失败: ' + (e.response?.data?.message || e.message))
    onError(e, file)
  } finally {
    uploading.value = false
  }
}

function goProject(id) {
  router.push(`/project/${id}/overview`)
}

async function handleCreateProject() {
  if (!createForm.name) {
    message.warning('请输入项目名称')
    return
  }
  createLoading.value = true
  try {
    const res = await api.post('/projects', createForm)
    message.success('项目创建成功')
    showCreateModal.value = false
    const newProject = res.data.data
    if (newProject?.id) {
      router.push(`/project/${newProject.id}/overview`)
    } else {
      // refresh list by reloading
      const listRes = await api.get('/projects')
      projects.value = listRes.data.data?.records || []
    }
  } catch (e) {
    // handled by interceptor
  } finally {
    createLoading.value = false
  }
}

function formatTime(dateStr) {
  if (!dateStr) return ''
  const d = new Date(dateStr)
  const now = new Date()
  const diff = now - d
  if (diff < 3600000) return Math.floor(diff / 60000) + ' 分钟前'
  if (diff < 86400000) return Math.floor(diff / 3600000) + ' 小时前'
  if (diff < 604800000) return Math.floor(diff / 86400000) + ' 天前'
  return d.toLocaleDateString('zh-CN')
}

function langColor(lang) {
  const map = {
    Java: 'linear-gradient(135deg, #f093fb, #f5576c)',
    Python: 'linear-gradient(135deg, #4facfe, #00f2fe)',
    JavaScript: 'linear-gradient(135deg, #f6d365, #fda085)',
    Go: 'linear-gradient(135deg, #43e97b, #38f9d7)',
    TypeScript: 'linear-gradient(135deg, #3178c6, #235a97)'
  }
  return map[lang] || 'linear-gradient(135deg, #667eea, #764ba2)'
}

function healthColor(score) {
  if (score >= 80) return '#52c41a'
  if (score >= 60) return '#faad14'
  return '#ff4d4f'
}
</script>

<style scoped>
.dashboard {
  padding: 0;
}

/* 统计卡片 */
.stat-row {
  margin-bottom: 24px;
}

.stat-card {
  background: #fff;
  border-radius: 12px;
  padding: 20px;
  display: flex;
  align-items: center;
  gap: 16px;
  box-shadow: 0 1px 4px rgba(0,0,0,0.04);
  transition: transform 0.2s, box-shadow 0.2s;
}

.stat-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 16px rgba(0,0,0,0.08);
}

.stat-icon {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  flex-shrink: 0;
}

.stat-body {
  display: flex;
  flex-direction: column;
}

.stat-value {
  font-size: 28px;
  font-weight: 700;
  color: #1a1a2e;
  line-height: 1.2;
}

.stat-label {
  font-size: 13px;
  color: #999;
  margin-top: 2px;
}

/* 区块头部 */
.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.section-title {
  font-size: 20px;
  font-weight: 600;
  color: #1a1a2e;
  margin: 0;
}

.section-actions {
  display: flex;
  gap: 12px;
  align-items: center;
}

/* 项目卡片 */
.project-card {
  border-radius: 12px;
  position: relative;
  overflow: hidden;
  transition: transform 0.2s, box-shadow 0.2s;
}

.project-card:hover {
  transform: translateY(-3px);
  box-shadow: 0 8px 24px rgba(0,0,0,0.1);
}

.card-top {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.card-avatar {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-weight: 700;
  font-size: 16px;
  flex-shrink: 0;
}

.card-title-wrap {
  flex: 1;
  min-width: 0;
}

.card-title {
  font-size: 15px;
  font-weight: 600;
  color: #1a1a2e;
  margin: 0 0 4px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.card-desc {
  color: #999;
  font-size: 13px;
  margin: 0 0 16px;
  height: 20px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-stats {
  display: flex;
  gap: 16px;
  color: #666;
  font-size: 12px;
}

.card-stat-item {
  display: flex;
  align-items: center;
  gap: 4px;
}

/* 健康度 */
.card-health {
  position: absolute;
  bottom: 20px;
  right: 20px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}

.health-label {
  font-size: 10px;
  color: #999;
}

/* 上传组件 */
.upload-icon {
  margin-bottom: 8px;
}

.upload-text {
  font-size: 14px;
  color: #555;
}

.upload-hint {
  font-size: 12px;
  color: #bbb;
  margin-top: 4px;
}
</style>

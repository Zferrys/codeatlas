<template>
  <div class="dashboard">
    <a-layout>
      <a-layout-header class="header">
        <div class="header-left">
          <h2 class="logo">CodeAtlas</h2>
        </div>
        <div class="header-right">
          <span class="user-name">{{ authStore.user?.username }}</span>
          <a-button type="link" @click="handleLogout">退出</a-button>
        </div>
      </a-layout-header>

      <a-layout-content class="content">
        <div class="content-inner">
          <div class="page-header">
            <h1>我的项目</h1>
            <a-button type="primary" @click="showCreateModal = true">+ 新建项目</a-button>
          </div>

          <a-row :gutter="[16, 16]">
            <a-col v-for="project in projects" :key="project.id" :xs="24" :sm="12" :lg="8">
              <a-card hoverable @click="goProject(project.id)" class="project-card">
                <template #title>{{ project.name }}</template>
                <p class="project-desc">{{ project.description || '暂无描述' }}</p>
                <div class="project-meta">
                  <a-tag :color="project.language === 'Java' ? 'blue' : 'green'">
                    {{ project.language || 'Java' }}
                  </a-tag>
                  <span>{{ project.totalClasses || 0 }} 类</span>
                  <span v-if="project.healthScore !== null">
                    健康度 {{ project.healthScore }}
                  </span>
                </div>
              </a-card>
            </a-col>
          </a-row>

          <a-empty v-if="projects.length === 0" description="还没有项目，点击右上角创建一个吧" />
        </div>
      </a-layout-content>
    </a-layout>

    <!-- Create Project Modal -->
    <a-modal
      v-model:open="showCreateModal"
      title="新建项目"
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
        <a-form-item label="项目描述">
          <a-textarea v-model:value="createForm.description" placeholder="可选描述" :rows="3" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { message } from 'ant-design-vue'
import api from '../api'

const router = useRouter()
const authStore = useAuthStore()
const projects = ref([])
const showCreateModal = ref(false)

const createForm = reactive({
  name: '',
  sourceType: 'GIT_URL',
  sourceUrl: '',
  description: ''
})

onMounted(async () => {
  try {
    const res = await api.get('/projects')
    projects.value = res.data.data?.records || []
  } catch (e) {
    // ignore, show empty
  }
})

function goProject(id) {
  router.push(`/project/${id}/overview`)
}

async function handleCreateProject() {
  if (!createForm.name) {
    message.warning('请输入项目名称')
    return
  }
  try {
    const res = await api.post('/projects', createForm)
    message.success('项目创建成功')
    showCreateModal.value = false
    const newProject = res.data.data
    if (newProject?.id) {
      router.push(`/project/${newProject.id}/overview`)
    } else {
      // refresh list
      window.location.reload()
    }
  } catch (e) {
    // handled by interceptor
  }
}

function handleLogout() {
  authStore.logout()
  router.push('/login')
}
</script>

<style scoped>
.dashboard { min-height: 100vh; background: #f0f2f5; }

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: #fff;
  padding: 0 24px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.06);
}

.logo { color: #667eea; font-size: 20px; margin: 0; }

.header-right { display: flex; align-items: center; gap: 12px; }
.user-name { color: #333; }

.content { padding: 24px; }
.content-inner { max-width: 1200px; margin: 0 auto; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px; }
.page-header h1 { font-size: 24px; margin: 0; }

.project-card { height: 180px; cursor: pointer; }
.project-desc { color: #999; height: 40px; overflow: hidden; }
.project-meta { display: flex; gap: 12px; align-items: center; color: #666; font-size: 13px; margin-top: 12px; }
</style>

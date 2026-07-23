<template>
  <div class="profile-page">
    <a-row :gutter="24">
      <!-- 左侧：用户信息卡片 -->
      <a-col :xs="24" :lg="8">
        <a-card class="profile-card" :bordered="false">
          <div class="profile-avatar-section">
            <a-avatar :size="80" class="profile-avatar">
              {{ userInitial }}
            </a-avatar>
            <h2 class="profile-username">{{ userInfo.username || '--' }}</h2>
            <a-tag :color="roleColor" class="profile-role-tag">{{ roleLabel }}</a-tag>
          </div>

          <a-divider />

          <div class="profile-info-list">
            <div class="info-item">
              <MailOutlined class="info-icon" />
              <span class="info-label">邮箱</span>
              <span class="info-value">{{ userInfo.email || '未设置' }}</span>
            </div>
            <div class="info-item">
              <IdcardOutlined class="info-icon" />
              <span class="info-label">用户ID</span>
              <span class="info-value">#{{ userInfo.id }}</span>
            </div>
            <div class="info-item">
              <CalendarOutlined class="info-icon" />
              <span class="info-label">注册时间</span>
              <span class="info-value">{{ formatDate(userInfo.createdAt) }}</span>
            </div>
            <div class="info-item">
              <CheckCircleOutlined :class="['info-icon', userInfo.status === 1 ? 'status-active' : 'status-inactive']" />
              <span class="info-label">状态</span>
              <a-tag :color="userInfo.status === 1 ? 'green' : 'red'" size="small">
                {{ userInfo.status === 1 ? '正常' : '禁用' }}
              </a-tag>
            </div>
          </div>
        </a-card>
      </a-col>

      <!-- 右侧：操作区 -->
      <a-col :xs="24" :lg="16">
        <a-card title="账户安全" :bordered="false" class="section-card">
          <a-list :split="false">
            <a-list-item>
              <a-list-item-meta title="登录密码" description="定期更换密码保障账户安全" />
              <a-button @click="showPasswordModal = true">修改密码</a-button>
            </a-list-item>
            <a-list-item>
              <a-list-item-meta title="邮箱绑定" :description="userInfo.email || '未绑定邮箱'" />
              <a-button @click="onChangeEmail">更换邮箱</a-button>
            </a-list-item>
          </a-list>
        </a-card>

        <a-card title="偏好设置" :bordered="false" class="section-card" style="margin-top:20px">
          <a-list :split="false">
            <a-list-item>
              <a-list-item-meta title="界面主题" description="切换亮色/暗色模式" />
              <a-switch
                :checked="isDark"
                checked-children="暗"
                un-checked-children="亮"
                @change="onThemeToggle"
              />
            </a-list-item>
            <a-list-item>
              <a-list-item-meta title="语言" description="界面显示语言" />
              <a-select :value="locale" style="width:120px" @change="onLocaleChange">
                <a-select-option value="zh-CN">简体中文</a-select-option>
                <a-select-option value="en-US">English</a-select-option>
              </a-select>
            </a-list-item>
          </a-list>
        </a-card>

        <a-card title="操作日志" :bordered="false" class="section-card" style="margin-top:20px">
          <a-empty description="暂无操作日志" :image-style="{ height: '60px' }" />
        </a-card>
      </a-col>
    </a-row>

    <!-- 修改密码弹窗 -->
    <a-modal
      v-model:open="showPasswordModal"
      title="修改密码"
      @ok="handlePasswordChange"
      @cancel="showPasswordModal = false"
      :confirm-loading="passwordLoading"
    >
      <a-form :model="passwordForm" layout="vertical">
        <a-form-item label="当前密码" required>
          <a-input-password v-model:value="passwordForm.oldPassword" placeholder="输入当前密码" />
        </a-form-item>
        <a-form-item label="新密码" required>
          <a-input-password v-model:value="passwordForm.newPassword" placeholder="至少6位" />
        </a-form-item>
        <a-form-item label="确认新密码" required>
          <a-input-password v-model:value="passwordForm.confirmPassword" placeholder="再次输入新密码" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import {
  MailOutlined, IdcardOutlined, CalendarOutlined, CheckCircleOutlined
} from '@ant-design/icons-vue'
import api from '../api'

const userInfo = ref({
  id: 0, username: '', email: '', role: '', avatarUrl: '', createdAt: null, status: 1
})

const isDark = ref(document.documentElement.getAttribute('data-theme') === 'dark')
const locale = ref(localStorage.getItem('codeatlas_locale') || 'zh-CN')

const showPasswordModal = ref(false)
const passwordLoading = ref(false)
const passwordForm = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

const userInitial = computed(() => {
  return (userInfo.value.username || 'U')[0].toUpperCase()
})

const roleColor = computed(() => {
  const map = { ADMIN: 'red', ARCHITECT: 'purple', DEVELOPER: 'blue', VIEWER: 'default' }
  return map[userInfo.value.role] || 'default'
})

const roleLabel = computed(() => {
  const map = { ADMIN: '管理员', ARCHITECT: '架构师', DEVELOPER: '开发者', VIEWER: '观察者' }
  return map[userInfo.value.role] || userInfo.value.role
})

function formatDate(dateStr) {
  if (!dateStr) return '--'
  const d = new Date(dateStr)
  const pad = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function onThemeToggle(checked) {
  isDark.value = checked
  document.documentElement.setAttribute('data-theme', checked ? 'dark' : 'light')
  localStorage.setItem('codeatlas_theme', checked ? 'dark' : 'light')
}

function onChangeEmail() {
  message.info('邮箱修改功能将在后续版本上线')
}

function onLocaleChange(val) {
  locale.value = val
  localStorage.setItem('codeatlas_locale', val)
  message.info('语言切换功能将在后续版本上线')
}

async function fetchUserInfo() {
  try {
    const res = await api.get('/auth/me')
    userInfo.value = res.data.data
  } catch (e) {
    // 401 handled by interceptor
  }
}

async function handlePasswordChange() {
  if (!passwordForm.oldPassword || !passwordForm.newPassword) {
    message.warning('请填写完整信息')
    return
  }
  if (passwordForm.newPassword !== passwordForm.confirmPassword) {
    message.error('两次输入的新密码不一致')
    return
  }
  if (passwordForm.newPassword.length < 6) {
    message.warning('新密码至少6位')
    return
  }
  passwordLoading.value = true
  try {
    await api.put('/auth/password', {
      oldPassword: passwordForm.oldPassword,
      newPassword: passwordForm.newPassword
    })
    message.success('密码修改成功，请重新登录')
    showPasswordModal.value = false
    passwordForm.oldPassword = ''
    passwordForm.newPassword = ''
    passwordForm.confirmPassword = ''
  } catch (e) {
    // handled by interceptor
  } finally {
    passwordLoading.value = false
  }
}

onMounted(() => {
  fetchUserInfo()
})
</script>

<style scoped>
.profile-page {
  max-width: 960px;
  margin: 0 auto;
}

.profile-card {
  text-align: center;
}

.profile-avatar-section {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}

.profile-avatar {
  background: linear-gradient(135deg, #667eea, #764ba2);
}

.profile-username {
  margin: 0;
  font-size: 22px;
  font-weight: 600;
  color: #1a1a2e;
}

.profile-role-tag {
  font-size: 13px;
}

.profile-info-list {
  text-align: left;
}

.info-item {
  display: flex;
  align-items: center;
  padding: 10px 0;
  gap: 10px;
}

.info-icon {
  font-size: 16px;
  color: #999;
  flex-shrink: 0;
  width: 20px;
}

.info-icon.status-active { color: #52c41a; }
.info-icon.status-inactive { color: #ff4d4f; }

.info-label {
  font-size: 13px;
  color: #999;
  width: 60px;
  flex-shrink: 0;
}

.info-value {
  font-size: 14px;
  color: #333;
  flex: 1;
  text-align: right;
}

.section-card {
  border-radius: 10px;
}

:deep(.ant-list-item) {
  padding: 16px 0;
}
</style>

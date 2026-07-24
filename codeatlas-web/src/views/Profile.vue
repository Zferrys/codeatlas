<template>
  <div class="profile-page">
    <a-row :gutter="24">
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
              <span class="info-label">{{ $t('profile.email') }}</span>
              <span class="info-value">{{ userInfo.email || $t('profile.notSet') }}</span>
            </div>
            <div class="info-item">
              <IdcardOutlined class="info-icon" />
              <span class="info-label">{{ $t('profile.userId') }}</span>
              <span class="info-value">#{{ userInfo.id }}</span>
            </div>
            <div class="info-item">
              <CalendarOutlined class="info-icon" />
              <span class="info-label">{{ $t('profile.registerTime') }}</span>
              <span class="info-value">{{ formatDate(userInfo.createdAt) }}</span>
            </div>
            <div class="info-item">
              <CheckCircleOutlined :class="['info-icon', userInfo.status === 1 ? 'status-active' : 'status-inactive']" />
              <span class="info-label">{{ $t('profile.status') }}</span>
              <a-tag :color="userInfo.status === 1 ? 'green' : 'red'" size="small">
                {{ userInfo.status === 1 ? $t('profile.active') : $t('profile.disabled') }}
              </a-tag>
            </div>
          </div>
        </a-card>
      </a-col>

      <a-col :xs="24" :lg="16">
        <a-card :title="$t('profile.accountSecurity')" :bordered="false" class="section-card">
          <a-list :split="false">
            <a-list-item>
              <a-list-item-meta :title="$t('profile.loginPassword')" :description="$t('profile.passwordDesc')" />
              <a-button @click="showPasswordModal = true">{{ $t('profile.changePassword') }}</a-button>
            </a-list-item>
            <a-list-item>
              <a-list-item-meta :title="$t('profile.emailBinding')" :description="userInfo.email || $t('profile.notBound')" />
              <a-button @click="onChangeEmail">{{ $t('profile.changeEmail') }}</a-button>
            </a-list-item>
          </a-list>
        </a-card>

        <a-card :title="$t('profile.preferences')" :bordered="false" class="section-card" style="margin-top:20px">
          <a-list :split="false">
            <a-list-item>
              <a-list-item-meta :title="$t('profile.theme')" :description="$t('profile.themeDesc')" />
              <a-switch
                :checked="isDark"
                :checked-children="$t('profile.dark')"
                :un-checked-children="$t('profile.light')"
                @change="onThemeToggle"
              />
            </a-list-item>
            <a-list-item>
              <a-list-item-meta :title="$t('profile.language')" :description="$t('profile.languageDesc')" />
              <a-select :value="locale" style="width:120px" @change="onLocaleChange">
                <a-select-option value="zh-CN">简体中文</a-select-option>
                <a-select-option value="en-US">English</a-select-option>
              </a-select>
            </a-list-item>
          </a-list>
        </a-card>

        <a-card :title="$t('profile.operationLog')" :bordered="false" class="section-card" style="margin-top:20px">
          <a-empty :description="$t('profile.noOperationLog')" :image-style="{ height: '60px' }" />
        </a-card>
      </a-col>
    </a-row>

    <a-modal
      v-model:open="showPasswordModal"
      :title="$t('profile.changePasswordTitle')"
      @ok="handlePasswordChange"
      @cancel="showPasswordModal = false"
      :confirm-loading="passwordLoading"
    >
      <a-form :model="passwordForm" layout="vertical">
        <a-form-item :label="$t('profile.oldPassword')" required>
          <a-input-password v-model:value="passwordForm.oldPassword" :placeholder="$t('profile.passwordPlaceholder')" />
        </a-form-item>
        <a-form-item :label="$t('profile.newPassword')" required>
          <a-input-password v-model:value="passwordForm.newPassword" :placeholder="$t('profile.newPasswordPlaceholder')" />
        </a-form-item>
        <a-form-item :label="$t('profile.confirmPassword')" required>
          <a-input-password v-model:value="passwordForm.confirmPassword" :placeholder="$t('profile.confirmPasswordPlaceholder')" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { message } from 'ant-design-vue'
import {
  MailOutlined, IdcardOutlined, CalendarOutlined, CheckCircleOutlined
} from '@ant-design/icons-vue'
import api from '../api'

const { t, locale: i18nLocale } = useI18n()

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
  const role = userInfo.value.role
  if (!role) return role
  const key = 'profile.role_' + role.toLowerCase()
  const translated = t(key)
  return translated !== key ? translated : role
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
  message.info(t('profile.emailComingSoon'))
}

function onLocaleChange(val) {
  locale.value = val
  i18nLocale.value = val
  localStorage.setItem('codeatlas_locale', val)
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
    message.warning(t('profile.fillAllFields'))
    return
  }
  if (passwordForm.newPassword !== passwordForm.confirmPassword) {
    message.error(t('profile.passwordMismatch'))
    return
  }
  if (passwordForm.newPassword.length < 6) {
    message.warning(t('profile.passwordTooShort'))
    return
  }
  passwordLoading.value = true
  try {
    await api.put('/auth/password', {
      oldPassword: passwordForm.oldPassword,
      newPassword: passwordForm.newPassword
    })
    message.success(t('profile.passwordSuccess'))
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
  background: linear-gradient(135deg, #1677ff, #0050b3);
}

.profile-username {
  margin: 0;
  font-size: 22px;
  font-weight: 600;
  color: var(--color-text-primary);
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
  width: 70px;
  flex-shrink: 0;
}

.info-value {
  font-size: 14px;
  color: var(--color-text-primary);
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

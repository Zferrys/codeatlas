<template>
  <div class="login-form-wrap">
    <h2 class="form-title">{{ $t('auth.welcomeBack') }}</h2>
    <p class="form-subtitle">{{ $t('auth.loginSubtitle') }}</p>

    <a-form
      :model="form"
      :rules="rules"
      @finish="handleLogin"
      layout="vertical"
      class="login-form"
    >
      <a-form-item name="username">
        <a-input
          v-model:value="form.username"
          size="large"
          :placeholder="$t('auth.usernamePlaceholder')"
          autocomplete="username"
        >
          <template #prefix><UserOutlined style="color:#bfbfbf" /></template>
        </a-input>
      </a-form-item>

      <a-form-item name="password">
        <a-input-password
          v-model:value="form.password"
          size="large"
          :placeholder="$t('auth.passwordPlaceholder')"
          autocomplete="current-password"
        >
          <template #prefix><LockOutlined style="color:#bfbfbf" /></template>
        </a-input-password>
      </a-form-item>

      <div class="form-extra">
        <a-checkbox v-model:checked="rememberMe">{{ $t('auth.rememberMe') }}</a-checkbox>
        <a class="forgot-link" @click="onForgotPassword">{{ $t('auth.forgotPassword') }}</a>
      </div>

      <a-form-item>
        <a-button
          type="primary"
          html-type="submit"
          size="large"
          block
          :loading="loading"
          class="submit-btn"
        >
          {{ $t('auth.login') }}
        </a-button>
      </a-form-item>
    </a-form>

    <div class="switch-auth">
      {{ $t('auth.noAccount') }}
      <router-link to="/register">{{ $t('auth.registerNow') }}</router-link>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '../stores/auth'
import { message } from 'ant-design-vue'
import { UserOutlined, LockOutlined } from '@ant-design/icons-vue'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const { t } = useI18n()
const loading = ref(false)
const rememberMe = ref(false)

const form = reactive({
  username: '',
  password: ''
})

const rules = {
  username: [{ required: true, message: t('auth.usernameRequired'), trigger: 'blur' }],
  password: [{ required: true, message: t('auth.passwordRequired'), trigger: 'blur' }]
}

async function handleLogin() {
  loading.value = true
  try {
    await authStore.login(form.username, form.password)
    message.success(t('auth.loginSuccess'))
    const redirect = route.query.redirect || '/dashboard'
    router.push(redirect)
  } catch (e) {
    // handled by axios interceptor
  } finally {
    loading.value = false
  }
}

function onForgotPassword() {
  message.info(t('auth.contactAdmin'))
}
</script>

<style scoped>
.login-form-wrap {
  width: 100%;
}

.form-title {
  font-size: 28px;
  font-weight: 700;
  color: #1a1a2e;
  margin: 0 0 8px;
}

.form-subtitle {
  color: #999;
  font-size: 14px;
  margin: 0 0 36px;
}

.form-extra {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
}

.forgot-link {
  color: #1677ff;
  font-size: 13px;
  cursor: pointer;
}

.submit-btn {
  height: 44px;
  font-size: 16px;
  letter-spacing: 4px;
  border-radius: 8px;
  background: linear-gradient(135deg, #1677ff, #0050b3);
  border: none;
}

.submit-btn:hover {
  background: linear-gradient(135deg, #0958d9, #003a8c);
}

.switch-auth {
  text-align: center;
  margin-top: 24px;
  font-size: 14px;
  color: #999;
}

.switch-auth a {
  color: #1677ff;
  font-weight: 500;
}
</style>

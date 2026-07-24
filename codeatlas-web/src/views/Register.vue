<template>
  <div class="register-form-wrap">
    <h2 class="form-title">{{ $t('auth.createAccount') }}</h2>
    <p class="form-subtitle">{{ $t('auth.registerSubtitle') }}</p>

    <a-form
      :model="form"
      :rules="rules"
      @finish="handleRegister"
      layout="vertical"
    >
      <a-form-item name="username">
        <a-input
          v-model:value="form.username"
          size="large"
          :placeholder="$t('auth.usernameHint')"
          autocomplete="username"
        >
          <template #prefix><UserOutlined style="color:#bfbfbf" /></template>
        </a-input>
      </a-form-item>

      <a-form-item name="email">
        <a-input
          v-model:value="form.email"
          size="large"
          :placeholder="$t('auth.emailPlaceholder')"
          autocomplete="email"
        >
          <template #prefix><MailOutlined style="color:#bfbfbf" /></template>
        </a-input>
      </a-form-item>

      <a-form-item name="password">
        <a-input-password
          v-model:value="form.password"
          size="large"
          :placeholder="$t('auth.passwordHint')"
          autocomplete="new-password"
        >
          <template #prefix><LockOutlined style="color:#bfbfbf" /></template>
        </a-input-password>
      </a-form-item>

      <a-form-item name="confirmPassword">
        <a-input-password
          v-model:value="form.confirmPassword"
          size="large"
          :placeholder="$t('auth.confirmPasswordHint')"
          autocomplete="new-password"
        >
          <template #prefix><LockOutlined style="color:#bfbfbf" /></template>
        </a-input-password>
      </a-form-item>

      <a-form-item>
        <a-button
          type="primary"
          html-type="submit"
          size="large"
          block
          :loading="loading"
          class="submit-btn"
        >
          {{ $t('auth.register') }}
        </a-button>
      </a-form-item>
    </a-form>

    <div class="switch-auth">
      {{ $t('auth.hasAccount') }}
      <router-link to="/login">{{ $t('auth.backToLogin') }}</router-link>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '../stores/auth'
import { message } from 'ant-design-vue'
import { UserOutlined, MailOutlined, LockOutlined } from '@ant-design/icons-vue'

const router = useRouter()
const authStore = useAuthStore()
const { t } = useI18n()
const loading = ref(false)

const form = reactive({
  username: '',
  email: '',
  password: '',
  confirmPassword: ''
})

const validateConfirmPassword = (rule, value) => {
  if (value !== form.password) {
    return Promise.reject(t('auth.passwordNotMatch'))
  }
  return Promise.resolve()
}

const rules = {
  username: [
    { required: true, message: t('auth.usernameRequired'), trigger: 'blur' },
    { min: 3, max: 50, message: t('auth.usernameLength'), trigger: 'blur' }
  ],
  email: [{ type: 'email', message: t('auth.emailInvalid'), trigger: 'blur' }],
  password: [
    { required: true, message: t('auth.passwordRequired'), trigger: 'blur' },
    { min: 6, message: t('auth.passwordMinLength'), trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: t('auth.confirmRequired'), trigger: 'blur' },
    { validator: validateConfirmPassword, trigger: 'blur' }
  ]
}

async function handleRegister() {
  loading.value = true
  try {
    await authStore.register(form.username, form.password, form.email || null)
    message.success(t('auth.registerSuccess'))
    router.push('/dashboard')
  } catch (e) {
    // handled by axios interceptor
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.register-form-wrap {
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

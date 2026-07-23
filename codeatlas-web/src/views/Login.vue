<template>
  <div class="login-form-wrap">
    <h2 class="form-title">欢迎回来</h2>
    <p class="form-subtitle">登录您的 CodeAtlas 账号</p>

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
          placeholder="用户名"
          autocomplete="username"
        >
          <template #prefix><UserOutlined style="color:#bfbfbf" /></template>
        </a-input>
      </a-form-item>

      <a-form-item name="password">
        <a-input-password
          v-model:value="form.password"
          size="large"
          placeholder="密码"
          autocomplete="current-password"
        >
          <template #prefix><LockOutlined style="color:#bfbfbf" /></template>
        </a-input-password>
      </a-form-item>

      <div class="form-extra">
        <a-checkbox v-model:checked="rememberMe">记住登录</a-checkbox>
        <a class="forgot-link" @click="onForgotPassword">忘记密码？</a>
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
          登 录
        </a-button>
      </a-form-item>
    </a-form>

    <div class="switch-auth">
      还没有账号？
      <router-link to="/register">立即注册</router-link>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { message } from 'ant-design-vue'
import { UserOutlined, LockOutlined } from '@ant-design/icons-vue'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const loading = ref(false)
const rememberMe = ref(false)

const form = reactive({
  username: '',
  password: ''
})

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function handleLogin() {
  loading.value = true
  try {
    await authStore.login(form.username, form.password)
    message.success('登录成功')
    const redirect = route.query.redirect || '/dashboard'
    router.push(redirect)
  } catch (e) {
    // handled by axios interceptor
  } finally {
    loading.value = false
  }
}

function onForgotPassword() {
  message.info('请联系管理员重置密码')
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
  color: #667eea;
  font-size: 13px;
  cursor: pointer;
}

.submit-btn {
  height: 44px;
  font-size: 16px;
  letter-spacing: 4px;
  border-radius: 8px;
  background: linear-gradient(135deg, #667eea, #764ba2);
  border: none;
}

.submit-btn:hover {
  background: linear-gradient(135deg, #5a6fd6, #6a3f96);
}

.switch-auth {
  text-align: center;
  margin-top: 24px;
  font-size: 14px;
  color: #999;
}

.switch-auth a {
  color: #667eea;
  font-weight: 500;
}
</style>

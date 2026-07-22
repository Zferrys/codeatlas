<template>
  <div class="register-container">
    <div class="register-card">
      <h1 class="register-title">创建账号</h1>
      <p class="register-subtitle">加入 CodeAtlas，探索代码架构之美</p>

      <a-form
        :model="form"
        :rules="rules"
        @finish="handleRegister"
        layout="vertical"
      >
        <a-form-item label="用户名" name="username">
          <a-input v-model:value="form.username" size="large" placeholder="3-50 位字符" />
        </a-form-item>

        <a-form-item label="邮箱（选填）" name="email">
          <a-input v-model:value="form.email" size="large" placeholder="example@mail.com" />
        </a-form-item>

        <a-form-item label="密码" name="password">
          <a-input-password v-model:value="form.password" size="large" placeholder="至少 6 位" />
        </a-form-item>

        <a-form-item label="确认密码" name="confirmPassword">
          <a-input-password v-model:value="form.confirmPassword" size="large" placeholder="再次输入密码" />
        </a-form-item>

        <a-form-item>
          <a-button
            type="primary"
            html-type="submit"
            size="large"
            block
            :loading="loading"
          >
            注册
          </a-button>
        </a-form-item>
      </a-form>

      <div class="register-footer">
        已有账号？
        <router-link to="/login">返回登录</router-link>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { message } from 'ant-design-vue'

const router = useRouter()
const authStore = useAuthStore()
const loading = ref(false)

const form = reactive({
  username: '',
  email: '',
  password: '',
  confirmPassword: ''
})

const validateConfirmPassword = (rule, value) => {
  if (value !== form.password) {
    return Promise.reject('两次输入的密码不一致')
  }
  return Promise.resolve()
}

const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 50, message: '用户名长度 3-50 位', trigger: 'blur' }
  ],
  email: [{ type: 'email', message: '请输入有效的邮箱地址', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码至少 6 位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请确认密码', trigger: 'blur' },
    { validator: validateConfirmPassword, trigger: 'blur' }
  ]
}

async function handleRegister() {
  loading.value = true
  try {
    await authStore.register(form.username, form.password, form.email || null)
    message.success('注册成功')
    router.push('/dashboard')
  } catch (e) {
    // error handled by axios interceptor
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.register-container {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

.register-card {
  width: 440px;
  padding: 40px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.15);
}

.register-title {
  text-align: center;
  font-size: 24px;
  font-weight: 700;
  color: #1a1a2e;
  margin-bottom: 8px;
}

.register-subtitle {
  text-align: center;
  color: #666;
  margin-bottom: 32px;
  font-size: 14px;
}

.register-footer {
  text-align: center;
  margin-top: 16px;
  font-size: 14px;
  color: #999;
}

.register-footer a {
  color: #667eea;
}
</style>

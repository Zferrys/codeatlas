<template>
  <div class="register-form-wrap">
    <h2 class="form-title">创建账号</h2>
    <p class="form-subtitle">加入 CodeAtlas，探索代码架构之美</p>

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
          placeholder="用户名（3-50 位字符）"
          autocomplete="username"
        >
          <template #prefix><UserOutlined style="color:#bfbfbf" /></template>
        </a-input>
      </a-form-item>

      <a-form-item name="email">
        <a-input
          v-model:value="form.email"
          size="large"
          placeholder="邮箱（选填）"
          autocomplete="email"
        >
          <template #prefix><MailOutlined style="color:#bfbfbf" /></template>
        </a-input>
      </a-form-item>

      <a-form-item name="password">
        <a-input-password
          v-model:value="form.password"
          size="large"
          placeholder="密码（至少 6 位）"
          autocomplete="new-password"
        >
          <template #prefix><LockOutlined style="color:#bfbfbf" /></template>
        </a-input-password>
      </a-form-item>

      <a-form-item name="confirmPassword">
        <a-input-password
          v-model:value="form.confirmPassword"
          size="large"
          placeholder="确认密码"
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
          注 册
        </a-button>
      </a-form-item>
    </a-form>

    <div class="switch-auth">
      已有账号？
      <router-link to="/login">返回登录</router-link>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { message } from 'ant-design-vue'
import { UserOutlined, MailOutlined, LockOutlined } from '@ant-design/icons-vue'

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

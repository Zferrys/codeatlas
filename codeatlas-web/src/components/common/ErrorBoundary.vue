<template>
  <div v-if="hasError" class="error-boundary">
    <div class="error-card">
      <div class="error-icon">
        <svg width="80" height="80" viewBox="0 0 24 24" fill="none">
          <circle cx="12" cy="12" r="10" stroke="#ff4d4f" stroke-width="1.5"/>
          <path d="M12 8v4M12 16h.01" stroke="#ff4d4f" stroke-width="2" stroke-linecap="round"/>
        </svg>
      </div>
      <h2 class="error-title">页面出现了异常</h2>
      <p class="error-desc">很抱歉，页面渲染过程中遇到了错误。请尝试刷新页面，或返回首页。</p>
      <div class="error-actions">
        <a-button type="primary" @click="handleRefresh">刷新页面</a-button>
        <a-button @click="handleGoHome">返回首页</a-button>
      </div>
      <details class="error-details" v-if="error">
        <summary>错误详情</summary>
        <pre>{{ error.message }}</pre>
        <pre class="error-stack">{{ error.stack }}</pre>
      </details>
    </div>
  </div>
  <slot v-else />
</template>

<script setup>
import { ref, onErrorCaptured } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
const hasError = ref(false)
const error = ref(null)

onErrorCaptured((err, instance, info) => {
  console.error('[ErrorBoundary]', err, info)
  error.value = err
  hasError.value = true
  return false // prevent propagation
})

function handleRefresh() {
  hasError.value = false
  error.value = null
}

function handleGoHome() {
  hasError.value = false
  error.value = null
  router.push('/dashboard')
}
</script>

<style scoped>
.error-boundary {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 400px;
  padding: 40px;
}

.error-card {
  text-align: center;
  max-width: 480px;
}

.error-icon {
  margin-bottom: 24px;
  color: #ff4d4f;
}

.error-title {
  font-size: 22px;
  font-weight: 600;
  color: #1a1a2e;
  margin: 0 0 12px;
}

.error-desc {
  color: #666;
  font-size: 14px;
  line-height: 1.6;
  margin: 0 0 24px;
}

.error-actions {
  display: flex;
  gap: 12px;
  justify-content: center;
}

.error-details {
  margin-top: 24px;
  text-align: left;
  background: #fafafa;
  border-radius: 8px;
  padding: 16px;
  cursor: pointer;
}

.error-details summary {
  font-size: 13px;
  color: #999;
  margin-bottom: 8px;
}

.error-details pre {
  font-size: 12px;
  color: #666;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
}

.error-stack {
  margin-top: 8px;
  color: #999;
  max-height: 200px;
  overflow-y: auto;
}
</style>

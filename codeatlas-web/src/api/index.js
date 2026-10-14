import axios from 'axios'
import { message } from 'ant-design-vue'
import { report } from '../utils/errorReporter'
import { useAuthStore } from '../stores/auth'

const api = axios.create({
  baseURL: '/api/v1',
  timeout: 120000
})

// pinia store 与 localStorage 同步清理：只清 localStorage 会残留 store 状态，
// 过期会话跳登录页时会被路由守卫按「已登录」重定向回 Dashboard
function clearAuthState() {
  try {
    useAuthStore().clearAuth()
  } catch (e) {
    localStorage.removeItem('codeatlas_token')
    localStorage.removeItem('codeatlas_user')
  }
}

// Request interceptor — attach JWT token + set Content-Type for JSON
api.interceptors.request.use(
  config => {
    const token = localStorage.getItem('codeatlas_token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    // 仅对非 FormData 请求自动设置 JSON Content-Type（FormData 由浏览器自动设置 boundary）
    if (config.data && !(config.data instanceof FormData)) {
      config.headers['Content-Type'] = 'application/json'
    }
    return config
  },
  error => Promise.reject(error)
)

// Response interceptor — unified error handling
api.interceptors.response.use(
  response => response,
  error => {
    // 用户主动终止 AI 任务等取消请求：静默透传，避免误报「网络异常」
    if (error?.code === 'ERR_CANCELED' || error?.name === 'CanceledError') {
      return Promise.reject(error)
    }
    const { response } = error
    if (response && response.status >= 400) {
      report({
        type: 'api_error',
        url: error.config?.url,
        status: response.status,
        traceId: response.headers?.['x-trace-id'],
        timestamp: Date.now()
      })
    }
    if (response) {
      const { status, data } = response
      switch (status) {
        case 401:
          clearAuthState()
          window.location.hash = '#/login'
          message.error('登录已过期，请重新登录')
          break
        case 403:
          message.error('无权限访问')
          break
        case 404:
          message.error('资源不存在')
          break
        case 500:
          message.error(data?.message || '服务器内部错误')
          break
        default:
          message.error(data?.message || `请求失败 (${status})`)
      }
    } else {
      message.error('网络异常，请检查连接')
    }
    return Promise.reject(error)
  }
)

export default api

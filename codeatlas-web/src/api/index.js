import axios from 'axios'
import { message } from 'ant-design-vue'

const api = axios.create({
  baseURL: '/api/v1',
  timeout: 30000
})

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
    const { response } = error
    if (response) {
      const { status, data } = response
      switch (status) {
        case 401:
          localStorage.removeItem('codeatlas_token')
          localStorage.removeItem('codeatlas_user')
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

import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import api from '../api'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('codeatlas_token') || '')
  const user = ref(JSON.parse(localStorage.getItem('codeatlas_user') || 'null'))

  const isLoggedIn = computed(() => !!token.value)

  function setAuth(newToken, newUser) {
    token.value = newToken
    user.value = newUser
    localStorage.setItem('codeatlas_token', newToken)
    localStorage.setItem('codeatlas_user', JSON.stringify(newUser))
  }

  function clearAuth() {
    token.value = ''
    user.value = null
    localStorage.removeItem('codeatlas_token')
    localStorage.removeItem('codeatlas_user')
  }

  async function login(username, password) {
    const res = await api.post('/auth/login', { username, password })
    const data = res.data.data
    setAuth(data.token, { id: data.userId, username: data.username, role: data.role })
    return data
  }

  async function register(username, password, email) {
    const res = await api.post('/auth/register', { username, password, email })
    const data = res.data.data
    setAuth(data.token, { id: data.userId, username: data.username, role: data.role })
    return data
  }

  function logout() {
    clearAuth()
  }

  return { token, user, isLoggedIn, login, register, logout, setAuth, clearAuth }
})

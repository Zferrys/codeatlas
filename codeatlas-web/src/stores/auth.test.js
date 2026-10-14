import { describe, it, expect, vi, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const { postMock } = vi.hoisted(() => ({ postMock: vi.fn() }))

vi.mock('../api', () => ({ default: { post: postMock } }))

import { useAuthStore } from './auth'

describe('useAuthStore', () => {
  beforeEach(() => {
    localStorage.clear()
    postMock.mockReset()
    setActivePinia(createPinia())
  })

  it('useAuthStore_whenLocalStorageEmpty_shouldStartLoggedOut', () => {
    const store = useAuthStore()

    expect(store.token).toBe('')
    expect(store.user).toBeNull()
    expect(store.isLoggedIn).toBe(false)
  })

  it('useAuthStore_whenLocalStorageSeeded_shouldRestoreTokenAndUser', () => {
    localStorage.setItem('codeatlas_token', 'persisted-token')
    localStorage.setItem('codeatlas_user', JSON.stringify({ id: 7, username: 'zferrys', role: 'ADMIN' }))

    const store = useAuthStore()

    expect(store.token).toBe('persisted-token')
    expect(store.user).toEqual({ id: 7, username: 'zferrys', role: 'ADMIN' })
    expect(store.isLoggedIn).toBe(true)
  })

  it('setAuth_whenCalled_shouldUpdateStateAndPersistBothKeys', () => {
    const store = useAuthStore()

    store.setAuth('tok-1', { id: 1, username: 'alice', role: 'DEVELOPER' })

    expect(store.token).toBe('tok-1')
    expect(store.user).toEqual({ id: 1, username: 'alice', role: 'DEVELOPER' })
    expect(store.isLoggedIn).toBe(true)
    expect(localStorage.getItem('codeatlas_token')).toBe('tok-1')
    expect(JSON.parse(localStorage.getItem('codeatlas_user'))).toEqual({ id: 1, username: 'alice', role: 'DEVELOPER' })
  })

  it('clearAuth_whenCalled_shouldResetStateAndRemoveBothKeys', () => {
    const store = useAuthStore()
    store.setAuth('tok-2', { id: 2, username: 'bob', role: 'VIEWER' })

    store.clearAuth()

    expect(store.token).toBe('')
    expect(store.user).toBeNull()
    expect(store.isLoggedIn).toBe(false)
    expect(localStorage.getItem('codeatlas_token')).toBeNull()
    expect(localStorage.getItem('codeatlas_user')).toBeNull()
  })

  it('login_whenApiSucceeds_shouldPostCredentialsAndSetAuth', async () => {
    postMock.mockResolvedValue({
      data: { data: { token: 'jwt-login', userId: 9, username: 'carol', role: 'ARCHITECT' } }
    })
    const store = useAuthStore()

    const result = await store.login('carol', 'secret')

    expect(postMock).toHaveBeenCalledTimes(1)
    expect(postMock).toHaveBeenCalledWith('/auth/login', { username: 'carol', password: 'secret' })
    expect(result).toEqual({ token: 'jwt-login', userId: 9, username: 'carol', role: 'ARCHITECT' })
    expect(store.token).toBe('jwt-login')
    expect(store.user).toEqual({ id: 9, username: 'carol', role: 'ARCHITECT' })
    expect(localStorage.getItem('codeatlas_token')).toBe('jwt-login')
  })

  it('login_whenApiFails_shouldPropagateErrorWithoutTouchingState', async () => {
    postMock.mockRejectedValue(new Error('401 unauthorized'))
    const store = useAuthStore()

    await expect(store.login('carol', 'wrong')).rejects.toThrow('401 unauthorized')
    expect(store.isLoggedIn).toBe(false)
    expect(localStorage.getItem('codeatlas_token')).toBeNull()
  })

  it('register_whenApiSucceeds_shouldPostAllFieldsAndSetAuth', async () => {
    postMock.mockResolvedValue({
      data: { data: { token: 'jwt-register', userId: 11, username: 'dave', role: 'DEVELOPER' } }
    })
    const store = useAuthStore()

    const result = await store.register('dave', 'secret6', 'dave@example.com')

    expect(postMock).toHaveBeenCalledTimes(1)
    expect(postMock).toHaveBeenCalledWith('/auth/register', {
      username: 'dave',
      password: 'secret6',
      email: 'dave@example.com'
    })
    expect(result.token).toBe('jwt-register')
    expect(store.user).toEqual({ id: 11, username: 'dave', role: 'DEVELOPER' })
    expect(store.isLoggedIn).toBe(true)
  })

  it('logout_whenLoggedIn_shouldClearStateAndStorage', () => {
    const store = useAuthStore()
    store.setAuth('tok-3', { id: 3, username: 'erin', role: 'VIEWER' })

    store.logout()

    expect(store.token).toBe('')
    expect(store.user).toBeNull()
    expect(store.isLoggedIn).toBe(false)
    expect(localStorage.getItem('codeatlas_token')).toBeNull()
    expect(localStorage.getItem('codeatlas_user')).toBeNull()
  })
})

import axios from 'axios'
import { useAuthStore } from '../store/authStore'

export const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api',
})

api.interceptors.request.use((config) => {
  const { accessToken: token, userId } = useAuthStore.getState()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
    ;(config as typeof config & { _authUserId?: number })._authUserId = userId ?? undefined
  }
  return config
})

let refreshing: { token: string; promise: Promise<string> } | null = null

api.interceptors.response.use(
  (res) => res,
  async (error) => {
    const original = error.config as (typeof error.config & { _retry?: boolean; _authUserId?: number }) | undefined
    if (!original) return Promise.reject(error)
    if (error.response?.status === 401 && !original._retry && !original.url?.startsWith('/auth/')) {
      original._retry = true
      const { refreshToken, userId, accessToken, logout } = useAuthStore.getState()
      if (original._authUserId && original._authUserId !== userId) return Promise.reject(error)
      if (accessToken && original.headers?.Authorization !== `Bearer ${accessToken}`) {
        original.headers.Authorization = `Bearer ${accessToken}`
        return api(original)
      }
      if (!refreshToken) {
        logout()
        return Promise.reject(error)
      }
      try {
        if (!refreshing || refreshing.token !== refreshToken) {
          // Use bare axios so a 401 from refresh cannot enter this interceptor
          // and wait on its own promise.
          const promise = axios
            .post(`${api.defaults.baseURL}/auth/refresh`, { refreshToken })
            .then((res) => {
              const current = useAuthStore.getState()
              if (current.refreshToken !== refreshToken || current.userId !== userId) {
                throw new Error('Account changed during token refresh')
              }
              current.setAuth(res.data)
              return res.data.accessToken as string
            })
            .finally(() => {
              if (refreshing?.promise === promise) refreshing = null
            })
          refreshing = { token: refreshToken, promise }
        }
        const newToken = await refreshing.promise
        if (newToken) {
          original.headers.Authorization = `Bearer ${newToken}`
          return api(original)
        }
      } catch {
        const current = useAuthStore.getState()
        if (current.refreshToken === refreshToken && current.userId === userId) current.logout()
      }
    }
    return Promise.reject(error)
  },
)

export function apiErrorMessage(err: unknown): string {
  const anyErr = err as any
  return anyErr?.response?.data?.message || anyErr?.message || 'Something went wrong. Please try again.'
}

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import axios, { AxiosError, type AxiosAdapter, type InternalAxiosRequestConfig } from 'axios'

vi.hoisted(() => {
  const makeStorage = () => {
    const values = new Map<string, string>()
    return {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => { values.set(key, value) },
      removeItem: (key: string) => { values.delete(key) },
      clear: () => { values.clear() },
    }
  }
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: makeStorage(),
  })
  Object.defineProperty(globalThis, 'sessionStorage', { configurable: true, value: makeStorage() })
})
import { api } from '../lib/api'
import { mergeGuestCart } from '../lib/mergeGuestCart'
import { clearPendingOrderId, readPendingOrderId, savePendingOrderId } from '../lib/checkoutSession'
import { queryClient } from '../lib/queryClient'
import { useAuthStore } from '../store/authStore'
import { useGuestCartStore } from '../store/cartStore'

describe('shopping state across account changes', () => {
  const originalAdapter = api.defaults.adapter

  beforeEach(() => {
    queryClient.clear()
    useGuestCartStore.setState({ items: [] })
    useAuthStore.getState().logout()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    api.defaults.adapter = originalAdapter
    queryClient.clear()
  })

  it('removes cached private data when another account signs in or logs out', () => {
    const first = { accessToken: 'first', refreshToken: 'refresh-1', userId: 1, fullName: 'First', role: 'ROLE_CUSTOMER' }
    useAuthStore.getState().setAuth(first)
    queryClient.setQueryData(['addresses'], [{ recipientName: 'First' }])

    useAuthStore.getState().setAuth({ ...first, accessToken: 'renewed' })
    expect(queryClient.getQueryData(['addresses'])).toBeDefined()

    useAuthStore.getState().setAuth({ ...first, userId: 2, fullName: 'Second' })
    expect(queryClient.getQueryData(['addresses'])).toBeUndefined()

    queryClient.setQueryData(['my-orders'], [{ id: 42 }])
    useAuthStore.getState().logout()
    expect(queryClient.getQueryData(['my-orders'])).toBeUndefined()
  })

  it('keeps only failed guest items for a safe retry after login', async () => {
    useGuestCartStore.setState({ items: [
      { productId: 1, name: 'Available', unitPrice: 100, quantity: 2 },
      { productId: 2, name: 'Unavailable', unitPrice: 200, quantity: 1 },
    ] })
    const post = vi.spyOn(api, 'post').mockImplementation(async (_url, item) => {
      if ((item as { productId: number }).productId === 2) throw new Error('No stock')
      return { data: {} } as never
    })

    expect(await mergeGuestCart()).toEqual({ added: 1, failed: 1 })
    expect(post).toHaveBeenCalledWith('/cart/items', { productId: 1, variantId: undefined, quantity: 2 })
    expect(useGuestCartStore.getState().items.map((item) => item.productId)).toEqual([2])
  })

  it('refreshes concurrent expired-token requests once and retries both', async () => {
    const first = { accessToken: 'expired', refreshToken: 'refresh-1', userId: 1, fullName: 'First', role: 'ROLE_CUSTOMER' }
    useAuthStore.getState().setAuth(first)
    const renewed = { ...first, accessToken: 'renewed', refreshToken: 'refresh-2' }
    const refresh = vi.spyOn(axios, 'post').mockResolvedValue({ data: renewed } as never)
    api.defaults.adapter = (async (config: InternalAxiosRequestConfig) => {
      if (config.headers.Authorization === 'Bearer expired') {
        const response = { config, data: {}, status: 401, statusText: 'Unauthorized', headers: {} }
        throw new AxiosError('unauthorized', 'ERR_BAD_REQUEST', config, undefined, response)
      }
      return { config, data: { ok: true }, status: 200, statusText: 'OK', headers: {} }
    }) as AxiosAdapter

    const responses = await Promise.all([api.get('/cart'), api.get('/addresses')])
    expect(responses.map((res) => res.data.ok)).toEqual([true, true])
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(useAuthStore.getState().accessToken).toBe('renewed')
  })

  it('ends a failed refresh instead of recursing into the same interceptor', async () => {
    useAuthStore.getState().setAuth({ accessToken: 'expired', refreshToken: 'bad', userId: 1, fullName: 'First', role: 'ROLE_CUSTOMER' })
    vi.spyOn(axios, 'post').mockRejectedValue(new Error('refresh rejected'))
    api.defaults.adapter = (async (config: InternalAxiosRequestConfig) => {
      const response = { config, data: {}, status: 401, statusText: 'Unauthorized', headers: {} }
      throw new AxiosError('unauthorized', 'ERR_BAD_REQUEST', config, undefined, response)
    }) as AxiosAdapter

    await expect(api.get('/cart')).rejects.toThrow('unauthorized')
    expect(useAuthStore.getState().accessToken).toBeNull()
  })

  it('restores a pending order after the tab session ends and scopes it to the account', () => {
    savePendingOrderId(1, 42)
    sessionStorage.clear()
    expect(readPendingOrderId(1)).toBe(42)
    expect(readPendingOrderId(2)).toBeNull()
    clearPendingOrderId(1)
    expect(readPendingOrderId(1)).toBeNull()
  })
})

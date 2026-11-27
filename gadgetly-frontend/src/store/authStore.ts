import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import { queryClient } from '../lib/queryClient'

interface AuthState {
  accessToken: string | null
  refreshToken: string | null
  userId: number | null
  fullName: string | null
  role: 'ROLE_CUSTOMER' | 'ROLE_ADMIN' | null
  setAuth: (data: { accessToken: string; refreshToken: string; userId: number; fullName: string; role: string }) => void
  logout: () => void
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      accessToken: null,
      refreshToken: null,
      userId: null,
      fullName: null,
      role: null,
      setAuth: (data) => {
        // Query keys are shared between visitors. Never show one account's cached
        // cart, addresses, orders or profile after another account signs in.
        if (useAuthStore.getState().userId !== data.userId) queryClient.clear()
        set({
          accessToken: data.accessToken,
          refreshToken: data.refreshToken,
          userId: data.userId,
          fullName: data.fullName,
          role: data.role as 'ROLE_CUSTOMER' | 'ROLE_ADMIN',
        })
      },
      logout: () => {
        queryClient.clear()
        set({ accessToken: null, refreshToken: null, userId: null, fullName: null, role: null })
      },
    }),
    { name: 'gadgetly-auth' },
  ),
)

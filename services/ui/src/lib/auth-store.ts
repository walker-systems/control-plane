// Auth state — Zustand store persisted to localStorage. Access token
// and refresh token both live here for now. Simpler than an httpOnly
// cookie flow; suitable for a demo where the API returns tokens in
// the response body.
import { create } from 'zustand'
import { persist } from 'zustand/middleware'

export interface AuthUser {
  email: string
  roles: string[]
}

interface AuthState {
  accessToken: string | null
  refreshToken: string | null
  user: AuthUser | null
  setSession: (params: { accessToken: string; refreshToken: string; user: AuthUser }) => void
  setUser: (user: AuthUser) => void
  clear: () => void
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      accessToken: null,
      refreshToken: null,
      user: null,
      setSession: ({ accessToken, refreshToken, user }) =>
        set({ accessToken, refreshToken, user }),
      setUser: (user) => set({ user }),
      clear: () => set({ accessToken: null, refreshToken: null, user: null }),
    }),
    { name: 'cp-auth' },
  ),
)

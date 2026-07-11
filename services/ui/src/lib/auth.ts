import { api, ApiError } from '@/lib/api'
import { useAuthStore } from '@/lib/auth-store'
import { getMe } from '@/lib/users'

// One-shot on app boot: if a persisted session exists, refresh the
// user record from /api/users/me. Two reasons we need this:
//
//  1. Legacy sessions in localStorage (persisted before we added
//     roles) hydrate as { email } with no roles. Without this call,
//     role-gated UI (audit trail) would stay hidden for privileged
//     users until they signed out and back in.
//  2. Server-side role changes (a promote/revoke by an admin) take
//     effect on the next reload rather than the next full re-login.
//
// If the token is invalid the API returns 401 — we clear the session
// so the router lands the user on /login rather than showing a
// half-authenticated shell.
export async function hydrateSession(): Promise<void> {
  const token = useAuthStore.getState().accessToken
  if (!token) return
  try {
    const me = await getMe()
    useAuthStore.getState().setUser({ email: me.email, roles: me.roles })
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) {
      useAuthStore.getState().clear()
    } else {
      console.warn('hydrateSession failed', e)
    }
  }
}

// Full sign-out: revoke the refresh token server-side, then wipe
// local state. The API call is fire-and-forget from the UI's
// perspective — if the network is down or the token is already
// revoked we still want the user out locally. Any real error is
// logged, not surfaced.
export async function signOut(): Promise<void> {
  const refreshToken = useAuthStore.getState().refreshToken
  if (refreshToken) {
    try {
      await api('/api/auth/logout', {
        method: 'POST',
        body: { refreshToken },
        auth: false,
      })
    } catch (e) {
      // Don't block local sign-out on a failed revoke — the user's
      // intent is clear. Log so anyone reading the console knows.
      console.warn('logout revoke failed; clearing local session anyway', e)
    }
  }
  useAuthStore.getState().clear()
}

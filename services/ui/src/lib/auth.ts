import { api } from '@/lib/api'
import { useAuthStore } from '@/lib/auth-store'

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

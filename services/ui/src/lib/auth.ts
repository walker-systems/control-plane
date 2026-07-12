import { api } from '@/lib/api'
import { useAuthStore } from '@/lib/auth-store'
import { decodeJwtRoles, isJwtExpired } from '@/lib/jwt'

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

// Refresh the persisted user record's roles from the access token.
// Called once on app boot from main.tsx. Two cases this covers:
//
//   1. Legacy sessions in localStorage (persisted before the auth
//      user record grew a roles field) rehydrate with roles: [] and
//      would otherwise never see role-gated UI.
//   2. A recently-issued token whose claims we haven't yet read.
//
// Roles come from the JWT `roles` claim rather than /api/users/me
// so the UI's gate matches how the API's AuthenticatedCaller
// authorizes: same source of truth, no drift where /me shows a
// promoted role that the old token still lacks (which would fire
// requests the server 403s every poll tick).
//
// Synchronous — the token is already in the store; no request is
// needed. If someone gets promoted server-side, their next token
// (issued via refresh or re-login) will carry the new roles claim
// and this call will pick it up on the following boot.
export function hydrateSession(): void {
  const state = useAuthStore.getState()
  const token = state.accessToken
  const user = state.user
  if (!token || !user) return
  // Purely local expiry check via the JWT `exp` claim: if the
  // persisted token expired while the app was closed, clear now
  // so the router sends the user to /login instead of stranding
  // them on a protected route that never fires an API call
  // (Dashboard/Schedules today). The api() 401 handler covers the
  // "expired mid-session" case; this covers "expired at rest".
  if (isJwtExpired(token)) {
    useAuthStore.getState().clear()
    return
  }
  const roles = decodeJwtRoles(token)
  // Legacy persisted sessions (cp-auth written before AuthUser had
  // a roles field) rehydrate as { email } — user.roles is undefined
  // at runtime even though the type says string[]. Guard the shape
  // and always write to normalize when it's missing.
  const existingRoles = Array.isArray(user.roles) ? user.roles : null
  if (existingRoles) {
    const same =
      roles.length === existingRoles.length &&
      roles.every((r, i) => r === existingRoles[i])
    if (same) return
  }
  useAuthStore.getState().setUser({ ...user, roles })
}

import { useState, type SyntheticEvent } from 'react'
import { useNavigate, useLocation } from 'react-router-dom'
import { api, ApiError } from '@/lib/api'
import { useAuthStore } from '@/lib/auth-store'
import { decodeJwtRoles } from '@/lib/jwt'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Label } from '@/components/ui/Label'

// Response shape from POST /api/auth/login. Matches the API's
// TokenResponse record — deliberately no `email` field; the API
// doesn't echo it back. We use the submitted email when populating
// the user in the auth store.
interface LoginResponse {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresIn: number
}

// Shared demo accounts, provisioned by the API's bootstrap.
// Deliberately public — the whole point is that a visitor can explore
// without creating anything. Same credentials work in the local demo
// compose and on the deployed site. Two personas so the role gating
// is something a visitor can *experience*, not just read about:
// OPERATOR sees every job plus audit trails; USER sees only what it
// owns and no audit.
const DEMO_EMAIL = 'demo@control-plane.dev'
const DEMO_PASSWORD = 'demo-password'
const VIEWER_EMAIL = 'viewer@control-plane.dev'
const VIEWER_PASSWORD = 'viewer-password'

export function Login() {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const setSession = useAuthStore((s) => s.setSession)
  const navigate = useNavigate()
  const location = useLocation()
  // Where to send the user after a successful login. Set by
  // ProtectedRoute when it kicked us here.
  const redirectTo = (location.state as { from?: string } | null)?.from ?? '/'

  async function signIn(asEmail: string, asPassword: string) {
    setError(null)
    setSubmitting(true)
    try {
      const resp = await api<LoginResponse>('/api/auth/login', {
        method: 'POST',
        body: { email: asEmail, password: asPassword },
        auth: false,
      })
      // Roles come from the JWT `roles` claim so the UI gates on the
      // same source of truth the API authorizes against. Email comes
      // from the form — the login response deliberately doesn't
      // echo it.
      setSession({
        accessToken: resp.accessToken,
        refreshToken: resp.refreshToken,
        user: { email: asEmail, roles: decodeJwtRoles(resp.accessToken) },
      })
      navigate(redirectTo, { replace: true })
      return true
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        setError('Invalid email or password.')
      } else {
        setError('Login failed. Try again.')
      }
      return false
    } finally {
      setSubmitting(false)
    }
  }

  async function onSubmit(e: SyntheticEvent) {
    e.preventDefault()
    await signIn(email, password)
  }

  async function onDemoClick() {
    const ok = await signIn(DEMO_EMAIL, DEMO_PASSWORD)
    // The generic 401 copy would be confusing here — the visitor
    // typed nothing. Say what actually went wrong.
    if (!ok) {
      setError('The demo account is not available on this instance.')
    }
  }

  async function onViewerClick() {
    const ok = await signIn(VIEWER_EMAIL, VIEWER_PASSWORD)
    if (!ok) {
      setError('The restricted demo account is not available on this instance.')
    }
  }

  return (
    <div className="min-h-full flex items-center justify-center p-4">
      <form
        onSubmit={onSubmit}
        className="w-full max-w-sm space-y-4 rounded-lg border border-slate-200 bg-white p-6 shadow-sm"
      >
        <div className="space-y-1">
          <h1 className="text-xl font-semibold text-slate-900">Control Plane</h1>
          <p className="text-sm text-slate-500">Sign in to continue.</p>
        </div>

        <div className="space-y-1">
          <Label htmlFor="email">Email</Label>
          <Input
            id="email"
            type="email"
            autoComplete="username"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
            disabled={submitting}
          />
        </div>

        <div className="space-y-1">
          <Label htmlFor="password">Password</Label>
          <Input
            id="password"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            disabled={submitting}
          />
        </div>

        {error && (
          <p className="text-sm text-red-600" role="alert">
            {error}
          </p>
        )}

        <Button type="submit" className="w-full" disabled={submitting}>
          {submitting ? 'Signing in…' : 'Sign in'}
        </Button>

        <div className="flex items-center gap-3" aria-hidden="true">
          <div className="h-px flex-1 bg-slate-200" />
          <span className="text-xs uppercase tracking-wide text-slate-400">or</span>
          <div className="h-px flex-1 bg-slate-200" />
        </div>

        <div className="space-y-1.5">
          <Button
            type="button"
            variant="secondary"
            className="w-full"
            onClick={onDemoClick}
            disabled={submitting}
          >
            Explore the demo
          </Button>
          <p className="text-center text-xs text-slate-500">
            One click, no account — signs in as a shared demo user
            (operator view: all jobs + audit trails).
          </p>
          <p className="text-center text-xs text-slate-500">
            Or{' '}
            <button
              type="button"
              onClick={onViewerClick}
              disabled={submitting}
              className="underline decoration-slate-400 underline-offset-2 hover:text-slate-700 disabled:opacity-50"
            >
              view as a restricted user
            </button>
            {' '}— own jobs only, no audit access.
          </p>
        </div>
      </form>
    </div>
  )
}

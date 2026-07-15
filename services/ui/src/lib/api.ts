// Thin fetch wrapper. Adds Authorization if we have a token, throws
// ApiError on non-2xx, and JSON-parses successful bodies. Every network
// call in the app goes through this.
import { useAuthStore } from '@/lib/auth-store'

export class ApiError extends Error {
  status: number
  body: unknown
  constructor(status: number, body: unknown, message?: string) {
    super(message ?? `HTTP ${status}`)
    this.status = status
    this.body = body
  }
}

type ApiOptions = Omit<RequestInit, 'body'> & {
  body?: unknown
  // Skip the Authorization header even if an access token is in the
  // store. Required for /api/auth/{login,refresh,logout} — the JWT
  // resource server rejects invalid/expired bearers *before* the
  // controller runs, so a user with a stale token in localStorage
  // can't re-authenticate unless we suppress the header here.
  auth?: boolean
}

export async function api<T = unknown>(path: string, opts: ApiOptions = {}): Promise<T> {
  const { auth = true, body, headers: hdrs, ...rest } = opts
  const token = useAuthStore.getState().accessToken
  const headers = new Headers(hdrs)
  if (body !== undefined && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  if (auth && token && !headers.has('Authorization')) {
    headers.set('Authorization', `Bearer ${token}`)
  }

  const res = await fetch(path, {
    ...rest,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })

  let responseBody: unknown = null
  const contentType = res.headers.get('Content-Type') ?? ''
  // Accept application/json *and* the +json structured-suffix family
  // (RFC 6839). Spring's RFC 9457 error responses come back as
  // application/problem+json — without the +json branch those bodies
  // never parse, so ApiError.body stays null and callers that read it
  // (e.g. the schedule form's cron/timezone/duplicate-name messages)
  // always fall through to their generic error copy.
  if (contentType.includes('application/json') || contentType.includes('+json')) {
    responseBody = await res.json().catch(() => null)
  }

  if (!res.ok) {
    // Server rejected our bearer — the persisted session is stale
    // (token expired, revoked, or user deleted). Clear it so the
    // router lands the user on /login instead of trapping them in
    // the protected shell with an unbroken loop of failing polls.
    //
    // Only fires on authenticated requests; unauth calls (login,
    // refresh, logout) surface 401 as a normal auth error.
    //
    // Also gated on the store still holding the same token we
    // captured before fetch(): if a fresh login raced this slow
    // request, the 401 we just got belongs to the *old* session
    // and must not wipe the new one.
    if (
      res.status === 401 &&
      auth &&
      token &&
      useAuthStore.getState().accessToken === token
    ) {
      useAuthStore.getState().clear()
    }
    throw new ApiError(res.status, responseBody)
  }
  return responseBody as T
}

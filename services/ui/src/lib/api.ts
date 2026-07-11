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
  if (contentType.includes('application/json')) {
    responseBody = await res.json().catch(() => null)
  }

  if (!res.ok) {
    throw new ApiError(res.status, responseBody)
  }
  return responseBody as T
}

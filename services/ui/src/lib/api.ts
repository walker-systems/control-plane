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

type ApiOptions = Omit<RequestInit, 'body'> & { body?: unknown }

export async function api<T = unknown>(path: string, opts: ApiOptions = {}): Promise<T> {
  const token = useAuthStore.getState().accessToken
  const headers = new Headers(opts.headers)
  if (opts.body !== undefined && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  if (token && !headers.has('Authorization')) {
    headers.set('Authorization', `Bearer ${token}`)
  }

  const res = await fetch(path, {
    ...opts,
    headers,
    body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
  })

  let body: unknown = null
  const contentType = res.headers.get('Content-Type') ?? ''
  if (contentType.includes('application/json')) {
    body = await res.json().catch(() => null)
  }

  if (!res.ok) {
    throw new ApiError(res.status, body)
  }
  return body as T
}

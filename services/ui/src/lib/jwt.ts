// Base64url decode helpers for reading claims out of the access token.
// We deliberately don't verify the signature — that's the server's job,
// and the UI's role gating is a display convenience, not a security
// boundary. What we DO care about is that the UI gates on the same
// values the API authorizes against (JWT `roles` claim), so
// role-gated UI never fires requests the server will 403.

function base64UrlDecode(str: string): string {
  const b64 = str.replace(/-/g, '+').replace(/_/g, '/')
  const padded = b64 + '='.repeat((4 - (b64.length % 4)) % 4)
  return atob(padded)
}

interface JwtPayload {
  roles?: unknown
  exp?: unknown
}

function decodePayload(token: string): JwtPayload | null {
  try {
    const parts = token.split('.')
    if (parts.length !== 3) return null
    return JSON.parse(base64UrlDecode(parts[1])) as JwtPayload
  } catch {
    return null
  }
}

// Extract the `roles` claim from a JWT access token. Returns an empty
// array if the token is malformed, unparseable, or missing the claim
// — the resulting UI simply hides role-gated surfaces, which is the
// correct fail-safe.
export function decodeJwtRoles(token: string): string[] {
  const payload = decodePayload(token)
  if (!payload || !Array.isArray(payload.roles)) return []
  return payload.roles.filter((r): r is string => typeof r === 'string')
}

// True if the JWT's `exp` claim is present AND in the past. Returns
// false when we cannot prove expiry (malformed token, missing/invalid
// exp) — the api() 401 handler is the fallback for anything we
// can't decide locally.
export function isJwtExpired(token: string, nowMs: number = Date.now()): boolean {
  const payload = decodePayload(token)
  if (!payload || typeof payload.exp !== 'number') return false
  // JWT exp is in seconds since epoch; Date.now() is milliseconds.
  return payload.exp * 1000 <= nowMs
}

import { api } from '@/lib/api'

// Response from GET /api/users/me — mirrors the API's UserResponse
// record. We use email + roles; the other fields are here for shape
// completeness and future use.
export interface MeResponse {
  id: string
  email: string
  status: string
  roles: string[]
  createdAt: string
  lastLoginAt: string | null
}

export function getMe(): Promise<MeResponse> {
  return api<MeResponse>('/api/users/me')
}

// Names of the API roles that gate the audit read endpoints. Kept in
// one place so the UI and the API stay in sync — see
// AuditEventService#assertCanRead.
export const AUDIT_ROLES = new Set(['OPERATOR', 'ADMIN'])

export function canReadAudit(roles: readonly string[] | undefined): boolean {
  return roles?.some((r) => AUDIT_ROLES.has(r)) ?? false
}

// Names of the API roles that gate the audit read endpoints. Kept in
// one place so the UI and the API stay in sync — see
// AuditEventService#assertCanRead.
export const AUDIT_ROLES = new Set(['OPERATOR', 'ADMIN'])

export function canReadAudit(roles: readonly string[] | undefined): boolean {
  return roles?.some((r) => AUDIT_ROLES.has(r)) ?? false
}

// User management is ADMIN-only, matching UserAdminService's gate.
export function canManageUsers(roles: readonly string[] | undefined): boolean {
  return roles?.includes('ADMIN') ?? false
}

// The roles an admin can assign, in privilege order. Mirrors the seed
// rows in V1__create_users_and_roles.sql — the API validates against
// the roles table, so this list must not drift from it.
export const ASSIGNABLE_ROLES = ['USER', 'OPERATOR', 'ADMIN'] as const
export type RoleName = (typeof ASSIGNABLE_ROLES)[number]

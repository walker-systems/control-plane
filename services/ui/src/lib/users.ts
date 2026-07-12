// Names of the API roles that gate the audit read endpoints. Kept in
// one place so the UI and the API stay in sync — see
// AuditEventService#assertCanRead.
export const AUDIT_ROLES = new Set(['OPERATOR', 'ADMIN'])

export function canReadAudit(roles: readonly string[] | undefined): boolean {
  return roles?.some((r) => AUDIT_ROLES.has(r)) ?? false
}

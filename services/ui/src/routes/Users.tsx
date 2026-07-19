import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Navigate } from 'react-router-dom'
import { describeUserError, listUsers, updateUser } from '@/lib/user-admin'
import { canManageUsers } from '@/lib/users'
import type { UserResponse, UserStatus } from '@/lib/types'
import { useAuthStore } from '@/lib/auth-store'
import { RoleBadge, UserStatusBadge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { ConfirmDialog } from '@/components/ui/ConfirmDialog'
import { CreateUserDialog } from '@/components/users/CreateUserDialog'
import { EditRolesDialog } from '@/components/users/EditRolesDialog'
import { formatRelative, shortId } from '@/lib/format'

const PAGE_SIZE = 20

export function Users() {
  const currentUser = useAuthStore((s) => s.user)
  // Match "self" by email — the store holds the logged-in email but no
  // user id (the id lives only in the JWT subject). The API lowercases
  // emails on create, so compare case-insensitively. Self-detection is
  // a UX nicety; the server is the real guard (409 SELF_MODIFICATION).
  const currentEmail = currentUser?.email?.toLowerCase()
  const queryClient = useQueryClient()
  const [page, setPage] = useState(0)
  const [creating, setCreating] = useState(false)
  const [editingRoles, setEditingRoles] = useState<UserResponse | null>(null)
  const [statusTarget, setStatusTarget] = useState<{ user: UserResponse; next: UserStatus } | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)

  // Client-side guard mirrors the server's ADMIN gate. The server is
  // still the authority (every /api/users call 403s for non-admins);
  // this just avoids rendering a page that would only show errors.
  const canManage = canManageUsers(currentUser?.roles)

  const { data, isLoading, isError } = useQuery({
    queryKey: ['users', { page }],
    queryFn: () => listUsers({ page, size: PAGE_SIZE }),
    refetchInterval: 10_000,
    enabled: canManage,
  })

  const statusMutation = useMutation({
    mutationFn: ({ id, status }: { id: string; status: UserStatus }) =>
      updateUser(id, { status }),
    onSuccess: () => {
      setStatusTarget(null)
      setActionError(null)
      queryClient.invalidateQueries({ queryKey: ['users'] })
    },
    onError: (e) => { setStatusTarget(null); setActionError(describeUserError(e)) },
  })

  if (!canManage) {
    return <Navigate to="/" replace />
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-4">
        <h1 className="text-2xl font-semibold text-slate-900">Users</h1>
        {data && <span className="text-sm text-slate-500">{data.totalElements} total</span>}
        <div className="ml-auto">
          <Button size="sm" onClick={() => { setActionError(null); setCreating(true) }}>
            + New user
          </Button>
        </div>
      </div>

      {actionError && (
        <p className="text-sm text-red-600" role="alert">{actionError}</p>
      )}

      <div className="rounded-lg border border-slate-200 bg-white overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-slate-50 text-left text-slate-600">
            <tr>
              <Th>Email</Th>
              <Th>Status</Th>
              <Th>Roles</Th>
              <Th>Last login</Th>
              <Th>Created</Th>
              <Th className="text-right">Actions</Th>
            </tr>
          </thead>
          <tbody>
            {isLoading && (
              <tr><td colSpan={6} className="p-6 text-center text-slate-500">Loading…</td></tr>
            )}
            {isError && (
              <tr><td colSpan={6} className="p-6 text-center text-red-600">Failed to load users.</td></tr>
            )}
            {data && data.content.map((u) => {
              const isSelf = u.email.toLowerCase() === currentEmail
              return (
                <tr key={u.id} className="border-t border-slate-200 hover:bg-slate-50">
                  <Td>
                    <span className="text-slate-900">{u.email}</span>
                    {isSelf && <span className="ml-2 text-xs text-slate-400">(you)</span>}
                    <span className="ml-2 font-mono text-xs text-slate-400">{shortId(u.id)}</span>
                  </Td>
                  <Td><UserStatusBadge status={u.status} /></Td>
                  <Td>
                    <div className="flex flex-wrap gap-1">
                      {u.roles.length === 0
                        ? <span className="text-slate-400">—</span>
                        : u.roles.map((r) => <RoleBadge key={r} role={r} />)}
                    </div>
                  </Td>
                  <Td className="text-slate-500">{formatRelative(u.lastLoginAt)}</Td>
                  <Td className="text-slate-500">{formatRelative(u.createdAt)}</Td>
                  <Td className="text-right">
                    <UserActions
                      user={u}
                      isSelf={isSelf}
                      busy={statusMutation.isPending}
                      onEditRoles={() => { setActionError(null); setEditingRoles(u) }}
                      onSetStatus={(next) => { setActionError(null); setStatusTarget({ user: u, next }) }}
                    />
                  </Td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>

      {data && data.totalPages > 1 && (
        <div className="flex items-center justify-between text-sm text-slate-600">
          <Button variant="secondary" size="sm" onClick={() => setPage((p) => Math.max(0, p - 1))} disabled={page === 0}>← Prev</Button>
          <span>Page {page + 1} of {data.totalPages}</span>
          <Button variant="secondary" size="sm" onClick={() => setPage((p) => Math.min(data.totalPages - 1, p + 1))} disabled={page >= data.totalPages - 1}>Next →</Button>
        </div>
      )}

      <CreateUserDialog
        open={creating}
        onClose={() => setCreating(false)}
        onCreated={() => {
          setCreating(false)
          queryClient.invalidateQueries({ queryKey: ['users'] })
        }}
      />

      <EditRolesDialog
        user={editingRoles}
        onClose={() => setEditingRoles(null)}
        onSaved={() => {
          setEditingRoles(null)
          queryClient.invalidateQueries({ queryKey: ['users'] })
        }}
      />

      <ConfirmDialog
        open={statusTarget !== null}
        title={statusDialogTitle(statusTarget?.next)}
        description={statusDialogBody(statusTarget?.next)}
        confirmLabel={statusTarget?.next === 'ACTIVE' ? 'Reactivate' : (statusTarget?.next === 'LOCKED' ? 'Lock account' : 'Disable account')}
        destructive={statusTarget?.next !== 'ACTIVE'}
        busy={statusMutation.isPending}
        onConfirm={() => statusTarget && statusMutation.mutate({ id: statusTarget.user.id, status: statusTarget.next })}
        onCancel={() => setStatusTarget(null)}
      />
    </div>
  )
}

// Per-row actions. Self-row omits status/role controls entirely — the
// API refuses self-modification (409), so surfacing the buttons would
// only produce errors.
function UserActions({
  user,
  isSelf,
  busy,
  onEditRoles,
  onSetStatus,
}: {
  user: UserResponse
  isSelf: boolean
  busy: boolean
  onEditRoles: () => void
  onSetStatus: (next: UserStatus) => void
}) {
  if (isSelf) {
    return <span className="text-xs text-slate-400">—</span>
  }
  return (
    <div className="flex items-center justify-end gap-2">
      <Button variant="ghost" size="sm" onClick={onEditRoles} disabled={busy}>Roles</Button>
      {user.status === 'ACTIVE' ? (
        <Button variant="ghost" size="sm" onClick={() => onSetStatus('LOCKED')} disabled={busy}>Lock</Button>
      ) : (
        <Button variant="ghost" size="sm" onClick={() => onSetStatus('ACTIVE')} disabled={busy}>Reactivate</Button>
      )}
      {user.status !== 'DISABLED' && (
        <Button variant="ghost" size="sm" onClick={() => onSetStatus('DISABLED')} disabled={busy}>Disable</Button>
      )}
    </div>
  )
}

function statusDialogTitle(next: UserStatus | undefined): string {
  switch (next) {
    case 'LOCKED': return 'Lock this account?'
    case 'DISABLED': return 'Disable this account?'
    case 'ACTIVE': return 'Reactivate this account?'
    default: return 'Change account status?'
  }
}

function statusDialogBody(next: UserStatus | undefined): string {
  switch (next) {
    case 'LOCKED':
      return 'The user is signed out immediately (all refresh tokens revoked) and cannot log in until reactivated. A held access token still works until it expires (≤15 min).'
    case 'DISABLED':
      return 'The user is signed out and blocked from logging in. Disable is for offboarded accounts; it behaves like Lock but signals permanence.'
    case 'ACTIVE':
      return 'The user can log in again. Their previous sessions are not restored — they sign in fresh.'
    default:
      return ''
  }
}

function Th({ children, className = '' }: { children: React.ReactNode; className?: string }) {
  return <th className={`px-3 py-2 font-medium ${className}`}>{children}</th>
}

function Td({ children, className = '' }: { children: React.ReactNode; className?: string }) {
  return <td className={`px-3 py-2 ${className}`}>{children}</td>
}

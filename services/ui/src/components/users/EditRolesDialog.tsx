import { useEffect, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { describeUserError, updateUser } from '@/lib/user-admin'
import { ASSIGNABLE_ROLES, type RoleName } from '@/lib/users'
import type { UserResponse } from '@/lib/types'
import { Button } from '@/components/ui/Button'
import { Label } from '@/components/ui/Label'
import { Dialog } from '@/components/ui/Dialog'
import { RoleBadge } from '@/components/ui/Badge'

// A null user closes the dialog; a non-null one opens it seeded with
// that user's current roles.
export function EditRolesDialog({
  user,
  onClose,
  onSaved,
}: {
  user: UserResponse | null
  onClose: () => void
  onSaved: () => void
}) {
  const [roles, setRoles] = useState<string[]>([])
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (user) {
      setRoles(user.roles)
      setError(null)
    }
  }, [user])

  const mutation = useMutation({
    mutationFn: () => updateUser(user!.id, { roles }),
    onSuccess: () => onSaved(),
    onError: (e) => setError(describeUserError(e)),
  })

  function toggleRole(role: RoleName) {
    setRoles((prev) =>
      prev.includes(role) ? prev.filter((r) => r !== role) : [...prev, role],
    )
  }

  function onSave() {
    setError(null)
    if (roles.length === 0) {
      setError('A user must have at least one role.')
      return
    }
    mutation.mutate()
  }

  return (
    <Dialog open={user !== null} title={`Roles — ${user?.email ?? ''}`} onClose={onClose}>
      <div className="space-y-4">
        <div className="space-y-1.5">
          <Label>Assigned roles</Label>
          <div className="flex flex-wrap gap-2">
            {ASSIGNABLE_ROLES.map((role) => {
              const active = roles.includes(role)
              return (
                <button
                  key={role}
                  type="button"
                  onClick={() => toggleRole(role)}
                  disabled={mutation.isPending}
                  className={
                    'rounded-md px-1 py-0.5 ring-2 transition ' +
                    (active ? 'ring-slate-900' : 'ring-transparent opacity-50 hover:opacity-100')
                  }
                  aria-pressed={active}
                >
                  <RoleBadge role={role} />
                </button>
              )
            })}
          </div>
          <p className="text-xs text-slate-500">
            Click to toggle. Changes take effect on the user's next token refresh.
          </p>
        </div>

        {error && <p className="text-sm text-red-600" role="alert">{error}</p>}

        <div className="flex justify-end gap-2 pt-2">
          <Button type="button" variant="secondary" size="sm" onClick={onClose} disabled={mutation.isPending}>
            Cancel
          </Button>
          <Button type="button" size="sm" onClick={onSave} disabled={mutation.isPending}>
            {mutation.isPending ? 'Saving…' : 'Save roles'}
          </Button>
        </div>
      </div>
    </Dialog>
  )
}

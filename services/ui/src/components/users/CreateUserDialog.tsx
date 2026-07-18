import { useEffect, useState, type SyntheticEvent } from 'react'
import { useMutation } from '@tanstack/react-query'
import { createUser, describeUserError } from '@/lib/user-admin'
import { ASSIGNABLE_ROLES, type RoleName } from '@/lib/users'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Label } from '@/components/ui/Label'
import { Dialog } from '@/components/ui/Dialog'
import { RoleBadge } from '@/components/ui/Badge'

// Matches the API's @Size(min = 12) on the password field.
const MIN_PASSWORD = 12

export function CreateUserDialog({
  open,
  onClose,
  onCreated,
}: {
  open: boolean
  onClose: () => void
  onCreated: () => void
}) {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [roles, setRoles] = useState<RoleName[]>(['USER'])
  const [error, setError] = useState<string | null>(null)

  // Reset fields whenever the dialog opens, so a second "New user" is
  // a blank form rather than the last attempt's leftovers.
  useEffect(() => {
    if (open) {
      setEmail('')
      setPassword('')
      setRoles(['USER'])
      setError(null)
    }
  }, [open])

  const mutation = useMutation({
    mutationFn: () => createUser({ email: email.trim(), password, roles }),
    onSuccess: () => onCreated(),
    onError: (e) => setError(describeUserError(e)),
  })

  function toggleRole(role: RoleName) {
    setRoles((prev) =>
      prev.includes(role) ? prev.filter((r) => r !== role) : [...prev, role],
    )
  }

  function onSubmit(e: SyntheticEvent) {
    e.preventDefault()
    setError(null)
    if (password.length < MIN_PASSWORD) {
      setError(`Password must be at least ${MIN_PASSWORD} characters.`)
      return
    }
    if (roles.length === 0) {
      setError('Pick at least one role.')
      return
    }
    mutation.mutate()
  }

  return (
    <Dialog open={open} title="New user" onClose={onClose}>
      <form onSubmit={onSubmit} className="space-y-4">
        <div className="space-y-1">
          <Label htmlFor="new-user-email">Email</Label>
          <Input
            id="new-user-email"
            type="email"
            autoComplete="off"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
            disabled={mutation.isPending}
            placeholder="person@example.com"
          />
        </div>

        <div className="space-y-1">
          <Label htmlFor="new-user-password">Temporary password</Label>
          <Input
            id="new-user-password"
            type="text"
            autoComplete="off"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            disabled={mutation.isPending}
            className="font-mono"
            placeholder={`at least ${MIN_PASSWORD} characters`}
          />
          <p className="text-xs text-slate-500">
            Shown as plain text — this is an admin-set initial password to hand off, not a secret you're recovering.
          </p>
        </div>

        <div className="space-y-1.5">
          <Label>Roles</Label>
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
        </div>

        {error && <p className="text-sm text-red-600" role="alert">{error}</p>}

        <div className="flex justify-end gap-2 pt-2">
          <Button type="button" variant="secondary" size="sm" onClick={onClose} disabled={mutation.isPending}>
            Cancel
          </Button>
          <Button type="submit" size="sm" disabled={mutation.isPending}>
            {mutation.isPending ? 'Creating…' : 'Create user'}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}

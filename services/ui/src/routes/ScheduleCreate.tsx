import { useState, type SyntheticEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { createSchedule } from '@/lib/schedules'
import { describeCron } from '@/lib/cron'
import type { JobPriority, JobType } from '@/lib/types'
import { ApiError } from '@/lib/api'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Label } from '@/components/ui/Label'

const JOB_TYPES: JobType[] = [
  'CUSTOMER_EXPORT', 'STALE_ACCOUNT_CLEANUP', 'SUSPICIOUS_ACCOUNT_SCAN', 'CRM_SYNC',
]
const PRIORITIES: JobPriority[] = ['LOW', 'MEDIUM', 'HIGH']

// Shape of the RFC 9457 ProblemDetail the API returns on 400/409,
// with the `reason` property JobScheduleController adds.
interface ScheduleProblem {
  detail?: string
  reason?: 'INVALID_CRON' | 'INVALID_TIMEZONE' | 'DUPLICATE_NAME'
}

export function ScheduleCreate() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const [name, setName] = useState('')
  const [type, setType] = useState<JobType>('CUSTOMER_EXPORT')
  const [payloadJson, setPayloadJson] = useState('{}')
  const [priority, setPriority] = useState<JobPriority>('MEDIUM')
  const [maxRetries, setMaxRetries] = useState('3')
  const [cron, setCron] = useState('0 */5 * * * *')
  // Default to the browser's zone — right for nearly everyone, and
  // visible/editable for the rest.
  const [timezone, setTimezone] = useState(
    () => Intl.DateTimeFormat().resolvedOptions().timeZone,
  )
  const [error, setError] = useState<string | null>(null)

  // Pure local computation, cheap enough to run every render.
  const cronDescription = describeCron(cron)

  const createMutation = useMutation({
    mutationFn: () =>
      createSchedule({
        name: name.trim(),
        type,
        payloadJson,
        priority,
        maxRetries: Number(maxRetries),
        cron: cron.trim(),
        timezone: timezone.trim(),
      }),
    onSuccess: (created) => {
      queryClient.invalidateQueries({ queryKey: ['schedules'] })
      navigate(`/schedules/${created.id}`, { replace: true })
    },
    onError: (e) => setError(describeError(e)),
  })

  function onSubmit(e: SyntheticEvent) {
    e.preventDefault()
    setError(null)
    // Validate the payload client-side so the user gets an immediate,
    // specific message instead of a server round-trip. The API only
    // checks @NotBlank — a payload of "not json" would be accepted
    // and then fail inside the handler at execution time.
    try {
      JSON.parse(payloadJson)
    } catch {
      setError('Payload must be valid JSON.')
      return
    }
    const retries = Number(maxRetries)
    if (!Number.isInteger(retries) || retries < 0 || retries > 20) {
      setError('Max retries must be a whole number from 0 to 20.')
      return
    }
    createMutation.mutate()
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center gap-3">
        <Link to="/schedules" className="text-sm text-slate-600 hover:underline">
          ← Schedules
        </Link>
        <span className="text-slate-300">/</span>
        <h1 className="text-2xl font-semibold text-slate-900">New schedule</h1>
      </div>

      <form
        onSubmit={onSubmit}
        className="max-w-2xl space-y-4 rounded-lg border border-slate-200 bg-white p-6"
      >
        <div className="space-y-1">
          <Label htmlFor="name">Name</Label>
          <Input
            id="name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            maxLength={120}
            required
            disabled={createMutation.isPending}
            placeholder="nightly-customer-export"
          />
        </div>

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <div className="space-y-1">
            <Label htmlFor="type">Job type</Label>
            <Select
              id="type"
              value={type}
              onChange={(v) => setType(v as JobType)}
              options={JOB_TYPES}
              disabled={createMutation.isPending}
            />
          </div>
          <div className="space-y-1">
            <Label htmlFor="priority">Priority</Label>
            <Select
              id="priority"
              value={priority}
              onChange={(v) => setPriority(v as JobPriority)}
              options={PRIORITIES}
              disabled={createMutation.isPending}
            />
          </div>
        </div>

        <div className="space-y-1">
          <Label htmlFor="payload">Payload (JSON)</Label>
          <textarea
            id="payload"
            value={payloadJson}
            onChange={(e) => setPayloadJson(e.target.value)}
            required
            disabled={createMutation.isPending}
            rows={5}
            spellCheck={false}
            className={
              'w-full rounded-md border border-slate-300 bg-white px-3 py-2 ' +
              'font-mono text-xs text-slate-800 placeholder:text-slate-400 ' +
              'focus:outline-none focus:ring-2 focus:ring-slate-950 disabled:opacity-50'
            }
          />
          <p className="text-xs text-slate-500">
            Copied into every job this schedule creates.
          </p>
        </div>

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <div className="space-y-1 sm:col-span-1">
            <Label htmlFor="cron">Cron (6-field)</Label>
            <Input
              id="cron"
              value={cron}
              onChange={(e) => setCron(e.target.value)}
              required
              disabled={createMutation.isPending}
              className="font-mono"
              placeholder="0 */5 * * * *"
            />
            {/* Live human-readable preview; falls back to the format
                hint while the expression doesn't parse. Recomputed on
                every keystroke — it's a pure local function, no
                request involved. */}
            {cronDescription ? (
              <p className="text-xs text-emerald-700">{cronDescription}</p>
            ) : (
              <p className="text-xs text-slate-500">
                Spring format: sec min hour day month weekday.
              </p>
            )}
          </div>
          <div className="space-y-1">
            <Label htmlFor="timezone">Timezone</Label>
            <Input
              id="timezone"
              value={timezone}
              onChange={(e) => setTimezone(e.target.value)}
              required
              disabled={createMutation.isPending}
              placeholder="America/New_York"
            />
          </div>
          <div className="space-y-1">
            <Label htmlFor="maxRetries">Max retries</Label>
            <Input
              id="maxRetries"
              type="number"
              min={0}
              max={20}
              value={maxRetries}
              onChange={(e) => setMaxRetries(e.target.value)}
              required
              disabled={createMutation.isPending}
            />
          </div>
        </div>

        {error && (
          <p className="text-sm text-red-600" role="alert">{error}</p>
        )}

        <div className="flex items-center gap-2 pt-2">
          <Button type="submit" disabled={createMutation.isPending}>
            {createMutation.isPending ? 'Creating…' : 'Create schedule'}
          </Button>
          <Button
            type="button"
            variant="ghost"
            onClick={() => navigate('/schedules')}
            disabled={createMutation.isPending}
          >
            Cancel
          </Button>
        </div>
      </form>
    </div>
  )
}

// Map the API's ProblemDetail reasons onto actionable copy; fall back
// to the server's own detail message, then a generic line.
function describeError(e: unknown): string {
  if (e instanceof ApiError) {
    const problem = (e.body ?? {}) as ScheduleProblem
    switch (problem.reason) {
      case 'INVALID_CRON':
        return 'Invalid cron expression. Use Spring\'s 6-field format, e.g. "0 */5 * * * *".'
      case 'INVALID_TIMEZONE':
        return 'Unknown timezone. Use an IANA zone ID like "America/New_York".'
      case 'DUPLICATE_NAME':
        return 'You already have a schedule with this name.'
    }
    if (typeof problem.detail === 'string' && problem.detail) {
      return problem.detail
    }
  }
  return 'Failed to create schedule. Try again in a moment.'
}

function Select({
  id,
  value,
  onChange,
  options,
  disabled,
}: {
  id: string
  value: string
  onChange: (v: string) => void
  options: string[]
  disabled?: boolean
}) {
  return (
    <select
      id={id}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      disabled={disabled}
      className={
        'h-10 w-full rounded-md border border-slate-300 bg-white px-3 text-sm ' +
        'focus:outline-none focus:ring-2 focus:ring-slate-950 disabled:opacity-50'
      }
    >
      {options.map((o) => (
        <option key={o} value={o}>{o}</option>
      ))}
    </select>
  )
}

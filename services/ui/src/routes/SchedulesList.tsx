import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { listSchedules } from '@/lib/schedules'
import type { JobType } from '@/lib/types'
import { Button } from '@/components/ui/Button'
import { EnabledBadge, PriorityBadge } from '@/components/ui/Badge'
import { formatAbsolute, formatRelative, shortId } from '@/lib/format'

const JOB_TYPES: JobType[] = [
  'CUSTOMER_EXPORT', 'STALE_ACCOUNT_CLEANUP', 'SUSPICIOUS_ACCOUNT_SCAN', 'CRM_SYNC',
]

const PAGE_SIZE = 20

// Filter value semantics for `enabled`:
//   ''         → all (no filter applied)
//   'true'     → only ENABLED
//   'false'    → only PAUSED
// Kept as strings so URL round-tripping is trivial.
type EnabledFilter = '' | 'true' | 'false'

export function SchedulesList() {
  const [searchParams, setSearchParams] = useSearchParams()
  const navigate = useNavigate()
  const enabledParam = (searchParams.get('enabled') ?? '') as EnabledFilter
  const type = (searchParams.get('type') as JobType | null) ?? undefined
  const page = Number(searchParams.get('page') ?? '0')

  const { data, isLoading, isError } = useQuery({
    queryKey: ['schedules', { enabled: enabledParam, type, page }],
    queryFn: () =>
      listSchedules({
        // Only translate '' to undefined; keep the string→boolean
        // conversion here rather than making listSchedules do it.
        enabled: enabledParam === '' ? undefined : enabledParam === 'true',
        type,
        page,
        size: PAGE_SIZE,
      }),
    refetchInterval: 5_000,
  })

  function updateParams(updates: Record<string, string | undefined>) {
    const next = new URLSearchParams(searchParams)
    let filterChanged = false
    for (const [name, value] of Object.entries(updates)) {
      if (value) next.set(name, value)
      else next.delete(name)
      if (name !== 'page') filterChanged = true
    }
    if (filterChanged) next.delete('page')
    setSearchParams(next, { replace: true })
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-4">
        <h1 className="text-2xl font-semibold text-slate-900">Schedules</h1>
        {data && (
          <span className="text-sm text-slate-500">{data.totalElements} total</span>
        )}
        <div className="ml-auto">
          <Button size="sm" onClick={() => navigate('/schedules/new')}>
            + New schedule
          </Button>
        </div>
      </div>

      <FilterBar
        enabled={enabledParam}
        type={type}
        onEnabledChange={(v) => updateParams({ enabled: v || undefined })}
        onTypeChange={(v) => updateParams({ type: v })}
        onClear={() => updateParams({ enabled: undefined, type: undefined })}
      />

      <div className="rounded-lg border border-slate-200 bg-white overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-slate-50 text-left text-slate-600">
            <tr>
              <Th>Name</Th>
              <Th>Type</Th>
              <Th>State</Th>
              <Th>Priority</Th>
              <Th>Cron</Th>
              <Th>Timezone</Th>
              <Th>Next run</Th>
            </tr>
          </thead>
          <tbody>
            {isLoading && (
              <tr><td colSpan={7} className="p-6 text-center text-slate-500">Loading…</td></tr>
            )}
            {isError && (
              <tr><td colSpan={7} className="p-6 text-center text-red-600">Failed to load schedules.</td></tr>
            )}
            {data && data.content.length === 0 && (
              <tr><td colSpan={7} className="p-6 text-center text-slate-500">
                No schedules yet.
              </td></tr>
            )}
            {data && data.content.map((s) => (
              <tr key={s.id} className="border-t border-slate-200 hover:bg-slate-50">
                <Td>
                  <Link
                    to={`/schedules/${s.id}`}
                    className="text-slate-900 hover:underline"
                    title={s.id}
                  >
                    {s.name}
                  </Link>
                  <span className="ml-2 font-mono text-xs text-slate-400">
                    {shortId(s.id)}
                  </span>
                </Td>
                <Td className="text-slate-700">{s.type}</Td>
                <Td><EnabledBadge enabled={s.enabled} /></Td>
                <Td><PriorityBadge priority={s.priority} /></Td>
                <Td className="font-mono text-xs text-slate-800">{s.cron}</Td>
                <Td className="text-slate-500">{s.timezone}</Td>
                <Td className="text-slate-500" title={formatAbsolute(s.nextRunAt)}>
                  {formatRelative(s.nextRunAt)}
                </Td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {data && data.totalPages > 1 && (
        <Pagination
          page={page}
          totalPages={data.totalPages}
          onChange={(p) => updateParams({ page: String(p) })}
        />
      )}
    </div>
  )
}

function FilterBar(props: {
  enabled: EnabledFilter
  type: JobType | undefined
  onEnabledChange: (v: EnabledFilter) => void
  onTypeChange: (v: JobType | undefined) => void
  onClear: () => void
}) {
  return (
    <div className="flex flex-wrap items-center gap-3 rounded-lg border border-slate-200 bg-white p-3">
      <label className="flex items-center gap-2 text-sm">
        <span className="text-slate-600">State</span>
        <select
          value={props.enabled}
          onChange={(e) => props.onEnabledChange(e.target.value as EnabledFilter)}
          className="h-8 rounded-md border border-slate-300 bg-white px-2 text-sm"
        >
          <option value="">All</option>
          <option value="true">Enabled</option>
          <option value="false">Paused</option>
        </select>
      </label>
      <label className="flex items-center gap-2 text-sm">
        <span className="text-slate-600">Type</span>
        <select
          value={props.type ?? ''}
          onChange={(e) => props.onTypeChange((e.target.value || undefined) as JobType | undefined)}
          className="h-8 rounded-md border border-slate-300 bg-white px-2 text-sm"
        >
          <option value="">All</option>
          {JOB_TYPES.map((t) => (
            <option key={t} value={t}>{t}</option>
          ))}
        </select>
      </label>
      {(props.enabled || props.type) && (
        <Button variant="ghost" size="sm" onClick={props.onClear}>Clear</Button>
      )}
    </div>
  )
}

function Pagination({
  page,
  totalPages,
  onChange,
}: { page: number; totalPages: number; onChange: (p: number) => void }) {
  return (
    <div className="flex items-center justify-between text-sm text-slate-600">
      <Button
        variant="secondary"
        size="sm"
        onClick={() => onChange(Math.max(0, page - 1))}
        disabled={page === 0}
      >
        ← Prev
      </Button>
      <span>Page {page + 1} of {totalPages}</span>
      <Button
        variant="secondary"
        size="sm"
        onClick={() => onChange(Math.min(totalPages - 1, page + 1))}
        disabled={page >= totalPages - 1}
      >
        Next →
      </Button>
    </div>
  )
}

function Th({ children }: { children: React.ReactNode }) {
  return <th className="px-3 py-2 font-medium">{children}</th>
}

function Td({
  children,
  className = '',
  title,
}: { children: React.ReactNode; className?: string; title?: string }) {
  return <td className={`px-3 py-2 ${className}`} title={title}>{children}</td>
}

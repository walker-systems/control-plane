import { Link, useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { listJobs } from '@/lib/jobs'
import type { JobStatus, JobType } from '@/lib/types'
import { Button } from '@/components/ui/Button'
import { JobStatusBadge, PriorityBadge } from '@/components/ui/Badge'
import { formatRelative, shortId } from '@/lib/format'

const JOB_STATUSES: JobStatus[] = [
  'PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'DEAD_LETTER', 'CANCELLED',
]
const JOB_TYPES: JobType[] = [
  'CUSTOMER_EXPORT', 'STALE_ACCOUNT_CLEANUP', 'SUSPICIOUS_ACCOUNT_SCAN', 'CRM_SYNC',
]

const PAGE_SIZE = 20

export function JobsList() {
  // Filters + pagination live in the URL so the page is bookmarkable
  // and back/forward navigation works naturally.
  const [searchParams, setSearchParams] = useSearchParams()
  const status = (searchParams.get('status') as JobStatus | null) ?? undefined
  const type = (searchParams.get('type') as JobType | null) ?? undefined
  const page = Number(searchParams.get('page') ?? '0')

  const { data, isLoading, isError } = useQuery({
    queryKey: ['jobs', { status, type, page }],
    queryFn: () => listJobs({ status, type, page, size: PAGE_SIZE }),
    refetchInterval: 3_000,
  })

  function updateParam(name: string, value: string | undefined) {
    const next = new URLSearchParams(searchParams)
    if (value) next.set(name, value)
    else next.delete(name)
    // Any filter change resets to page 0.
    if (name !== 'page') next.delete('page')
    setSearchParams(next, { replace: true })
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-4">
        <h1 className="text-2xl font-semibold text-slate-900">Jobs</h1>
        {data && (
          <span className="text-sm text-slate-500">
            {data.totalElements} total
          </span>
        )}
      </div>

      <FilterBar
        status={status}
        type={type}
        onStatusChange={(v) => updateParam('status', v)}
        onTypeChange={(v) => updateParam('type', v)}
      />

      <div className="rounded-lg border border-slate-200 bg-white overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-slate-50 text-left text-slate-600">
            <tr>
              <Th>Status</Th>
              <Th>Type</Th>
              <Th>Priority</Th>
              <Th>Attempts</Th>
              <Th>Owner</Th>
              <Th>Created</Th>
              <Th>ID</Th>
            </tr>
          </thead>
          <tbody>
            {isLoading && (
              <tr><td colSpan={7} className="p-6 text-center text-slate-500">Loading…</td></tr>
            )}
            {isError && (
              <tr><td colSpan={7} className="p-6 text-center text-red-600">Failed to load jobs.</td></tr>
            )}
            {data && data.content.length === 0 && (
              <tr><td colSpan={7} className="p-6 text-center text-slate-500">No jobs match these filters.</td></tr>
            )}
            {data && data.content.map((job) => (
              <tr key={job.id} className="border-t border-slate-200 hover:bg-slate-50">
                <Td><JobStatusBadge status={job.status} /></Td>
                <Td className="text-slate-700">{job.type}</Td>
                <Td><PriorityBadge priority={job.priority} /></Td>
                <Td className="text-slate-700">{job.attemptCount} / {job.maxRetries + 1}</Td>
                <Td className="text-slate-600">{job.ownerEmail}</Td>
                <Td className="text-slate-500">{formatRelative(job.createdAt)}</Td>
                <Td>
                  <Link
                    to={`/jobs/${job.id}`}
                    className="font-mono text-xs text-slate-900 hover:underline"
                  >
                    {shortId(job.id)}
                  </Link>
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
          onChange={(p) => updateParam('page', String(p))}
        />
      )}
    </div>
  )
}

function FilterBar(props: {
  status: JobStatus | undefined
  type: JobType | undefined
  onStatusChange: (v: JobStatus | undefined) => void
  onTypeChange: (v: JobType | undefined) => void
}) {
  return (
    <div className="flex flex-wrap items-center gap-3 rounded-lg border border-slate-200 bg-white p-3">
      <FilterSelect
        label="Status"
        value={props.status}
        onChange={(v) => props.onStatusChange((v || undefined) as JobStatus | undefined)}
        options={JOB_STATUSES}
      />
      <FilterSelect
        label="Type"
        value={props.type}
        onChange={(v) => props.onTypeChange((v || undefined) as JobType | undefined)}
        options={JOB_TYPES}
      />
      {(props.status || props.type) && (
        <Button
          variant="ghost"
          size="sm"
          onClick={() => {
            props.onStatusChange(undefined)
            props.onTypeChange(undefined)
          }}
        >
          Clear
        </Button>
      )}
    </div>
  )
}

function FilterSelect(props: {
  label: string
  value: string | undefined
  onChange: (v: string) => void
  options: string[]
}) {
  return (
    <label className="flex items-center gap-2 text-sm">
      <span className="text-slate-600">{props.label}</span>
      <select
        value={props.value ?? ''}
        onChange={(e) => props.onChange(e.target.value)}
        className="h-8 rounded-md border border-slate-300 bg-white px-2 text-sm"
      >
        <option value="">All</option>
        {props.options.map((o) => (
          <option key={o} value={o}>{o}</option>
        ))}
      </select>
    </label>
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

function Td({ children, className = '' }: { children: React.ReactNode; className?: string }) {
  return <td className={`px-3 py-2 ${className}`}>{children}</td>
}

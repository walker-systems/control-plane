import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { getJobStats, listJobs } from '@/lib/jobs'
import type { JobStatus } from '@/lib/types'
import { JobStatusBadge } from '@/components/ui/Badge'
import { formatRelative, shortId } from '@/lib/format'

// Order matches the natural JobStatus lifecycle: pre-execution first,
// then running, then terminal outcomes. Kept independent of the API's
// enum iteration so we can reorder for UX without any server change.
const TILE_ORDER: JobStatus[] = [
  'PENDING',
  'RUNNING',
  'SUCCEEDED',
  'FAILED',
  'DEAD_LETTER',
  'CANCELLED',
]

const RECENT_LIMIT = 10

export function Dashboard() {
  const statsQuery = useQuery({
    queryKey: ['job-stats'],
    queryFn: getJobStats,
    refetchInterval: 3_000,
  })
  const recentQuery = useQuery({
    queryKey: ['jobs-recent'],
    // No status/type filter — we want the latest N regardless of state.
    // The API sorts by createdAt desc by default.
    queryFn: () => listJobs({ size: RECENT_LIMIT }),
    refetchInterval: 3_000,
  })

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold text-slate-900">Dashboard</h1>

      <section>
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
          {TILE_ORDER.map((status) => (
            <StatusTile
              key={status}
              status={status}
              count={statsQuery.data?.counts[status]}
              loading={statsQuery.isLoading}
              error={statsQuery.isError}
            />
          ))}
        </div>
      </section>

      <section className="space-y-3">
        <div className="flex items-baseline justify-between">
          <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-500">
            Recent activity
          </h2>
          <Link to="/jobs" className="text-sm text-slate-600 hover:underline">
            View all jobs →
          </Link>
        </div>
        <div className="rounded-lg border border-slate-200 bg-white overflow-hidden">
          <table className="w-full text-sm">
            <thead className="bg-slate-50 text-left text-slate-600">
              <tr>
                <Th>Status</Th>
                <Th>Type</Th>
                <Th>Owner</Th>
                <Th>Created</Th>
                <Th>ID</Th>
              </tr>
            </thead>
            <tbody>
              {recentQuery.isLoading && (
                <tr><td colSpan={5} className="p-6 text-center text-slate-500">Loading…</td></tr>
              )}
              {recentQuery.isError && (
                <tr><td colSpan={5} className="p-6 text-center text-red-600">Failed to load recent jobs.</td></tr>
              )}
              {recentQuery.data && recentQuery.data.content.length === 0 && (
                <tr><td colSpan={5} className="p-6 text-center text-slate-500">
                  No jobs yet. Create one via <code className="font-mono">POST /api/jobs</code>.
                </td></tr>
              )}
              {recentQuery.data && recentQuery.data.content.map((job) => (
                <tr key={job.id} className="border-t border-slate-200 hover:bg-slate-50">
                  <Td><JobStatusBadge status={job.status} /></Td>
                  <Td className="text-slate-700">{job.type}</Td>
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
      </section>
    </div>
  )
}

function StatusTile({
  status,
  count,
  loading,
  error,
}: {
  status: JobStatus
  count: number | undefined
  loading: boolean
  error: boolean
}) {
  return (
    <Link
      to={`/jobs?status=${status}`}
      className="rounded-lg border border-slate-200 bg-white p-4 hover:border-slate-300 hover:shadow-sm transition"
    >
      <div className="flex items-center justify-between">
        <JobStatusBadge status={status} />
      </div>
      <div className="mt-3 text-2xl font-semibold text-slate-900 tabular-nums">
        {error ? '—' : loading && count === undefined ? '…' : (count ?? 0).toLocaleString()}
      </div>
    </Link>
  )
}

function Th({ children }: { children: React.ReactNode }) {
  return <th className="px-3 py-2 font-medium">{children}</th>
}

function Td({ children, className = '' }: { children: React.ReactNode; className?: string }) {
  return <td className={`px-3 py-2 ${className}`}>{children}</td>
}

import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  deleteSchedule,
  getSchedule,
  pauseSchedule,
  resumeSchedule,
} from '@/lib/schedules'
import { listJobs } from '@/lib/jobs'
import { describeCron } from '@/lib/cron'
import type { JobResponse, JobScheduleResponse } from '@/lib/types'
import { ApiError } from '@/lib/api'
import { EnabledBadge, JobStatusBadge, PriorityBadge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { ConfirmDialog } from '@/components/ui/ConfirmDialog'
import { formatAbsolute, formatRelative, shortId } from '@/lib/format'

const RECENT_JOB_LIMIT = 10

export function ScheduleDetail() {
  const { id = '' } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [pending, setPending] = useState<'delete' | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)

  const scheduleQuery = useQuery({
    queryKey: ['schedule', id],
    queryFn: () => getSchedule(id),
    refetchInterval: 5_000,
    enabled: !!id,
  })
  // Recently materialized jobs from this schedule. sourceScheduleId is
  // an existing filter on GET /api/jobs, so no new endpoint is needed
  // — the API already sorts by createdAt desc by default.
  const jobsQuery = useQuery({
    queryKey: ['schedule-jobs', id],
    queryFn: () => listJobs({ sourceScheduleId: id, size: RECENT_JOB_LIMIT }),
    refetchInterval: 5_000,
    enabled: !!id,
  })

  function invalidate() {
    queryClient.invalidateQueries({ queryKey: ['schedule', id] })
    queryClient.invalidateQueries({ queryKey: ['schedules'] })
  }

  function handleActionError(e: unknown) {
    if (e instanceof ApiError && e.status === 404) {
      setActionError('Schedule not found. It may have been deleted.')
    } else {
      setActionError('Action failed. Try again in a moment.')
    }
  }

  const pauseMutation = useMutation({
    mutationFn: () => pauseSchedule(id),
    onSuccess: () => { setActionError(null); invalidate() },
    onError: (e) => handleActionError(e),
  })
  const resumeMutation = useMutation({
    mutationFn: () => resumeSchedule(id),
    onSuccess: () => { setActionError(null); invalidate() },
    onError: (e) => handleActionError(e),
  })
  const deleteMutation = useMutation({
    mutationFn: () => deleteSchedule(id),
    onSuccess: () => {
      // Invalidate the list so the deleted row disappears. The detail
      // caches get *removed*, not invalidated: the resource is gone,
      // and with a 10s default staleTime a revisit to this URL would
      // otherwise render the cached (deleted) schedule until the next
      // poll 404s. Removal makes a revisit go straight to the
      // not-found state. Then send the user back — there's nothing
      // more to show on this page.
      queryClient.invalidateQueries({ queryKey: ['schedules'] })
      queryClient.removeQueries({ queryKey: ['schedule', id] })
      queryClient.removeQueries({ queryKey: ['schedule-jobs', id] })
      navigate('/schedules', { replace: true })
    },
    onError: (e) => { setPending(null); handleActionError(e) },
  })

  if (scheduleQuery.isLoading) {
    return <p className="text-slate-500">Loading…</p>
  }
  if (scheduleQuery.isError || !scheduleQuery.data) {
    return (
      <div className="space-y-4">
        <p className="text-red-600">Schedule not found.</p>
        <Link to="/schedules" className="text-sm text-slate-600 hover:underline">
          ← Back to schedules
        </Link>
      </div>
    )
  }

  const schedule = scheduleQuery.data
  const actionBusy =
    pauseMutation.isPending || resumeMutation.isPending || deleteMutation.isPending

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center gap-3">
        <Link to="/schedules" className="text-sm text-slate-600 hover:underline">
          ← Schedules
        </Link>
        <span className="text-slate-300">/</span>
        <h1 className="text-2xl font-semibold text-slate-900">{schedule.name}</h1>
        <EnabledBadge enabled={schedule.enabled} />
        <div className="ml-auto flex items-center gap-2">
          {schedule.enabled ? (
            <Button
              variant="secondary"
              size="sm"
              onClick={() => { setActionError(null); pauseMutation.mutate() }}
              disabled={actionBusy}
            >
              Pause
            </Button>
          ) : (
            <Button
              size="sm"
              onClick={() => { setActionError(null); resumeMutation.mutate() }}
              disabled={actionBusy}
            >
              Resume
            </Button>
          )}
          <Button
            variant="secondary"
            size="sm"
            onClick={() => { setActionError(null); setPending('delete') }}
            disabled={actionBusy}
          >
            Delete
          </Button>
        </div>
      </div>

      {actionError && (
        <p className="text-sm text-red-600" role="alert">{actionError}</p>
      )}

      <ConfirmDialog
        open={pending === 'delete'}
        title="Delete this schedule?"
        description="The schedule will stop materializing new jobs. Jobs it has already created are unaffected."
        confirmLabel="Delete schedule"
        destructive
        busy={deleteMutation.isPending}
        onConfirm={() => deleteMutation.mutate()}
        onCancel={() => setPending(null)}
      />

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
        <MetadataCard schedule={schedule} />
        <div className="lg:col-span-2 space-y-6">
          <PayloadCard payload={schedule.payloadJson} />
          <RecentJobsCard
            jobs={jobsQuery.data?.content ?? []}
            loading={jobsQuery.isLoading}
          />
        </div>
      </div>
    </div>
  )
}

function MetadataCard({ schedule }: { schedule: JobScheduleResponse }) {
  return (
    <Card title="Metadata">
      <dl className="space-y-2 text-sm">
        <Row label="Type">{schedule.type}</Row>
        <Row label="Priority"><PriorityBadge priority={schedule.priority} /></Row>
        <Row label="Cron">
          <span className="font-mono text-xs">{schedule.cron}</span>
          {describeCron(schedule.cron) && (
            <span className="block text-xs text-slate-500">
              {describeCron(schedule.cron)}
            </span>
          )}
        </Row>
        <Row label="Timezone">{schedule.timezone}</Row>
        <Row label="Max retries">{schedule.maxRetries}</Row>
        <Row label="Owner">
          <span className="text-slate-700">{schedule.ownerEmail}</span>
        </Row>
        <Row label="Next run" title={formatAbsolute(schedule.nextRunAt)}>
          {formatRelative(schedule.nextRunAt)}
        </Row>
        <Row label="Last enqueued" title={formatAbsolute(schedule.lastEnqueuedAt)}>
          {formatRelative(schedule.lastEnqueuedAt)}
        </Row>
        <Row label="Created">{formatRelative(schedule.createdAt)}</Row>
        <Row label="Updated">{formatRelative(schedule.updatedAt)}</Row>
        <Row label="ID">
          <span className="font-mono text-xs text-slate-500 break-all">
            {schedule.id}
          </span>
        </Row>
      </dl>
    </Card>
  )
}

function PayloadCard({ payload }: { payload: string }) {
  let pretty = payload
  try {
    pretty = JSON.stringify(JSON.parse(payload), null, 2)
  } catch {
    // Show raw if not valid JSON.
  }
  return (
    <Card title="Payload template">
      <pre className="overflow-x-auto rounded-md bg-slate-50 p-3 text-xs text-slate-800">
        {pretty}
      </pre>
    </Card>
  )
}

function RecentJobsCard({
  jobs,
  loading,
}: { jobs: JobResponse[]; loading: boolean }) {
  return (
    <Card title="Recently materialized jobs">
      {loading && jobs.length === 0 && (
        <p className="text-sm text-slate-500">Loading…</p>
      )}
      {!loading && jobs.length === 0 && (
        <p className="text-sm text-slate-500">
          This schedule hasn't produced any jobs yet.
        </p>
      )}
      {jobs.length > 0 && (
        <div className="overflow-hidden rounded-md border border-slate-200">
          <table className="w-full text-sm">
            <thead className="bg-slate-50 text-left text-slate-600">
              <tr>
                <th className="px-3 py-2 font-medium">Status</th>
                <th className="px-3 py-2 font-medium">Created</th>
                <th className="px-3 py-2 font-medium">ID</th>
              </tr>
            </thead>
            <tbody>
              {jobs.map((j) => (
                <tr key={j.id} className="border-t border-slate-200">
                  <td className="px-3 py-2"><JobStatusBadge status={j.status} /></td>
                  <td className="px-3 py-2 text-slate-500">
                    {formatRelative(j.createdAt)}
                  </td>
                  <td className="px-3 py-2">
                    <Link
                      to={`/jobs/${j.id}`}
                      className="font-mono text-xs text-slate-900 hover:underline"
                    >
                      {shortId(j.id)}
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  )
}

function Card({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="rounded-lg border border-slate-200 bg-white p-4">
      <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-slate-500">
        {title}
      </h2>
      {children}
    </section>
  )
}

function Row({
  label,
  children,
  title,
}: { label: string; children: React.ReactNode; title?: string }) {
  return (
    <div className="flex items-baseline gap-3" title={title}>
      <dt className="w-32 shrink-0 text-slate-500">{label}</dt>
      <dd className="text-slate-800">{children}</dd>
    </div>
  )
}

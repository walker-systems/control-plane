import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { cancelJob, getJob, listExecutions, listJobAudit, retryJob } from '@/lib/jobs'
import type { AuditEventResponse, JobExecutionResponse, JobResponse, JobStatus } from '@/lib/types'
import { useAuthStore } from '@/lib/auth-store'
import { canReadAudit } from '@/lib/users'
import { ApiError } from '@/lib/api'
import {
  ExecutionStatusBadge,
  JobStatusBadge,
  PriorityBadge,
} from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { ConfirmDialog } from '@/components/ui/ConfirmDialog'
import { formatAbsolute, formatRelative, shortId } from '@/lib/format'

const CANCELLABLE: JobStatus[] = ['PENDING', 'RUNNING']
const RETRYABLE: JobStatus[] = ['DEAD_LETTER']

export function JobDetail() {
  const { id = '' } = useParams<{ id: string }>()
  // Audit read is OPERATOR/ADMIN only on the API side. USER role
  // gets 403 — polling anyway would generate a 403 every 3s and
  // silently render an empty card. Gate the query on the caller
  // actually having a role that can read it.
  const roles = useAuthStore((s) => s.user?.roles)
  const auditVisible = canReadAudit(roles)
  const queryClient = useQueryClient()
  const [pending, setPending] = useState<'cancel' | 'retry' | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)

  const jobQuery = useQuery({
    queryKey: ['job', id],
    queryFn: () => getJob(id),
    refetchInterval: 3_000,
    enabled: !!id,
  })
  const execsQuery = useQuery({
    queryKey: ['job-executions', id],
    queryFn: () => listExecutions(id),
    refetchInterval: 3_000,
    enabled: !!id,
  })
  const auditQuery = useQuery({
    queryKey: ['job-audit', id],
    queryFn: () => listJobAudit(id),
    refetchInterval: 3_000,
    enabled: !!id && auditVisible,
  })

  // Invalidate everything the action might have changed so the
  // UI reflects the outcome immediately instead of waiting for the
  // next 3s poll tick. Also refresh the dashboard stats and the
  // jobs list — this job's status just changed, and its owner's
  // aggregate counts moved with it.
  function invalidate() {
    queryClient.invalidateQueries({ queryKey: ['job', id] })
    queryClient.invalidateQueries({ queryKey: ['job-executions', id] })
    if (auditVisible) {
      queryClient.invalidateQueries({ queryKey: ['job-audit', id] })
    }
    queryClient.invalidateQueries({ queryKey: ['job-stats'] })
    queryClient.invalidateQueries({ queryKey: ['jobs'] })
    queryClient.invalidateQueries({ queryKey: ['jobs-recent'] })
  }

  function handleActionError(e: unknown) {
    if (e instanceof ApiError && e.status === 409) {
      // 409 comes from JobStateException — the status changed
      // under us (executor picked it up, watchdog reclaimed, etc.).
      // Refetching gives the user an updated view.
      setActionError('Job state changed. Refreshed with the latest.')
      invalidate()
    } else {
      setActionError('Action failed. Try again in a moment.')
    }
  }

  const cancelMutation = useMutation({
    mutationFn: () => cancelJob(id),
    onSuccess: () => { setPending(null); setActionError(null); invalidate() },
    onError: (e) => { setPending(null); handleActionError(e) },
  })
  const retryMutation = useMutation({
    mutationFn: () => retryJob(id),
    onSuccess: () => { setPending(null); setActionError(null); invalidate() },
    onError: (e) => { setPending(null); handleActionError(e) },
  })

  if (jobQuery.isLoading) {
    return <p className="text-slate-500">Loading…</p>
  }
  if (jobQuery.isError || !jobQuery.data) {
    return (
      <div className="space-y-4">
        <p className="text-red-600">Job not found.</p>
        <Link to="/jobs" className="text-sm text-slate-600 hover:underline">← Back to jobs</Link>
      </div>
    )
  }

  const job = jobQuery.data
  const canCancel = CANCELLABLE.includes(job.status)
  const canRetry = RETRYABLE.includes(job.status)

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center gap-3">
        <Link to="/jobs" className="text-sm text-slate-600 hover:underline">← Jobs</Link>
        <span className="text-slate-300">/</span>
        <h1 className="text-2xl font-semibold text-slate-900">
          <span className="font-mono">{shortId(job.id)}</span>
        </h1>
        <JobStatusBadge status={job.status} />
        <div className="ml-auto flex items-center gap-2">
          {canCancel && (
            <Button
              variant="secondary"
              size="sm"
              onClick={() => { setActionError(null); setPending('cancel') }}
              disabled={cancelMutation.isPending}
            >
              Cancel job
            </Button>
          )}
          {canRetry && (
            <Button
              size="sm"
              onClick={() => { setActionError(null); setPending('retry') }}
              disabled={retryMutation.isPending}
            >
              Retry job
            </Button>
          )}
        </div>
      </div>

      {actionError && (
        <p className="text-sm text-red-600" role="alert">{actionError}</p>
      )}

      <ConfirmDialog
        open={pending === 'cancel'}
        title="Cancel this job?"
        description={
          job.status === 'RUNNING'
            ? 'Marks the job for cancellation. If a handler is currently running, its attempt still records its outcome — the job transitions to CANCELLED at completion.'
            : 'The job is still PENDING and will transition to CANCELLED immediately.'
        }
        confirmLabel="Cancel job"
        destructive
        busy={cancelMutation.isPending}
        onConfirm={() => cancelMutation.mutate()}
        onCancel={() => setPending(null)}
      />
      <ConfirmDialog
        open={pending === 'retry'}
        title="Retry this dead-lettered job?"
        description="Transitions the job back to PENDING. The executor will pick it up on the next tick with a fresh attempt."
        confirmLabel="Retry job"
        busy={retryMutation.isPending}
        onConfirm={() => retryMutation.mutate()}
        onCancel={() => setPending(null)}
      />

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
        <MetadataCard job={job} />
        <div className="lg:col-span-2 space-y-6">
          <PayloadCard payload={job.payloadJson} />
          <ExecutionsCard executions={execsQuery.data ?? []} loading={execsQuery.isLoading} />
          {auditVisible && (
            <AuditCard events={auditQuery.data?.content ?? []} loading={auditQuery.isLoading} />
          )}
        </div>
      </div>
    </div>
  )
}

function MetadataCard({ job }: { job: JobResponse }) {
  return (
    <Card title="Metadata">
      <dl className="space-y-2 text-sm">
        <Row label="Type">{job.type}</Row>
        <Row label="Priority"><PriorityBadge priority={job.priority} /></Row>
        <Row label="Attempts">{job.attemptCount} / {job.maxRetries + 1}</Row>
        <Row label="Owner"><span className="text-slate-700">{job.ownerEmail}</span></Row>
        <Row label="Available at">{formatAbsolute(job.availableAt)}</Row>
        {job.cancelRequestedAt && (
          <Row label="Cancel requested">{formatAbsolute(job.cancelRequestedAt)}</Row>
        )}
        {job.sourceScheduleId && (
          <Row label="From schedule">
            <span className="font-mono text-xs">{shortId(job.sourceScheduleId)}</span>
          </Row>
        )}
        <Row label="Created">{formatRelative(job.createdAt)}</Row>
        <Row label="Updated">{formatRelative(job.updatedAt)}</Row>
        <Row label="ID">
          <span className="font-mono text-xs text-slate-500 break-all">{job.id}</span>
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
    // Leave as-is if not valid JSON.
  }
  return (
    <Card title="Payload">
      <pre className="overflow-x-auto rounded-md bg-slate-50 p-3 text-xs text-slate-800">
        {pretty}
      </pre>
    </Card>
  )
}

function ExecutionsCard({
  executions,
  loading,
}: { executions: JobExecutionResponse[]; loading: boolean }) {
  return (
    <Card title="Executions">
      {loading && executions.length === 0 && (
        <p className="text-sm text-slate-500">Loading…</p>
      )}
      {!loading && executions.length === 0 && (
        <p className="text-sm text-slate-500">No attempts yet.</p>
      )}
      {executions.length > 0 && (
        <div className="overflow-hidden rounded-md border border-slate-200">
          <table className="w-full text-sm">
            <thead className="bg-slate-50 text-left text-slate-600">
              <tr>
                <th className="px-3 py-2 font-medium">Attempt</th>
                <th className="px-3 py-2 font-medium">Status</th>
                <th className="px-3 py-2 font-medium">Started</th>
                <th className="px-3 py-2 font-medium">Finished</th>
                <th className="px-3 py-2 font-medium">Detail</th>
              </tr>
            </thead>
            <tbody>
              {executions.map((e) => (
                <tr key={e.id} className="border-t border-slate-200">
                  <td className="px-3 py-2 text-slate-700">#{e.attemptNumber}</td>
                  <td className="px-3 py-2"><ExecutionStatusBadge status={e.status} /></td>
                  <td className="px-3 py-2 text-slate-500">{formatRelative(e.startedAt)}</td>
                  <td className="px-3 py-2 text-slate-500">{formatRelative(e.finishedAt)}</td>
                  <td className="px-3 py-2 text-slate-700">
                    {e.errorMessage
                      ? <span className="text-red-700">{e.errorMessage}</span>
                      : e.outputSummary ?? <span className="text-slate-400">—</span>}
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

function AuditCard({
  events,
  loading,
}: { events: AuditEventResponse[]; loading: boolean }) {
  return (
    <Card title="Audit trail">
      {loading && events.length === 0 && (
        <p className="text-sm text-slate-500">Loading…</p>
      )}
      {!loading && events.length === 0 && (
        <p className="text-sm text-slate-500">No audit events.</p>
      )}
      {events.length > 0 && (
        <ol className="space-y-2 text-sm">
          {events.map((e) => (
            <li key={e.id} className="flex items-baseline gap-3">
              <span className="w-40 shrink-0 text-slate-500">
                {formatRelative(e.createdAt)}
              </span>
              <span className="font-medium text-slate-800">{e.eventType}</span>
              <AuditMetadata json={e.metadataJson} />
            </li>
          ))}
        </ol>
      )}
    </Card>
  )
}

// Audit metadata comes over the wire as a JSON string. Show it as a
// compact one-liner beside the event type; skip if empty or unparseable.
function AuditMetadata({ json }: { json: string | null }) {
  if (!json) return null
  try {
    const parsed = JSON.parse(json)
    if (parsed && typeof parsed === 'object' && Object.keys(parsed).length === 0) {
      return null
    }
    return (
      <span className="text-slate-500 text-xs">
        {JSON.stringify(parsed)}
      </span>
    )
  } catch {
    return <span className="text-slate-500 text-xs">{json}</span>
  }
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

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-baseline gap-3">
      <dt className="w-32 shrink-0 text-slate-500">{label}</dt>
      <dd className="text-slate-800">{children}</dd>
    </div>
  )
}

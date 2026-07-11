import { Link, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { getJob, listExecutions, listJobAudit } from '@/lib/jobs'
import type { AuditEventResponse, JobExecutionResponse, JobResponse } from '@/lib/types'
import {
  ExecutionStatusBadge,
  JobStatusBadge,
  PriorityBadge,
} from '@/components/ui/Badge'
import { formatAbsolute, formatRelative, shortId } from '@/lib/format'

export function JobDetail() {
  const { id = '' } = useParams<{ id: string }>()

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
    enabled: !!id,
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

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-3">
        <Link to="/jobs" className="text-sm text-slate-600 hover:underline">← Jobs</Link>
        <span className="text-slate-300">/</span>
        <h1 className="text-2xl font-semibold text-slate-900">
          <span className="font-mono">{shortId(job.id)}</span>
        </h1>
        <JobStatusBadge status={job.status} />
      </div>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
        <MetadataCard job={job} />
        <div className="lg:col-span-2 space-y-6">
          <PayloadCard payload={job.payloadJson} />
          <ExecutionsCard executions={execsQuery.data ?? []} loading={execsQuery.isLoading} />
          <AuditCard events={auditQuery.data?.content ?? []} loading={auditQuery.isLoading} />
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
              {e.metadata && Object.keys(e.metadata).length > 0 && (
                <span className="text-slate-500 text-xs">
                  {JSON.stringify(e.metadata)}
                </span>
              )}
            </li>
          ))}
        </ol>
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

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-baseline gap-3">
      <dt className="w-32 shrink-0 text-slate-500">{label}</dt>
      <dd className="text-slate-800">{children}</dd>
    </div>
  )
}

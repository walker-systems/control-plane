import type { JobExecutionStatus, JobPriority, JobStatus } from '@/lib/types'

// Small colored pill used for JobStatus, JobExecutionStatus, and
// JobPriority. Colors are deliberately consistent across the three
// families — green = healthy, red = failed, amber = in-progress,
// slate = neutral/terminal-quiet.

const base =
  'inline-flex items-center rounded-md px-2 py-0.5 text-xs font-medium ring-1 ring-inset'

const jobStatusStyle: Record<JobStatus, string> = {
  PENDING: 'bg-slate-100 text-slate-700 ring-slate-300',
  RUNNING: 'bg-amber-100 text-amber-800 ring-amber-300',
  SUCCEEDED: 'bg-emerald-100 text-emerald-800 ring-emerald-300',
  FAILED: 'bg-red-100 text-red-800 ring-red-300',
  DEAD_LETTER: 'bg-red-200 text-red-900 ring-red-400',
  CANCELLED: 'bg-slate-200 text-slate-700 ring-slate-300',
}

const execStatusStyle: Record<JobExecutionStatus, string> = {
  RUNNING: jobStatusStyle.RUNNING,
  SUCCEEDED: jobStatusStyle.SUCCEEDED,
  FAILED: jobStatusStyle.FAILED,
  TIMED_OUT: 'bg-orange-100 text-orange-800 ring-orange-300',
}

const priorityStyle: Record<JobPriority, string> = {
  LOW: 'bg-slate-100 text-slate-600 ring-slate-300',
  MEDIUM: 'bg-slate-100 text-slate-700 ring-slate-300',
  HIGH: 'bg-indigo-100 text-indigo-800 ring-indigo-300',
}

export function JobStatusBadge({ status }: { status: JobStatus }) {
  return <span className={`${base} ${jobStatusStyle[status]}`}>{status}</span>
}

export function ExecutionStatusBadge({ status }: { status: JobExecutionStatus }) {
  return <span className={`${base} ${execStatusStyle[status]}`}>{status}</span>
}

export function PriorityBadge({ priority }: { priority: JobPriority }) {
  return <span className={`${base} ${priorityStyle[priority]}`}>{priority}</span>
}

export function EnabledBadge({ enabled }: { enabled: boolean }) {
  const style = enabled
    ? 'bg-emerald-100 text-emerald-800 ring-emerald-300'
    : 'bg-slate-200 text-slate-700 ring-slate-300'
  return <span className={`${base} ${style}`}>{enabled ? 'ENABLED' : 'PAUSED'}</span>
}

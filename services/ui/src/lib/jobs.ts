import { api } from '@/lib/api'
import type {
  AuditEventResponse,
  JobExecutionResponse,
  JobResponse,
  JobStatus,
  JobType,
  Page,
} from '@/lib/types'

export interface ListJobsParams {
  status?: JobStatus
  type?: JobType
  page?: number
  size?: number
}

function toQueryString(params: Record<string, string | number | undefined>): string {
  const parts: string[] = []
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== '') {
      parts.push(`${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
    }
  }
  return parts.length ? '?' + parts.join('&') : ''
}

export function listJobs(params: ListJobsParams = {}): Promise<Page<JobResponse>> {
  const qs = toQueryString({
    status: params.status,
    type: params.type,
    page: params.page,
    size: params.size,
  })
  return api<Page<JobResponse>>('/api/jobs' + qs)
}

export function getJob(id: string): Promise<JobResponse> {
  return api<JobResponse>('/api/jobs/' + id)
}

export function listExecutions(jobId: string): Promise<JobExecutionResponse[]> {
  return api<JobExecutionResponse[]>('/api/jobs/' + jobId + '/executions')
}

export function listJobAudit(jobId: string): Promise<Page<AuditEventResponse>> {
  return api<Page<AuditEventResponse>>('/api/audit/target/Job/' + jobId)
}

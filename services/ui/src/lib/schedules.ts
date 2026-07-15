import { api } from '@/lib/api'
import type {
  JobScheduleCreateRequest,
  JobScheduleResponse,
  JobType,
  Page,
} from '@/lib/types'

export interface ListSchedulesParams {
  enabled?: boolean
  type?: JobType
  page?: number
  size?: number
}

function toQueryString(params: Record<string, string | number | boolean | undefined>): string {
  const parts: string[] = []
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== '') {
      parts.push(`${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
    }
  }
  return parts.length ? '?' + parts.join('&') : ''
}

export function listSchedules(
  params: ListSchedulesParams = {},
): Promise<Page<JobScheduleResponse>> {
  const qs = toQueryString({
    enabled: params.enabled,
    type: params.type,
    page: params.page,
    size: params.size,
  })
  return api<Page<JobScheduleResponse>>('/api/schedules' + qs)
}

export function getSchedule(id: string): Promise<JobScheduleResponse> {
  return api<JobScheduleResponse>('/api/schedules/' + id)
}

export function createSchedule(
  body: JobScheduleCreateRequest,
): Promise<JobScheduleResponse> {
  return api<JobScheduleResponse>('/api/schedules', { method: 'POST', body })
}

export function pauseSchedule(id: string): Promise<JobScheduleResponse> {
  return api<JobScheduleResponse>('/api/schedules/' + id + '/pause', { method: 'POST' })
}

export function resumeSchedule(id: string): Promise<JobScheduleResponse> {
  return api<JobScheduleResponse>('/api/schedules/' + id + '/resume', { method: 'POST' })
}

export function deleteSchedule(id: string): Promise<void> {
  return api<void>('/api/schedules/' + id, { method: 'DELETE' })
}

// API response types. Kept close to what the Java DTOs actually send —
// don't add fields here that the server doesn't return, and don't
// rename anything: the whole point is to match Jackson's default
// name mapping so no client-side massaging is needed.

export type JobStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'DEAD_LETTER'
  | 'CANCELLED'

export type JobPriority = 'LOW' | 'MEDIUM' | 'HIGH'

export type JobType =
  | 'CUSTOMER_EXPORT'
  | 'STALE_ACCOUNT_CLEANUP'
  | 'SUSPICIOUS_ACCOUNT_SCAN'
  | 'CRM_SYNC'

export type JobExecutionStatus =
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'TIMED_OUT'

export interface JobResponse {
  id: string
  ownerId: string
  ownerEmail: string
  type: JobType
  payloadJson: string
  status: JobStatus
  priority: JobPriority
  idempotencyKey: string | null
  maxRetries: number
  attemptCount: number
  availableAt: string | null
  cancelRequestedAt: string | null
  sourceScheduleId: string | null
  createdAt: string
  updatedAt: string
}

export interface JobExecutionResponse {
  id: string
  jobId: string
  attemptNumber: number
  status: JobExecutionStatus
  workerId: string
  startedAt: string | null
  finishedAt: string | null
  leaseExpiresAt: string | null
  errorMessage: string | null
  outputSummary: string | null
  createdAt: string
}

export interface AuditEventResponse {
  id: string
  eventType: string
  actorUserId: string | null
  targetType: string | null
  targetId: string | null
  // The API sends this as a JSON string (`metadataJson`), not a parsed
  // object. Callers that want the structured payload should JSON.parse
  // it — see AuditEventResponse.java on the server side.
  metadataJson: string | null
  createdAt: string
}

// Spring's Page<T> wire format — the fields we actually use. The full
// shape has more (sort descriptors, pageable metadata), but content,
// total counts, and page indices are enough for our list UI.
export interface Page<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number // current page, zero-indexed
  size: number
  first: boolean
  last: boolean
}

// GET /api/jobs/stats — counts by JobStatus. Every JobStatus is
// present in the map even when count is zero (the API pads gaps),
// so tiles can render without null-checking each key.
export interface JobStatsResponse {
  counts: Record<JobStatus, number>
}

export interface JobScheduleResponse {
  id: string
  ownerId: string
  ownerEmail: string
  name: string
  type: JobType
  payloadJson: string
  priority: JobPriority
  maxRetries: number
  cron: string
  timezone: string
  enabled: boolean
  nextRunAt: string | null
  lastEnqueuedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface JobScheduleCreateRequest {
  name: string
  type: JobType
  payloadJson: string
  priority?: JobPriority
  maxRetries?: number
  cron: string
  timezone: string
}

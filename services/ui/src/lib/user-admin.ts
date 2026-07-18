import { api, ApiError } from '@/lib/api'
import type {
  Page,
  UserCreateRequest,
  UserResponse,
  UserUpdateRequest,
} from '@/lib/types'

export interface ListUsersParams {
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

export function listUsers(params: ListUsersParams = {}): Promise<Page<UserResponse>> {
  return api<Page<UserResponse>>('/api/users' + toQueryString({ page: params.page, size: params.size }))
}

export function createUser(body: UserCreateRequest): Promise<UserResponse> {
  return api<UserResponse>('/api/users', { method: 'POST', body })
}

export function updateUser(id: string, body: UserUpdateRequest): Promise<UserResponse> {
  return api<UserResponse>('/api/users/' + id, { method: 'PATCH', body })
}

// Maps the API's ProblemDetail reasons to actionable copy; falls back
// to the server's detail, then a generic line. Shared by the users
// page and its dialogs.
export function describeUserError(e: unknown): string {
  if (e instanceof ApiError) {
    const problem = (e.body ?? {}) as { detail?: string; reason?: string }
    switch (problem.reason) {
      case 'DUPLICATE_EMAIL': return 'A user with that email already exists.'
      case 'UNKNOWN_ROLE': return 'That role does not exist.'
      case 'SELF_MODIFICATION': return 'You cannot change your own account.'
    }
    if (typeof problem.detail === 'string' && problem.detail) return problem.detail
  }
  return 'Action failed. Try again in a moment.'
}

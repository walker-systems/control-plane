// Small formatting helpers shared by list and detail views. All API
// timestamps come across as ISO strings.

export function formatRelative(iso: string | null): string {
  if (!iso) return '—'
  const then = new Date(iso).getTime()
  const now = Date.now()
  const diffSec = Math.round((now - then) / 1000)
  const abs = Math.abs(diffSec)
  const past = diffSec >= 0
  const suffix = past ? ' ago' : ''
  const prefix = past ? '' : 'in '
  if (abs < 5) return 'just now'
  if (abs < 60) return `${prefix}${abs}s${suffix}`
  if (abs < 3600) return `${prefix}${Math.round(abs / 60)}m${suffix}`
  if (abs < 86400) return `${prefix}${Math.round(abs / 3600)}h${suffix}`
  return `${prefix}${Math.round(abs / 86400)}d${suffix}`
}

export function formatAbsolute(iso: string | null): string {
  if (!iso) return '—'
  const d = new Date(iso)
  return d.toLocaleString()
}

export function shortId(id: string): string {
  return id.slice(0, 8)
}

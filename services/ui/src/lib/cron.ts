import cronstrue from 'cronstrue'

// Human-readable preview of a 6-field Spring cron expression, or null
// when no sensible description exists. Display convenience only — the
// API's CronExpression.parse stays the validation authority. cronstrue
// is deliberately more lenient than Spring (it also reads Quartz
// syntax), so a description here doesn't guarantee the server will
// accept the expression; it just makes the common case legible.
export function describeCron(expr: string): string | null {
  const trimmed = expr.trim()
  if (!trimmed) return null
  // Spring requires exactly six fields (sec min hour day month
  // weekday). cronstrue happily describes 5-field crons too — which
  // the API rejects — so gate on field count rather than blessing an
  // expression the server will 400.
  if (trimmed.split(/\s+/).length !== 6) return null
  try {
    return cronstrue.toString(trimmed)
  } catch {
    return null
  }
}

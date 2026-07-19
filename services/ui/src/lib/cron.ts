import cronstrue from 'cronstrue'

// --- Builder: structured schedule choices → cron expression --------
//
// Deliberately covers the common shapes only (fixed intervals, daily/
// weekly/monthly at a time of day); everything else is the Custom
// mode's raw input. One-way: we generate cron from choices but never
// parse arbitrary cron back into the controls — that inverse mapping
// is ambiguous and the create form never needs it.

export type IntervalUnit = 'seconds' | 'minutes' | 'hours'

// Only divisors of the parent unit: */45 on seconds would fire at :00
// and :45 — a 15s gap at the minute wrap — so uneven steps aren't
// offered at all.
//
// Seconds start at 30 because of the scheduler's materialization
// floor: ScheduleTickJob wakes every SCHEDULING_TICK_INTERVAL_MS
// (default 30s) and advanceNextRunAt jumps to the next fire *after*
// the tick, so a */5s cron would materialize one job per tick — an
// effective 30s cadence wearing an "every 5 seconds" label. Don't
// offer what the scheduler can't honor.
export const INTERVAL_CHOICES: Record<IntervalUnit, number[]> = {
  seconds: [30],
  minutes: [1, 2, 5, 10, 15, 30],
  hours: [1, 2, 3, 4, 6, 12],
}

export const WEEKDAYS = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'] as const
export type Weekday = (typeof WEEKDAYS)[number]

export type CronBuild =
  | { kind: 'interval'; n: number; unit: IntervalUnit }
  | { kind: 'daily'; time: string }
  | { kind: 'weekly'; weekday: Weekday; time: string }
  | { kind: 'monthly'; day: number; time: string }

// time is an <input type="time"> value: "HH:MM" (24h).
function splitTime(time: string): [number, number] {
  const [h, m] = time.split(':').map(Number)
  return [Number.isFinite(h) ? h : 0, Number.isFinite(m) ? m : 0]
}

export function buildCron(b: CronBuild): string {
  switch (b.kind) {
    case 'interval': {
      if (b.unit === 'seconds') return b.n === 1 ? '* * * * * *' : `*/${b.n} * * * * *`
      if (b.unit === 'minutes') return b.n === 1 ? '0 * * * * *' : `0 */${b.n} * * * *`
      return b.n === 1 ? '0 0 * * * *' : `0 0 */${b.n} * * *`
    }
    case 'daily': {
      const [h, m] = splitTime(b.time)
      return `0 ${m} ${h} * * *`
    }
    case 'weekly': {
      const [h, m] = splitTime(b.time)
      return `0 ${m} ${h} * * ${b.weekday}`
    }
    case 'monthly': {
      const [h, m] = splitTime(b.time)
      return `0 ${m} ${h} ${b.day} * *`
    }
  }
}

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

#!/usr/bin/env bash
# Seed the local demo with schedules and a burst of jobs so the
# dashboard has live data immediately instead of an empty table.
#
#   ./scripts/seed-demo.sh                # against http://localhost:8000
#   BASE_URL=http://localhost:9000 ./scripts/seed-demo.sh
#
# Idempotent: re-running skips schedules that already exist (409) and
# just adds another burst of one-off jobs.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8000}"
EMAIL="${DEMO_EMAIL:-demo@control-plane.dev}"
PASSWORD="${DEMO_PASSWORD:-demo-password}"

say() { printf '%s\n' "$*"; }

# --- login ---------------------------------------------------------
say "Logging in as $EMAIL ..."
TOKEN=$(curl -sf -X POST "$BASE_URL/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["accessToken"])') \
  || { say "Login failed — is the demo stack up? (docker compose -f deploy/compose.demo.yml up -d)"; exit 1; }

auth=(-H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json')

# --- schedules -----------------------------------------------------
# One healthy every-minute schedule, and one flaky type with zero
# retries so DEAD_LETTER rows show up — the retry button needs
# something to act on.
create_schedule() {
  local body="$1" name="$2"
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/schedules" "${auth[@]}" -d "$body")
  case "$code" in
    201) say "  created schedule: $name" ;;
    409) say "  schedule exists:  $name (skipping)" ;;
    *)   say "  FAILED ($code):   $name"; exit 1 ;;
  esac
}

say "Creating schedules ..."
create_schedule '{
  "name": "crm-sync-every-minute",
  "type": "CRM_SYNC",
  "payloadJson": "{\"segment\":\"demo\"}",
  "priority": "MEDIUM",
  "maxRetries": 2,
  "cron": "0 * * * * *",
  "timezone": "UTC"
}' crm-sync-every-minute

create_schedule '{
  "name": "suspicious-scan-flaky",
  "type": "SUSPICIOUS_ACCOUNT_SCAN",
  "payloadJson": "{\"threshold\":0.8}",
  "priority": "HIGH",
  "maxRetries": 0,
  "cron": "30 */2 * * * *",
  "timezone": "UTC"
}' suspicious-scan-flaky

# --- one-off job burst ---------------------------------------------
say "Creating a burst of one-off jobs ..."
jobs=(
  '{"type":"CUSTOMER_EXPORT","payloadJson":"{\"format\":\"csv\",\"rows\":50000}","priority":"HIGH"}'
  '{"type":"CUSTOMER_EXPORT","payloadJson":"{\"format\":\"parquet\",\"rows\":120000}","priority":"LOW"}'
  '{"type":"STALE_ACCOUNT_CLEANUP","payloadJson":"{\"olderThanDays\":365}","priority":"MEDIUM"}'
  '{"type":"STALE_ACCOUNT_CLEANUP","payloadJson":"{\"olderThanDays\":90}","priority":"LOW"}'
  '{"type":"CRM_SYNC","payloadJson":"{\"segment\":\"enterprise\"}","priority":"HIGH"}'
  '{"type":"SUSPICIOUS_ACCOUNT_SCAN","payloadJson":"{\"threshold\":0.5}","priority":"HIGH","maxRetries":1}'
)
created=0
for body in "${jobs[@]}"; do
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/jobs" "${auth[@]}" -d "$body")
  [ "$code" = "201" ] && created=$((created+1)) || say "  job create returned $code"
done
say "  created $created jobs"

say ""
say "Done. Open $BASE_URL — the executor picks jobs up within ~5s;"
say "the schedules fire on the next minute boundary."
